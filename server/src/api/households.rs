use crate::app::AppState;
use crate::auth::session::ts;
use crate::auth::{ApiJson, CurrentUser};
use crate::error::ApiError;
use axum::{
    Json,
    body::Bytes,
    extract::{Path, State},
    http::StatusCode,
};
use rusqlite::{Connection, OptionalExtension, params};
use serde::Deserialize;
use serde_json::{Value, json};

pub fn not_found() -> ApiError {
    ApiError::not_found("not_found", "Not found.")
}

fn household_json(c: &Connection, id: i64) -> rusqlite::Result<Value> {
    c.query_row(
        "SELECT id, name, (SELECT COUNT(*) FROM memberships WHERE household_id = h.id) FROM households h WHERE id = ?1",
        [id],
        |r| {
            Ok(json!({
                "id": r.get::<_, i64>(0)?,
                "name": r.get::<_, String>(1)?,
                "member_count": r.get::<_, i64>(2)?,
            }))
        },
    )
}

pub(crate) fn is_member(c: &Connection, hid: i64, uid: i64) -> rusqlite::Result<bool> {
    c.query_row(
        "SELECT 1 FROM memberships WHERE household_id = ?1 AND user_id = ?2",
        [hid, uid],
        |_| Ok(()),
    )
    .optional()
    .map(|o| o.is_some())
}

fn check_name(raw: &str) -> Result<String, ApiError> {
    let n = crate::model::clean_text(raw);
    if n.is_empty() || n.chars().count() > 60 {
        return Err(ApiError::bad_request(
            "invalid_name",
            "The name must be 1 to 60 characters long.",
        ));
    }
    Ok(n)
}

pub async fn list(State(st): State<AppState>, u: CurrentUser) -> Result<Json<Value>, ApiError> {
    let uid = u.id;
    let rows = st
        .db
        .run(move |c| {
            let mut s = c.prepare(
                "SELECT h.id, h.name, (SELECT COUNT(*) FROM memberships WHERE household_id = h.id)
                 FROM households h JOIN memberships m ON m.household_id = h.id
                 WHERE m.user_id = ?1 ORDER BY ulower(h.name), h.id",
            )?;
            s.query_map([uid], |r| {
                Ok(json!({
                    "id": r.get::<_, i64>(0)?,
                    "name": r.get::<_, String>(1)?,
                    "member_count": r.get::<_, i64>(2)?,
                }))
            })?
            .collect::<rusqlite::Result<Vec<_>>>()
        })
        .await?;
    Ok(Json(Value::Array(rows)))
}

#[derive(Deserialize)]
#[serde(deny_unknown_fields)]
pub struct NameBody {
    name: String,
}

pub async fn create(
    State(st): State<AppState>,
    u: CurrentUser,
    ApiJson(b): ApiJson<NameBody>,
) -> Result<(StatusCode, Json<Value>), ApiError> {
    let name = check_name(&b.name)?;
    let (uid, now) = (u.id, ts(st.clock.now()));
    let v = st
        .db
        .run(move |c| {
            let tx = c.transaction()?;
            tx.execute(
                "INSERT INTO households (name, created_at) VALUES (?1, ?2)",
                params![name, now],
            )?;
            let hid = tx.last_insert_rowid();
            tx.execute(
                "INSERT INTO memberships (household_id, user_id, joined_at) VALUES (?1, ?2, ?3)",
                params![hid, uid, now],
            )?;
            tx.execute(
                "UPDATE users SET last_household_id = ?2 WHERE id = ?1",
                params![uid, hid],
            )?;
            let v = household_json(&tx, hid)?;
            tx.commit()?;
            Ok(v)
        })
        .await?;
    Ok((StatusCode::CREATED, Json(v)))
}

pub async fn get(
    State(st): State<AppState>,
    u: CurrentUser,
    Path(hid): Path<i64>,
) -> Result<Json<Value>, ApiError> {
    let uid = u.id;
    st.db
        .run(move |c| {
            Ok(if is_member(c, hid, uid)? {
                Some(household_json(c, hid)?)
            } else {
                None
            })
        })
        .await?
        .map(Json)
        .ok_or_else(not_found)
}

pub async fn patch(
    State(st): State<AppState>,
    u: CurrentUser,
    Path(hid): Path<i64>,
    ApiJson(b): ApiJson<NameBody>,
) -> Result<Json<Value>, ApiError> {
    let uid = u.id;
    let name = check_name(&b.name)?;
    st.db
        .run(move |c| {
            if !is_member(c, hid, uid)? {
                return Ok(None);
            }
            c.execute(
                "UPDATE households SET name = ?2 WHERE id = ?1",
                params![hid, name],
            )?;
            Ok(Some(household_json(c, hid)?))
        })
        .await?
        .map(Json)
        .ok_or_else(not_found)
}

pub async fn members(
    State(st): State<AppState>,
    u: CurrentUser,
    Path(hid): Path<i64>,
) -> Result<Json<Value>, ApiError> {
    let uid = u.id;
    let rows = st
        .db
        .run(move |c| {
            if !is_member(c, hid, uid)? {
                return Ok(None);
            }
            member_rows(c, hid).map(Some)
        })
        .await?
        .ok_or_else(not_found)?;
    Ok(Json(Value::Array(rows)))
}

fn member_rows(c: &Connection, hid: i64) -> rusqlite::Result<Vec<Value>> {
    let mut s = c.prepare(
        "SELECT u.id, u.display_name, u.username, u.picture_url, m.joined_at
         FROM memberships m JOIN users u ON u.id = m.user_id
         WHERE m.household_id = ?1 AND u.former = 0
         ORDER BY ulower(u.display_name), u.id",
    )?;
    s.query_map([hid], member_json)?
        .collect::<rusqlite::Result<Vec<_>>>()
}

