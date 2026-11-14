use crate::app::AppState;
use crate::auth::CurrentUser;
use crate::auth::session::{hash_token, ts};
use crate::error::ApiError;
use axum::{
    Json,
    extract::{Path, State},
    http::StatusCode,
};
use base64::{Engine, engine::general_purpose::URL_SAFE_NO_PAD};
use chrono::Duration;
use rusqlite::{Connection, OptionalExtension, Transaction, params};
use serde_json::{Value, json};

const VALID_FOR: Duration = Duration::days(7);
const MAX_OPEN: i64 = 20;
const TOKEN_LEN: usize = 43;

fn not_found() -> ApiError {
    ApiError::not_found("invite_not_found", "This invite does not exist.")
}

fn gone(code: &'static str, message: &'static str) -> ApiError {
    ApiError::new(StatusCode::GONE, code, message)
}

/// Hash of a well-formed token; anything else never reaches the database.
pub fn token_hash(raw: &str) -> Result<String, ApiError> {
    let ok = raw.len() == TOKEN_LEN
        && raw
            .bytes()
            .all(|b| b.is_ascii_alphanumeric() || b == b'-' || b == b'_');
    if ok {
        Ok(hash_token(raw))
    } else {
        Err(not_found())
    }
}

fn is_member(c: &Connection, hid: i64, uid: i64) -> rusqlite::Result<bool> {
    c.query_row(
        "SELECT 1 FROM memberships WHERE household_id = ?1 AND user_id = ?2",
        [hid, uid],
        |_| Ok(()),
    )
    .optional()
    .map(|o| o.is_some())
}

#[derive(Clone, Copy, PartialEq)]
enum Reason {
    Used,
    Cancelled,
    Expired,
}

impl Reason {
    fn name(self) -> &'static str {
        match self {
            Reason::Used => "used",
            Reason::Cancelled => "cancelled",
            Reason::Expired => "expired",
        }
    }

    fn error(self) -> ApiError {
        match self {
            Reason::Used => gone("invite_used", "This invite was already used."),
            Reason::Cancelled => gone("invite_cancelled", "This invite was cancelled."),
            Reason::Expired => gone("invite_expired", "This invite has expired."),
        }
    }
}

struct Row {
    id: i64,
    household_id: i64,
    household_name: String,
    invited_by: String,
    expires_at: String,
    reason: Option<Reason>,
}

fn load(c: &Connection, hash: &str, now: &str) -> rusqlite::Result<Option<Row>> {
    c.query_row(
        "SELECT i.id, i.household_id, h.name, u.display_name, i.expires_at, i.used_at IS NOT NULL, i.cancelled_at IS NOT NULL, i.expires_at <= ?2
         FROM invites i JOIN households h ON h.id = i.household_id JOIN users u ON u.id = i.created_by
         WHERE i.token_hash = ?1",
        params![hash, now],
        |r| {
            let (used, cancelled, expired): (bool, bool, bool) = (r.get(5)?, r.get(6)?, r.get(7)?);
            let reason = if used {
                Some(Reason::Used)
            } else if cancelled {
                Some(Reason::Cancelled)
            } else if expired {
                Some(Reason::Expired)
            } else {
                None
            };
            Ok(Row {
                id: r.get(0)?,
                household_id: r.get(1)?,
                household_name: r.get(2)?,
                invited_by: r.get(3)?,
                expires_at: r.get(4)?,
                reason,
            })
        },
    )
    .optional()
}

/// Checks and consumes an invite for `uid` inside the caller's transaction; returns (household id, was already a member).
pub fn accept_in_tx(
    tx: &Transaction,
    hash: &str,
    uid: i64,
    now: &str,
) -> rusqlite::Result<Result<(i64, bool), ApiError>> {
    let Some(row) = load(tx, hash, now)? else {
        return Ok(Err(not_found()));
    };
    if is_member(tx, row.household_id, uid)? {
        return Ok(Ok((row.household_id, true)));
    }
    if let Some(reason) = row.reason {
        return Ok(Err(reason.error()));
    }
    tx.execute(
        "INSERT INTO memberships (household_id, user_id, joined_at) VALUES (?1, ?2, ?3)",
        params![row.household_id, uid, now],
    )?;
    tx.execute(
        "UPDATE invites SET used_by = ?2, used_at = ?3 WHERE id = ?1",
        params![row.id, uid, now],
    )?;
    tx.execute(
        "UPDATE users SET last_household_id = ?2 WHERE id = ?1",
        params![uid, row.household_id],
    )?;
    Ok(Ok((row.household_id, false)))
}

/// Read-only validity check, used before expensive work.
pub async fn ensure_open(st: &AppState, hash: String) -> Result<(), ApiError> {
    let now = ts(st.clock.now());
    let row = st.db.run(move |c| load(c, &hash, &now)).await?;
    match row {
        None => Err(not_found()),
        Some(Row {
            reason: Some(r), ..
        }) => Err(r.error()),
        Some(_) => Ok(()),
    }
}

