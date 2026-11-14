use crate::api::chores::{BY_COLS, by_json, member_chore, some, today};
use crate::api::households::not_found;
use crate::app::AppState;
use crate::auth::session::ts;
use crate::auth::{ApiJson, CurrentUser};
use crate::error::ApiError;
use axum::{
    Json,
    extract::{Path, Query, State},
    http::StatusCode,
};
use chrono::{Months, NaiveDate};
use rusqlite::{Connection, OptionalExtension, params};
use serde::Deserialize;
use serde_json::{Value, json};
use std::collections::HashMap;

fn parse_uuid(s: &str) -> Option<String> {
    let b = s.as_bytes();
    let ok = b.len() == 36
        && b.iter().enumerate().all(|(i, c)| match i {
            8 | 13 | 18 | 23 => *c == b'-',
            _ => c.is_ascii_hexdigit(),
        });
    ok.then(|| s.to_ascii_lowercase())
}

fn check_date(raw: &str, today: NaiveDate) -> Result<String, ApiError> {
    let bad = || ApiError::bad_request("invalid_date", "The date is not valid.");
    if raw.len() != 10 {
        return Err(bad());
    }
    let d: NaiveDate = raw.parse().map_err(|_| bad())?;
    if d > today {
        return Err(ApiError::bad_request(
            "future_date",
            "The date cannot be in the future.",
        ));
    }
    if today
        .checked_sub_months(Months::new(60))
        .is_some_and(|min| d < min)
    {
        return Err(ApiError::bad_request(
            "date_too_old",
            "The date is too far in the past.",
        ));
    }
    Ok(d.to_string())
}

fn check_note(note: Option<String>) -> Result<Option<String>, ApiError> {
    let Some(n) = note else { return Ok(None) };
    let n = n.trim();
    if n.chars().count() > 500 {
        return Err(ApiError::bad_request(
            "invalid_note",
            "The note can have at most 500 characters.",
        ));
    }
    Ok((!n.is_empty()).then(|| n.to_string()))
}

const SELECT: &str = "SELECT co.id, co.done_on, co.note, co.created_at, {BY}, co.chore_id
    FROM completions co JOIN chores ch ON ch.id = co.chore_id JOIN households h ON h.id = ch.household_id
    JOIN users u ON u.id = co.user_id";

fn select(tail: &str) -> String {
    format!("{} {tail}", SELECT.replace("{BY}", BY_COLS))
}

fn completion_json(r: &rusqlite::Row) -> rusqlite::Result<Value> {
    Ok(json!({
        "id": r.get::<_, String>(0)?,
        "done_on": r.get::<_, String>(1)?,
        "note": r.get::<_, Option<String>>(2)?,
        "by": by_json(r, 4)?,
        "created_at": r.get::<_, String>(3)?,
    }))
}

fn load(c: &Connection, id: &str) -> rusqlite::Result<Option<Value>> {
    c.query_row(&select("WHERE co.id = ?1"), [id], completion_json)
        .optional()
}

#[derive(Deserialize)]
#[serde(deny_unknown_fields)]
pub struct Create {
    id: String,
    done_on: Option<String>,
    note: Option<String>,
}

pub async fn create(
    State(st): State<AppState>,
    u: CurrentUser,
    Path(chore): Path<i64>,
    ApiJson(b): ApiJson<Create>,
) -> Result<(StatusCode, Json<Value>), ApiError> {
    let id = parse_uuid(&b.id)
        .ok_or_else(|| ApiError::bad_request("invalid_id", "The id must be a UUID."))?;
    let today = today(&st);
    let (uid, now) = (u.id, ts(st.clock.now()));
    st.db
        .run(move |c| {
            let tx = c.transaction()?;
            if member_chore(&tx, chore, uid)?.is_none() {
                return Ok(Err(not_found()));
            }
            let owner: Option<i64> = tx
                .query_row("SELECT chore_id FROM completions WHERE id = ?1", [&id], |r| r.get(0))
                .optional()?;
            match owner {
                Some(o) if o == chore => {
                    let v = load(&tx, &id)?.expect("completion exists");
                    return Ok(Ok((StatusCode::OK, v)));
                }
                Some(_) => {
                    return Ok(Err(ApiError::conflict(
                        "id_in_use",
                        "This id belongs to another entry.",
                    )));
                }
                None => {}
            }
            let done_on = match &b.done_on {
                Some(d) => match check_date(d, today) {
                    Ok(d) => d,
                    Err(e) => return Ok(Err(e)),
                },
                None => today.to_string(),
            };
            let note = match check_note(b.note.clone()) {
                Ok(n) => n,
                Err(e) => return Ok(Err(e)),
            };
            tx.execute(
                "INSERT INTO completions (id, chore_id, user_id, done_on, note, created_at) VALUES (?1, ?2, ?3, ?4, ?5, ?6)",
                params![id, chore, uid, done_on, note, now],
            )?;
            let v = load(&tx, &id)?.expect("completion exists");
            tx.commit()?;
            Ok(Ok((StatusCode::CREATED, v)))
        })
        .await?
        .map(|(s, v)| (s, Json(v)))
}

