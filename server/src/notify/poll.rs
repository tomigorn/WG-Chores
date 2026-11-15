use crate::app::AppState;
use crate::auth::CurrentUser;
use crate::domain::{DueLevel, Interval, due_level, interval_days};
use crate::error::ApiError;
use axum::{
    Json,
    extract::{RawQuery, State},
};
use base64::{Engine, engine::general_purpose::URL_SAFE_NO_PAD};
use chrono::NaiveDate;
use rusqlite::params;
use serde_json::{Value, json};

const MAX_DONE: usize = 50;

fn encode(rowid: i64) -> String {
    URL_SAFE_NO_PAD.encode(rowid.to_be_bytes())
}

fn decode(raw: &str) -> Option<i64> {
    let bytes: [u8; 8] = URL_SAFE_NO_PAD.decode(raw).ok()?.try_into().ok()?;
    Some(i64::from_be_bytes(bytes)).filter(|v| *v >= 0)
}

/// `GET /api/v1/notifications/poll`: the app's background check; done-by-others since the cursor and chores newly due for this device.
pub async fn poll(
    State(st): State<AppState>,
    u: CurrentUser,
    RawQuery(q): RawQuery,
) -> Result<Json<Value>, ApiError> {
    if !u.bearer {
        return Err(ApiError::forbidden(
            "app_only",
            "Only the app polls for notifications.",
        ));
    }
    let since = url::form_urlencoded::parse(q.as_deref().unwrap_or("").as_bytes())
        .find(|(k, _)| k == "since")
        .map(|(_, v)| v.into_owned());
    let since =
        match since.as_deref() {
            None => None,
            Some(raw) => Some(decode(raw).ok_or_else(|| {
                ApiError::bad_request("invalid_cursor", "The cursor is not valid.")
            })?),
        };
    let today = st.clock.now().with_timezone(&st.cfg.tz).date_naive();
    let (uid, session) = (u.id, u.session_hash.clone());
    let out = st
        .db
        .run(move |c| {
            let tx = c.transaction()?;
            let (want_due, want_done): (bool, bool) = tx.query_row(
                "SELECT notify_due, notify_done FROM users WHERE id = ?1",
                [uid],
                |r| Ok((r.get(0)?, r.get(1)?)),
            )?;
            let newest: i64 =
                tx.query_row("SELECT COALESCE(MAX(rowid), 0) FROM completions", [], |r| {
                    r.get(0)
                })?;
            let (done, cursor, more) = match since {
                Some(since) if want_done => done_since(&tx, uid, since, newest)?,
                Some(since) => (Vec::new(), since.max(newest), false),
                None => (Vec::new(), newest, false),
            };
            let due = if want_due {
                due_now(&tx, uid, &session, today)?
            } else {
                Vec::new()
            };
            tx.commit()?;
            Ok(json!({"cursor": encode(cursor), "done": done, "due": due, "more": more}))
        })
        .await?;
    Ok(Json(out))
}

fn done_since(
    tx: &rusqlite::Transaction,
    uid: i64,
    since: i64,
    newest: i64,
) -> rusqlite::Result<(Vec<Value>, i64, bool)> {
    let mut s = tx.prepare(
        "SELECT co.rowid, h.id, h.name, ch.id, ch.name, u.display_name, co.done_on
         FROM completions co JOIN chores ch ON ch.id = co.chore_id
         JOIN households h ON h.id = ch.household_id
         JOIN memberships m ON m.household_id = h.id AND m.user_id = ?1
         JOIN users u ON u.id = co.user_id
         WHERE co.rowid > ?2 AND co.deleted_at IS NULL AND co.user_id != ?1
         ORDER BY co.rowid LIMIT ?3",
    )?;
    let rows = s
        .query_map(params![uid, since, MAX_DONE as i64 + 1], |r| {
            Ok((
                r.get::<_, i64>(0)?,
                json!({
                    "household": {"id": r.get::<_, i64>(1)?, "name": r.get::<_, String>(2)?},
                    "chore": {"id": r.get::<_, i64>(3)?, "name": r.get::<_, String>(4)?},
                    "by": r.get::<_, String>(5)?,
                    "done_on": r.get::<_, String>(6)?,
                }),
            ))
        })?
        .collect::<rusqlite::Result<Vec<_>>>()?;
    let more = rows.len() > MAX_DONE;
    let rows: Vec<(i64, Value)> = rows.into_iter().take(MAX_DONE).collect();
    let cursor = match rows.last() {
        Some((last, _)) if more => *last,
        _ => since.max(newest),
    };
    Ok((rows.into_iter().map(|(_, v)| v).collect(), cursor, more))
}

/// Chores due now that this device was not told about in their current due period; they are marked as told.
fn due_now(
    tx: &rusqlite::Transaction,
    uid: i64,
    session: &str,
    today: NaiveDate,
) -> rusqlite::Result<Vec<Value>> {
    let mut s = tx.prepare(
        "SELECT h.id, h.name, ch.id, ch.name, ch.interval,
           (SELECT MAX(co.done_on) FROM completions co WHERE co.chore_id = ch.id AND co.deleted_at IS NULL),
           ns.last_due_notified_on
         FROM memberships m JOIN households h ON h.id = m.household_id
         JOIN chores ch ON ch.household_id = h.id AND ch.archived_at IS NULL
         LEFT JOIN device_notification_state ns ON ns.session_hash = ?2 AND ns.chore_id = ch.id
         WHERE m.user_id = ?1
         ORDER BY ulower(h.name), h.id, ulower(ch.name), ch.id",
    )?;
    let rows = s
        .query_map(params![uid, session], |r| {
            Ok((
                r.get::<_, i64>(0)?,
                r.get::<_, String>(1)?,
                r.get::<_, i64>(2)?,
                r.get::<_, String>(3)?,
                r.get::<_, Option<String>>(4)?,
                r.get::<_, Option<String>>(5)?,
                r.get::<_, Option<String>>(6)?,
            ))
        })?
        .collect::<rusqlite::Result<Vec<_>>>()?;
    let mut out: Vec<Value> = Vec::new();
    let mut last_household = None;
    for (hid, hname, chore, name, interval, last, notified) in rows {
        let Some(period) = last else { continue };
        let Ok(done_on) = period.parse::<NaiveDate>() else {
            continue;
        };
        let iv = interval.as_deref().and_then(Interval::from_code);
        let level = due_level(Some((today - done_on).num_days()), interval_days(iv));
        if !matches!(
            level,
            DueLevel::Due | DueLevel::Overdue | DueLevel::LongOverdue
        ) || notified.as_deref() == Some(period.as_str())
        {
            continue;
        }
        tx.execute(
            "INSERT INTO device_notification_state (session_hash, chore_id, last_due_notified_on) VALUES (?1, ?2, ?3)
             ON CONFLICT (session_hash, chore_id) DO UPDATE SET last_due_notified_on = excluded.last_due_notified_on",
            params![session, chore, period],
        )?;
        if last_household != Some(hid) {
            out.push(json!({"household": {"id": hid, "name": hname}, "chores": []}));
            last_household = Some(hid);
        }
        if let Some(Value::Array(list)) = out.last_mut().and_then(|g| g.get_mut("chores")) {
            list.push(json!({"id": chore, "name": name}));
        }
    }
    Ok(out)
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn cursor_roundtrips_and_rejects_garbage() {
        assert_eq!(decode(&encode(0)), Some(0));
        assert_eq!(decode(&encode(123_456)), Some(123_456));
        assert_eq!(decode(&encode(-1)), None);
        assert_eq!(decode("abc"), None);
        assert_eq!(decode("!!"), None);
    }
}