pub async fn preview(
    State(st): State<AppState>,
    Path(token): Path<String>,
) -> Result<Json<Value>, ApiError> {
    let hash = token_hash(&token)?;
    let now = ts(st.clock.now());
    let row = st
        .db
        .run(move |c| load(c, &hash, &now))
        .await?
        .ok_or_else(not_found)?;
    let valid = row.reason.is_none();
    Ok(Json(json!({
        "household_name": row.household_name,
        "invited_by": row.invited_by,
        "expires_at": row.expires_at,
        "valid": valid,
        "reason": row.reason.map(Reason::name),
        "can_register": valid && st.cfg.local_accounts,
    })))
}

pub async fn accept(
    State(st): State<AppState>,
    u: CurrentUser,
    Path(token): Path<String>,
) -> Result<Json<Value>, ApiError> {
    let hash = token_hash(&token)?;
    let (uid, now) = (u.id, ts(st.clock.now()));
    let (hid, already) = st
        .db
        .run(move |c| {
            let tx = c.transaction()?;
            let out = accept_in_tx(&tx, &hash, uid, &now)?;
            if out.is_ok() {
                tx.commit()?;
            }
            Ok(out)
        })
        .await??;
    Ok(Json(if already {
        json!({"household_id": hid, "already_member": true})
    } else {
        json!({"household_id": hid})
    }))
}

fn new_token() -> String {
    let mut bytes = [0u8; 32];
    getrandom::fill(&mut bytes).expect("os randomness");
    URL_SAFE_NO_PAD.encode(bytes)
}

pub async fn create(
    State(st): State<AppState>,
    u: CurrentUser,
    Path(hid): Path<i64>,
) -> Result<(StatusCode, Json<Value>), ApiError> {
    let raw = new_token();
    let hash = hash_token(&raw);
    let now = st.clock.now();
    let (uid, now_s, exp_s) = (u.id, ts(now), ts(now + VALID_FOR));
    let exp = exp_s.clone();
    let id = st
        .db
        .run(move |c| {
            let tx = c.transaction()?;
            if !is_member(&tx, hid, uid)? {
                return Ok(Err(crate::api::households::not_found()));
            }
            let open: i64 = tx.query_row(
                "SELECT count(*) FROM invites WHERE household_id = ?1 AND used_at IS NULL AND cancelled_at IS NULL AND expires_at > ?2",
                params![hid, now_s],
                |r| r.get(0),
            )?;
            if open >= MAX_OPEN {
                return Ok(Err(ApiError::conflict(
                    "too_many_invites",
                    "Too many open invites. Cancel some first.",
                )));
            }
            tx.execute(
                "INSERT INTO invites (token_hash, household_id, created_by, created_at, expires_at) VALUES (?1, ?2, ?3, ?4, ?5)",
                params![hash, hid, uid, now_s, exp],
            )?;
            let id = tx.last_insert_rowid();
            tx.commit()?;
            Ok(Ok(id))
        })
        .await??;
    let base = st.cfg.public_url.as_str().trim_end_matches('/');
    Ok((
        StatusCode::CREATED,
        Json(json!({"id": id, "url": format!("{base}/invite/{raw}"), "expires_at": exp_s})),
    ))
}

pub async fn list(
    State(st): State<AppState>,
    u: CurrentUser,
    Path(hid): Path<i64>,
) -> Result<Json<Value>, ApiError> {
    let (uid, now) = (u.id, ts(st.clock.now()));
    let rows = st
        .db
        .run(move |c| {
            if !is_member(c, hid, uid)? {
                return Ok(None);
            }
            let mut s = c.prepare(
                "SELECT i.id, u.id, u.display_name, i.created_at, i.expires_at
                 FROM invites i JOIN users u ON u.id = i.created_by
                 WHERE i.household_id = ?1 AND i.used_at IS NULL AND i.cancelled_at IS NULL AND i.expires_at > ?2
                 ORDER BY i.created_at, i.id",
            )?;
            let rows = s
                .query_map(params![hid, now], |r| {
                    Ok(json!({
                        "id": r.get::<_, i64>(0)?,
                        "created_by": {"id": r.get::<_, i64>(1)?, "display_name": r.get::<_, String>(2)?},
                        "created_at": r.get::<_, String>(3)?,
                        "expires_at": r.get::<_, String>(4)?,
                    }))
                })?
                .collect::<rusqlite::Result<Vec<_>>>()?;
            Ok(Some(rows))
        })
        .await?
        .ok_or_else(crate::api::households::not_found)?;
    Ok(Json(Value::Array(rows)))
}

pub async fn cancel(
    State(st): State<AppState>,
    u: CurrentUser,
    Path((hid, iid)): Path<(i64, i64)>,
) -> Result<StatusCode, ApiError> {
    let (uid, now) = (u.id, ts(st.clock.now()));
    st.db
        .run(move |c| {
            let tx = c.transaction()?;
            if !is_member(&tx, hid, uid)? {
                return Ok(false);
            }
            let exists = tx
                .query_row(
                    "SELECT 1 FROM invites WHERE id = ?1 AND household_id = ?2",
                    [iid, hid],
                    |_| Ok(()),
                )
                .optional()?
                .is_some();
            if exists {
                tx.execute(
                    "UPDATE invites SET cancelled_at = ?2 WHERE id = ?1 AND cancelled_at IS NULL AND used_at IS NULL",
                    params![iid, now],
                )?;
                tx.commit()?;
            }
            Ok(exists)
        })
        .await?
        .then_some(StatusCode::NO_CONTENT)
        .ok_or_else(crate::api::households::not_found)
}