fn member_json(r: &rusqlite::Row) -> rusqlite::Result<Value> {
    Ok(json!({
        "id": r.get::<_, i64>(0)?,
        "display_name": r.get::<_, String>(1)?,
        "username": r.get::<_, Option<String>>(2)?,
        "picture_url": r.get::<_, Option<String>>(3)?,
        "joined_at": r.get::<_, String>(4)?,
    }))
}

const MEMBER_ADDS_PER_HOUR: i64 = 20;

#[derive(Deserialize)]
#[serde(deny_unknown_fields)]
pub struct AddMember {
    username: Option<String>,
    user_id: Option<i64>,
}

pub async fn add_member(
    State(st): State<AppState>,
    u: CurrentUser,
    Path(hid): Path<i64>,
    ApiJson(b): ApiJson<AddMember>,
) -> Result<(StatusCode, Json<Value>), ApiError> {
    let (uid, now) = (u.id, ts(st.clock.now()));
    let by_id = match (&b.username, b.user_id) {
        (Some(_), None) => None,
        (None, Some(id)) => Some(st.cfg.member_directory.then_some(id)),
        _ => {
            return Err(ApiError::bad_request(
                "invalid_body",
                "The request body is not valid.",
            ));
        }
    };
    crate::auth::local::reserve_counter(
        &st,
        format!("member-add:user:{uid}"),
        MEMBER_ADDS_PER_HOUR,
    )
    .await?;
    let username = b.username.unwrap_or_default().trim().to_string();
    let v = st
        .db
        .run(move |c| {
            let tx = c.transaction()?;
            if !is_member(&tx, hid, uid)? {
                return Ok(Err(not_found()));
            }
            let target: Option<i64> = match by_id {
                Some(None) => None,
                Some(Some(id)) => tx
                    .query_row(
                        "SELECT id FROM users WHERE id = ?1 AND former = 0",
                        [id],
                        |r| r.get(0),
                    )
                    .optional()?,
                None => tx
                    .query_row(
                        "SELECT id FROM users WHERE username = ?1 AND former = 0",
                        [&username],
                        |r| r.get(0),
                    )
                    .optional()?,
            };
            let cannot_add =
                || ApiError::not_found("cannot_add", "No user with that name can be added.");
            let Some(target) = target else {
                return Ok(Err(cannot_add()));
            };
            if is_member(&tx, hid, target)? {
                return Ok(Err(cannot_add()));
            }
            tx.execute(
                "INSERT INTO memberships (household_id, user_id, joined_at) VALUES (?1, ?2, ?3)",
                params![hid, target, now],
            )?;
            let v = tx.query_row(
                "SELECT u.id, u.display_name, u.username, u.picture_url, m.joined_at
                 FROM memberships m JOIN users u ON u.id = m.user_id
                 WHERE m.household_id = ?1 AND u.id = ?2",
                [hid, target],
                member_json,
            )?;
            tx.commit()?;
            Ok(Ok(v))
        })
        .await??;
    Ok((StatusCode::CREATED, Json(v)))
}

#[derive(Deserialize, Default)]
#[serde(deny_unknown_fields)]
struct Confirm {
    #[serde(default)]
    confirm_delete: bool,
}

fn parse_confirm(body: &Bytes) -> Result<bool, ApiError> {
    if body.iter().all(u8::is_ascii_whitespace) {
        return Ok(false);
    }
    serde_json::from_slice::<Confirm>(body)
        .map(|c| c.confirm_delete)
        .map_err(|_| ApiError::bad_request("invalid_body", "The request body is not valid."))
}

async fn remove(
    st: &AppState,
    caller: i64,
    hid: i64,
    target: i64,
    confirm: bool,
) -> Result<StatusCode, ApiError> {
    let now = ts(st.clock.now());
    st.db
        .run(move |c| {
            let tx = c.transaction()?;
            if !is_member(&tx, hid, caller)? || !is_member(&tx, hid, target)? {
                return Ok(Err(not_found()));
            }
            let count: i64 = tx.query_row(
                "SELECT COUNT(*) FROM memberships WHERE household_id = ?1",
                [hid],
                |r| r.get(0),
            )?;
            if count == 1 {
                if !confirm {
                    return Ok(Err(ApiError::conflict(
                        "last_member_confirm",
                        "You are the last member: leaving deletes the household. Confirm to continue.",
                    )));
                }
                tx.execute("DELETE FROM households WHERE id = ?1", [hid])?;
            } else {
                tx.execute(
                    "DELETE FROM memberships WHERE household_id = ?1 AND user_id = ?2",
                    [hid, target],
                )?;
                tx.execute(
                    "UPDATE invites SET cancelled_at = ?3 WHERE household_id = ?1 AND created_by = ?2 AND used_at IS NULL AND cancelled_at IS NULL",
                    params![hid, target, now],
                )?;
                tx.execute(
                    "UPDATE users SET last_household_id = NULL WHERE id = ?1 AND last_household_id = ?2",
                    [target, hid],
                )?;
            }
            tx.commit()?;
            Ok(Ok(StatusCode::NO_CONTENT))
        })
        .await?
}

pub async fn leave(
    State(st): State<AppState>,
    u: CurrentUser,
    Path(hid): Path<i64>,
    body: Bytes,
) -> Result<StatusCode, ApiError> {
    remove(&st, u.id, hid, u.id, parse_confirm(&body)?).await
}

pub async fn remove_member(
    State(st): State<AppState>,
    u: CurrentUser,
    Path((hid, target)): Path<(i64, i64)>,
    body: Bytes,
) -> Result<StatusCode, ApiError> {
    remove(&st, u.id, hid, target, parse_confirm(&body)?).await
}
