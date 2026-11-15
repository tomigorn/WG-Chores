use crate::app::AppState;
use crate::auth::CurrentUser;
use crate::auth::session::ts;
use crate::error::ApiError;
use axum::{
    Json,
    extract::{RawQuery, State},
};
use base64::{Engine, engine::general_purpose::URL_SAFE_NO_PAD};
use chrono::NaiveDate;
use rusqlite::{OptionalExtension, params};
use serde_json::{Value, json};

const MAX_DONE: usize = 50;

/// Done events seq and the number of the poll that answered; the next poll acknowledges that poll's due notices with it.
#[derive(Clone, Copy, Debug, PartialEq)]
struct Cursor {
    seq: i64,
    poll: i64,
}

fn encode(c: Cursor) -> String {
    let mut b = [0u8; 16];
    b[..8].copy_from_slice(&c.seq.to_be_bytes());
    b[8..].copy_from_slice(&c.poll.to_be_bytes());
    URL_SAFE_NO_PAD.encode(b)
}

fn decode(raw: &str) -> Option<Cursor> {
    let b: [u8; 16] = URL_SAFE_NO_PAD.decode(raw).ok()?.try_into().ok()?;
    let seq = i64::from_be_bytes(b[..8].try_into().ok()?);
    let poll = i64::from_be_bytes(b[8..].try_into().ok()?);
    (seq >= 0 && poll >= 0).then_some(Cursor { seq, poll })
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
    let now = st.clock.now();
    let today = now.with_timezone(&st.cfg.tz).date_naive();
    let settled = ts(now - chrono::Duration::from_std(super::DONE_DELAY).expect("delay"));
    let (uid, session) = (u.id, u.session_hash.clone());
    let out = st
        .db
        .run(move |c| {
            let tx = c.transaction()?;
            if let Some(cur) = since {
                tx.execute(
                    "UPDATE device_notification_state SET told_on = pending_on, pending_on = NULL, pending_poll = NULL
                     WHERE session_hash = ?1 AND pending_poll = ?2",
                    params![session, cur.poll],
                )?;
            }
            let poll: i64 = tx.query_row(
                "UPDATE sessions SET poll_seq = poll_seq + 1 WHERE token_hash = ?1 RETURNING poll_seq",
                [&session],
                |r| r.get(0),
            )?;
            let (want_due, want_done): (bool, bool) = tx.query_row(
                "SELECT notify_due, notify_done FROM users WHERE id = ?1",
                [uid],
                |r| Ok((r.get(0)?, r.get(1)?)),
            )?;
            let (done, seq, more) = done_since(&tx, uid, since.map(|c| c.seq), want_done, &settled)?;
            let due = if want_due {
                due_now(&tx, uid, &session, today, poll)?
            } else {
                Vec::new()
            };
            tx.commit()?;
            Ok(json!({"cursor": encode(Cursor { seq, poll }), "done": done, "due": due, "more": more}))
        })
        .await?;
    Ok(Json(out))
}

/// Events after `since` that are past the undo window; the cursor never passes a younger event, so it is reported later.
fn done_since(
    tx: &rusqlite::Transaction,
    uid: i64,
    since: Option<i64>,
    want: bool,
    settled: &str,
) -> rusqlite::Result<(Vec<Value>, i64, bool)> {
    let issued: i64 = tx
        .query_row(
            "SELECT seq FROM sqlite_sequence WHERE name = 'done_events'",
            [],
            |r| r.get(0),
        )
        .optional()?
        .unwrap_or(0);
    let Some(since) = since else {
        return Ok((Vec::new(), issued, false));
    };
    let young: Option<i64> = tx.query_row(
        "SELECT MIN(e.seq) FROM done_events e JOIN completions co ON co.id = e.completion_id
         WHERE e.seq > ?1 AND co.created_at > ?2",
        params![since, settled],
        |r| r.get(0),
    )?;
    let end = young.map_or(issued.max(since), |y| y - 1);
    if !want {
        return Ok((Vec::new(), end.max(since), false));
    }
    let mut s = tx.prepare(
        "SELECT e.seq, h.id, h.name, ch.id, ch.name, u.display_name, co.done_on
         FROM done_events e JOIN completions co ON co.id = e.completion_id
         JOIN chores ch ON ch.id = co.chore_id
         JOIN households h ON h.id = ch.household_id
         JOIN memberships m ON m.household_id = h.id AND m.user_id = ?1
         JOIN users u ON u.id = co.user_id
         WHERE e.seq > ?2 AND e.seq <= ?3 AND co.deleted_at IS NULL AND co.user_id != ?1
         ORDER BY e.seq LIMIT ?4",
    )?;
    let rows = s
        .query_map(params![uid, since, end, MAX_DONE as i64 + 1], |r| {
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
        _ => end.max(since),
    };
    Ok((rows.into_iter().map(|(_, v)| v).collect(), cursor, more))
}

/// Chores due now whose period this device has not acknowledged; they are stored as offered by `poll` until the next cursor acknowledges them.
fn due_now(
    tx: &rusqlite::Transaction,
    uid: i64,
    session: &str,
    today: NaiveDate,
    poll: i64,
) -> rusqlite::Result<Vec<Value>> {
    let mut s = tx.prepare(
        "SELECT h.id, h.name, ch.id, ch.name, ch.interval,
           (SELECT MAX(co.done_on) FROM completions co WHERE co.chore_id = ch.id AND co.deleted_at IS NULL),
           ns.told_on
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
    for (hid, hname, chore, name, interval, last, told) in rows {
        let Some(period) =
            super::newly_due(today, interval.as_deref(), last.as_deref(), told.as_deref())
        else {
            continue;
        };
        tx.execute(
            "INSERT INTO device_notification_state (session_hash, chore_id, pending_on, pending_poll) VALUES (?1, ?2, ?3, ?4)
             ON CONFLICT (session_hash, chore_id) DO UPDATE SET pending_on = excluded.pending_on, pending_poll = excluded.pending_poll",
            params![session, chore, period, poll],
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
        let c = Cursor {
            seq: 123_456,
            poll: 7,
        };
        assert_eq!(decode(&encode(c)), Some(c));
        assert_eq!(decode(&encode(Cursor { seq: -1, poll: 0 })), None);
        assert_eq!(decode("abc"), None);
        assert_eq!(decode("!!"), None);
        assert_eq!(decode(&URL_SAFE_NO_PAD.encode(5i64.to_be_bytes())), None);
    }
}