pub async fn history(
    State(st): State<AppState>,
    u: CurrentUser,
    Path(chore): Path<i64>,
    Query(q): Query<HashMap<String, String>>,
) -> Result<Json<Value>, ApiError> {
    let bad = || ApiError::bad_request("invalid_query", "The request is not valid.");
    let limit: i64 = match q.get("limit") {
        None => 30,
        Some(s) => s.parse::<i64>().map_err(|_| bad())?.clamp(1, 100),
    };
    let before = match q.get("before") {
        None => None,
        Some(s) => {
            let (d, id) = s.split_once(',').ok_or_else(bad)?;
            let d = d.parse::<NaiveDate>().map_err(|_| bad())?;
            Some((d.to_string(), parse_uuid(id).ok_or_else(bad)?))
        }
    };
    let uid = u.id;
    let (items, next) = st
        .db
        .run(move |c| {
            if member_chore(c, chore, uid)?.is_none() {
                return Ok(Err(not_found()));
            }
            let (bd, bc, bid) = match before {
                None => (None, None, None),
                Some((d, id)) => {
                    let created: Option<String> = c
                        .query_row(
                            "SELECT created_at FROM completions WHERE id = ?1 AND chore_id = ?2",
                            params![id, chore],
                            |r| r.get(0),
                        )
                        .optional()?;
                    let Some(created) = created else {
                        return Ok(Err(ApiError::bad_request(
                            "bad_cursor",
                            "The paging position is not valid.",
                        )));
                    };
                    (Some(d), Some(created), Some(id))
                }
            };
            let sql = select(
                "WHERE co.chore_id = ?1 AND co.deleted_at IS NULL AND (?2 IS NULL OR (co.done_on, co.created_at, co.id) < (?2, ?3, ?4))
                 ORDER BY co.done_on DESC, co.created_at DESC, co.id DESC LIMIT ?5",
            );
            let mut s = c.prepare(&sql)?;
            let mut items = s
                .query_map(params![chore, bd, bc, bid, limit + 1], completion_json)?
                .collect::<rusqlite::Result<Vec<_>>>()?;
            let next = if items.len() as i64 > limit {
                items.truncate(limit as usize);
                items.last().map(|v| {
                    format!(
                        "{},{}",
                        v["done_on"].as_str().unwrap_or(""),
                        v["id"].as_str().unwrap_or("")
                    )
                })
            } else {
                None
            };
            Ok(Ok((items, next)))
        })
        .await??;
    Ok(Json(json!({"items": items, "next": next})))
}

#[derive(Deserialize)]
#[serde(deny_unknown_fields)]
pub struct Patch {
    done_on: Option<String>,
    #[serde(default, deserialize_with = "some")]
    note: Option<Option<String>>,
}

/// Chore id of a live completion the user may touch.
fn editable(c: &Connection, id: &str, uid: i64) -> rusqlite::Result<bool> {
    let chore: Option<i64> = c
        .query_row(
            "SELECT chore_id FROM completions WHERE id = ?1 AND deleted_at IS NULL",
            [id],
            |r| r.get(0),
        )
        .optional()?;
    Ok(match chore {
        Some(ch) => member_chore(c, ch, uid)?.is_some(),
        None => false,
    })
}

pub async fn patch(
    State(st): State<AppState>,
    u: CurrentUser,
    Path(id): Path<String>,
    ApiJson(b): ApiJson<Patch>,
) -> Result<Json<Value>, ApiError> {
    let done_on = b
        .done_on
        .as_deref()
        .map(|d| check_date(d, today(&st)))
        .transpose()?;
    let note = b.note.map(check_note).transpose()?;
    let id = id.to_ascii_lowercase();
    let uid = u.id;
    st.db
        .run(move |c| {
            let tx = c.transaction()?;
            if !editable(&tx, &id, uid)? {
                return Ok(None);
            }
            if let Some(d) = done_on {
                tx.execute(
                    "UPDATE completions SET done_on = ?2 WHERE id = ?1",
                    params![id, d],
                )?;
            }
            if let Some(n) = note {
                tx.execute(
                    "UPDATE completions SET note = ?2 WHERE id = ?1",
                    params![id, n],
                )?;
            }
            let v = load(&tx, &id)?;
            tx.commit()?;
            Ok(v)
        })
        .await?
        .map(Json)
        .ok_or_else(not_found)
}

pub async fn delete(
    State(st): State<AppState>,
    u: CurrentUser,
    Path(id): Path<String>,
) -> Result<StatusCode, ApiError> {
    let (uid, now, id) = (u.id, ts(st.clock.now()), id.to_ascii_lowercase());
    let ok = st
        .db
        .run(move |c| {
            if !editable(c, &id, uid)? {
                return Ok(false);
            }
            c.execute(
                "UPDATE completions SET deleted_at = ?2 WHERE id = ?1",
                params![id, now],
            )?;
            Ok(true)
        })
        .await?;
    if ok {
        Ok(StatusCode::NO_CONTENT)
    } else {
        Err(not_found())
    }
}
