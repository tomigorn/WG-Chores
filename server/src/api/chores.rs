use crate::api::households::{is_member, not_found};
use crate::app::AppState;
use crate::auth::session::ts;
use crate::auth::{ApiJson, CurrentUser};
use crate::domain::{Interval, days_overdue, due_level, interval_days, usual_interval};
use crate::error::ApiError;
use axum::{
    Json,
    extract::{Path, Query, State},
    http::StatusCode,
};
use chrono::NaiveDate;
use rusqlite::{Connection, OptionalExtension, params};
use serde::{Deserialize, Deserializer};
use serde_json::{Value, json};
use std::collections::HashMap;

pub(crate) fn some<'de, D, T>(d: D) -> Result<Option<Option<T>>, D::Error>
where
    D: Deserializer<'de>,
    T: Deserialize<'de>,
{
    Option::<T>::deserialize(d).map(Some)
}

pub(crate) fn today(st: &AppState) -> NaiveDate {
    st.clock.now().with_timezone(&st.cfg.tz).date_naive()
}

pub(crate) fn chore_household(c: &Connection, chore: i64) -> rusqlite::Result<Option<i64>> {
    c.query_row(
        "SELECT household_id FROM chores WHERE id = ?1",
        [chore],
        |r| r.get(0),
    )
    .optional()
}

/// Household of the chore if the user is a member of it.
pub(crate) fn member_chore(c: &Connection, chore: i64, uid: i64) -> rusqlite::Result<Option<i64>> {
    match chore_household(c, chore)? {
        Some(h) if is_member(c, h, uid)? => Ok(Some(h)),
        _ => Ok(None),
    }
}

fn check_chore_name(raw: &str) -> Result<String, ApiError> {
    let n = raw.trim();
    if n.is_empty() || n.chars().count() > 80 {
        return Err(ApiError::bad_request(
            "invalid_name",
            "The name must be 1 to 80 characters long.",
        ));
    }
    Ok(n.to_string())
}

fn check_category(raw: &str) -> Result<String, ApiError> {
    let n = raw.trim();
    if n.chars().count() > 40 {
        return Err(ApiError::bad_request(
            "invalid_category",
            "The category must be 1 to 40 characters long.",
        ));
    }
    Ok(n.to_string())
}

fn check_interval(raw: &Option<String>) -> Result<Option<&'static str>, ApiError> {
    match raw {
        None => Ok(None),
        Some(s) => Interval::from_code(s)
            .map(|i| Some(i.code()))
            .ok_or_else(|| {
                ApiError::bad_request("invalid_interval", "This interval is not allowed.")
            }),
    }
}

fn category_id(c: &Connection, hid: i64, name: &str) -> rusqlite::Result<i64> {
    let found = c
        .query_row(
            "SELECT id FROM categories WHERE household_id = ?1 AND ulower(name) = ulower(?2)",
            params![hid, name],
            |r| r.get(0),
        )
        .optional()?;
    match found {
        Some(id) => Ok(id),
        None => {
            c.execute(
                "INSERT INTO categories (household_id, name) VALUES (?1, ?2)",
                params![hid, name],
            )?;
            Ok(c.last_insert_rowid())
        }
    }
}

fn drop_empty_categories(c: &Connection, hid: i64) -> rusqlite::Result<()> {
    c.execute(
        "DELETE FROM categories WHERE household_id = ?1
         AND id NOT IN (SELECT category_id FROM chores WHERE category_id IS NOT NULL)",
        [hid],
    )?;
    Ok(())
}

pub(crate) fn by_json(r: &rusqlite::Row, o: usize) -> rusqlite::Result<Value> {
    Ok(json!({
        "id": r.get::<_, i64>(o)?,
        "display_name": r.get::<_, String>(o + 1)?,
        "former": r.get::<_, bool>(o + 2)?,
    }))
}

/// SQL columns (id, display_name, former) of the user `u` as seen from household `h`.
pub(crate) const BY_COLS: &str = "u.id, u.display_name,
    (u.former = 1 OR NOT EXISTS (SELECT 1 FROM memberships m WHERE m.household_id = h.id AND m.user_id = u.id))";

pub(crate) fn chore_views(
    c: &Connection,
    today: NaiveDate,
    hid: i64,
    only: Option<i64>,
    archived: bool,
) -> rusqlite::Result<Vec<Value>> {
    struct Row {
        id: i64,
        name: String,
        category: Option<String>,
        interval: Option<String>,
        archived: bool,
    }
    let mut s = c.prepare(
        "SELECT ch.id, ch.name, cat.name, ch.interval, ch.archived_at IS NOT NULL
         FROM chores ch LEFT JOIN categories cat ON cat.id = ch.category_id
         WHERE ch.household_id = ?1 AND (?2 IS NULL OR ch.id = ?2)
           AND (?2 IS NOT NULL OR (ch.archived_at IS NOT NULL) = ?3)
         ORDER BY cat.id IS NULL, ulower(cat.name), ulower(ch.name), ch.id",
    )?;
    let chores = s
        .query_map(params![hid, only, archived], |r| {
            Ok(Row {
                id: r.get(0)?,
                name: r.get(1)?,
                category: r.get(2)?,
                interval: r.get(3)?,
                archived: r.get(4)?,
            })
        })?
        .collect::<rusqlite::Result<Vec<_>>>()?;
    let mut s = c.prepare(&format!(
        "SELECT chore_id, done_on, {BY_COLS} FROM (
           SELECT co.chore_id, co.done_on, co.user_id,
             ROW_NUMBER() OVER (PARTITION BY co.chore_id ORDER BY co.done_on DESC, co.created_at DESC, co.id DESC) AS rn
           FROM completions co JOIN chores x ON x.id = co.chore_id
           WHERE x.household_id = ?1 AND co.deleted_at IS NULL AND (?2 IS NULL OR x.id = ?2)
         ) q JOIN users u ON u.id = q.user_id JOIN households h ON h.id = ?1
         WHERE rn <= 10 ORDER BY chore_id, rn"
    ))?;
    let mut recent: HashMap<i64, Vec<(NaiveDate, Value)>> = HashMap::new();
    let mut rows = s.query(params![hid, only])?;
    while let Some(r) = rows.next()? {
        let done: String = r.get(1)?;
        let Ok(d) = done.parse::<NaiveDate>() else {
            continue;
        };
        recent
            .entry(r.get(0)?)
            .or_default()
            .push((d, by_json(r, 2)?));
    }
    Ok(chores
        .into_iter()
        .map(|ch| {
            let rec = recent.get(&ch.id).map(Vec::as_slice).unwrap_or(&[]);
            let iv = ch.interval.as_deref().and_then(Interval::from_code);
            let days = rec.first().map(|(d, _)| (today - *d).num_days());
            let dates: Vec<NaiveDate> = rec.iter().map(|(d, _)| *d).collect();
            let ivd = interval_days(iv);
            json!({
                "id": ch.id,
                "household_id": hid,
                "name": ch.name,
                "category": ch.category,
                "interval": ch.interval,
                "archived": ch.archived,
                "last": rec.first().map(|(d, by)| json!({"done_on": d.to_string(), "by": by})),
                "days_since": days,
                "due_level": due_level(days, ivd),
                "days_overdue": days.map_or(0, |d| days_overdue(d, ivd)),
                "usual_interval": usual_interval(&dates),
            })
        })
        .collect())
}

pub async fn list(
    State(st): State<AppState>,
    u: CurrentUser,
    Path(hid): Path<i64>,
    Query(q): Query<HashMap<String, String>>,
) -> Result<Json<Value>, ApiError> {
    let archived = match q.get("archived").map(String::as_str) {
        None | Some("false") => false,
        Some("true") => true,
        _ => {
            return Err(ApiError::bad_request(
                "invalid_query",
                "The request is not valid.",
            ));
        }
    };
    let (uid, today) = (u.id, today(&st));
    st.db
        .run(move |c| {
            if !is_member(c, hid, uid)? {
                return Ok(None);
            }
            chore_views(c, today, hid, None, archived).map(Some)
        })
        .await?
        .map(|v| Json(Value::Array(v)))
        .ok_or_else(not_found)
}

#[derive(Deserialize)]
#[serde(deny_unknown_fields)]
pub struct Create {
    name: String,
    category: Option<String>,
    interval: Option<String>,
}

pub async fn create(
    State(st): State<AppState>,
    u: CurrentUser,
    Path(hid): Path<i64>,
    ApiJson(b): ApiJson<Create>,
) -> Result<(StatusCode, Json<Value>), ApiError> {
    let name = check_chore_name(&b.name)?;
    let category = b.category.as_deref().map(check_category).transpose()?;
    let interval = check_interval(&b.interval)?;
    let (uid, now, today) = (u.id, ts(st.clock.now()), today(&st));
    let v = st
        .db
        .run(move |c| {
            let tx = c.transaction()?;
            if !is_member(&tx, hid, uid)? {
                return Ok(None);
            }
            let cat = match category.as_deref() {
                Some(n) if !n.is_empty() => Some(category_id(&tx, hid, n)?),
                _ => None,
            };
            tx.execute(
                "INSERT INTO chores (household_id, category_id, name, interval, created_at) VALUES (?1, ?2, ?3, ?4, ?5)",
                params![hid, cat, name, interval, now],
            )?;
            let id = tx.last_insert_rowid();
            let v = chore_views(&tx, today, hid, Some(id), false)?.remove(0);
            tx.commit()?;
            Ok(Some(v))
        })
        .await?
        .ok_or_else(not_found)?;
    Ok((StatusCode::CREATED, Json(v)))
}

#[derive(Deserialize)]
#[serde(deny_unknown_fields)]
pub struct Patch {
    name: Option<String>,
    #[serde(default, deserialize_with = "some")]
    category: Option<Option<String>>,
    #[serde(default, deserialize_with = "some")]
    interval: Option<Option<String>>,
}

pub async fn patch(
    State(st): State<AppState>,
    u: CurrentUser,
    Path(id): Path<i64>,
    ApiJson(b): ApiJson<Patch>,
) -> Result<Json<Value>, ApiError> {
    let name = b.name.as_deref().map(check_chore_name).transpose()?;
    let category = b
        .category
        .map(|o| o.as_deref().map(check_category).transpose())
        .transpose()?;
    let interval = b.interval.map(|o| check_interval(&o)).transpose()?;
    let (uid, today) = (u.id, today(&st));
    st.db
        .run(move |c| {
            let tx = c.transaction()?;
            let Some(hid) = member_chore(&tx, id, uid)? else {
                return Ok(None);
            };
            if let Some(n) = name {
                tx.execute("UPDATE chores SET name = ?2 WHERE id = ?1", params![id, n])?;
            }
            if let Some(iv) = interval {
                tx.execute(
                    "UPDATE chores SET interval = ?2 WHERE id = ?1",
                    params![id, iv],
                )?;
            }
            if let Some(cat) = category {
                let cid = match cat.as_deref() {
                    Some(n) if !n.is_empty() => Some(category_id(&tx, hid, n)?),
                    _ => None,
                };
                tx.execute(
                    "UPDATE chores SET category_id = ?2 WHERE id = ?1",
                    params![id, cid],
                )?;
                drop_empty_categories(&tx, hid)?;
            }
            let v = chore_views(&tx, today, hid, Some(id), false)?.remove(0);
            tx.commit()?;
            Ok(Some(v))
        })
        .await?
        .map(Json)
        .ok_or_else(not_found)
}

pub async fn get(
    State(st): State<AppState>,
    u: CurrentUser,
    Path(id): Path<i64>,
) -> Result<Json<Value>, ApiError> {
    let (uid, today) = (u.id, today(&st));
    st.db
        .run(move |c| {
            let Some(hid) = member_chore(c, id, uid)? else {
                return Ok(None);
            };
            Ok(chore_views(c, today, hid, Some(id), false)?.pop())
        })
        .await?
        .map(Json)
        .ok_or_else(not_found)
}

async fn set_archived(
    st: AppState,
    uid: i64,
    id: i64,
    archive: bool,
) -> Result<Json<Value>, ApiError> {
    let (now, today) = (ts(st.clock.now()), today(&st));
    st.db
        .run(move |c| {
            let tx = c.transaction()?;
            let Some(hid) = member_chore(&tx, id, uid)? else {
                return Ok(None);
            };
            if archive {
                tx.execute(
                    "UPDATE chores SET archived_at = COALESCE(archived_at, ?2) WHERE id = ?1",
                    params![id, now],
                )?;
            } else {
                tx.execute("UPDATE chores SET archived_at = NULL WHERE id = ?1", [id])?;
            }
            let v = chore_views(&tx, today, hid, Some(id), false)?.remove(0);
            tx.commit()?;
            Ok(Some(v))
        })
        .await?
        .map(Json)
        .ok_or_else(not_found)
}

pub async fn archive(
    State(st): State<AppState>,
    u: CurrentUser,
    Path(id): Path<i64>,
) -> Result<Json<Value>, ApiError> {
    set_archived(st, u.id, id, true).await
}

pub async fn restore(
    State(st): State<AppState>,
    u: CurrentUser,
    Path(id): Path<i64>,
) -> Result<Json<Value>, ApiError> {
    set_archived(st, u.id, id, false).await
}

fn category_rows(c: &Connection, hid: i64) -> rusqlite::Result<Vec<Value>> {
    let mut s = c.prepare(
        "SELECT cat.name, (SELECT COUNT(*) FROM chores ch WHERE ch.category_id = cat.id AND ch.archived_at IS NULL)
         FROM categories cat WHERE cat.household_id = ?1 ORDER BY ulower(cat.name), cat.id",
    )?;
    s.query_map([hid], |r| {
        Ok(json!({"name": r.get::<_, String>(0)?, "chores": r.get::<_, i64>(1)?}))
    })?
    .collect()
}

pub async fn categories(
    State(st): State<AppState>,
    u: CurrentUser,
    Path(hid): Path<i64>,
) -> Result<Json<Value>, ApiError> {
    let uid = u.id;
    st.db
        .run(move |c| {
            if !is_member(c, hid, uid)? {
                return Ok(None);
            }
            category_rows(c, hid).map(Some)
        })
        .await?
        .map(|v| Json(Value::Array(v)))
        .ok_or_else(not_found)
}

#[derive(Deserialize)]
#[serde(deny_unknown_fields)]
pub struct Rename {
    from: String,
    to: String,
}

pub async fn rename_category(
    State(st): State<AppState>,
    u: CurrentUser,
    Path(hid): Path<i64>,
    ApiJson(b): ApiJson<Rename>,
) -> Result<Json<Value>, ApiError> {
    let to = check_category(&b.to)?;
    if to.is_empty() {
        return Err(ApiError::bad_request(
            "invalid_category",
            "The category must be 1 to 40 characters long.",
        ));
    }
    let (uid, from) = (u.id, b.from.trim().to_string());
    st.db
        .run(move |c| {
            let tx = c.transaction()?;
            if !is_member(&tx, hid, uid)? {
                return Ok(Err(not_found()));
            }
            let src: Option<i64> = tx
                .query_row(
                    "SELECT id FROM categories WHERE household_id = ?1 AND ulower(name) = ulower(?2)",
                    params![hid, from],
                    |r| r.get(0),
                )
                .optional()?;
            let Some(src) = src else {
                return Ok(Err(ApiError::not_found("not_found", "No such category.")));
            };
            let dst: Option<i64> = tx
                .query_row(
                    "SELECT id FROM categories WHERE household_id = ?1 AND ulower(name) = ulower(?2) AND id != ?3",
                    params![hid, to, src],
                    |r| r.get(0),
                )
                .optional()?;
            match dst {
                Some(dst) => {
                    tx.execute(
                        "UPDATE chores SET category_id = ?2 WHERE category_id = ?1",
                        [src, dst],
                    )?;
                    tx.execute("DELETE FROM categories WHERE id = ?1", [src])?;
                }
                None => {
                    tx.execute(
                        "UPDATE categories SET name = ?2 WHERE id = ?1",
                        params![src, to],
                    )?;
                }
            }
            let rows = category_rows(&tx, hid)?;
            tx.commit()?;
            Ok(Ok(rows))
        })
        .await?
        .map(|v| Json(Value::Array(v)))
}
