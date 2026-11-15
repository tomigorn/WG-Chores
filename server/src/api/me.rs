use crate::app::AppState;
use crate::auth::local::{self, check_display_name, check_password};
use crate::auth::session::ts;
use crate::auth::{AccountKind, ApiJson, ClientIp, CurrentUser};
use crate::error::ApiError;
use crate::model::user_json;
use axum::{
    Json,
    extract::State,
    http::StatusCode,
    response::{IntoResponse, Response},
};
use serde::Deserialize;
use serde_json::Value;

struct Row {
    username: Option<String>,
    display_name: String,
    picture_url: Option<String>,
    hash: Option<String>,
    last_household_id: Option<i64>,
    notify_due: bool,
    notify_done: bool,
}

async fn load(st: &AppState, id: i64) -> Result<Row, ApiError> {
    Ok(st
        .db
        .run(move |c| {
            c.query_row(
                "SELECT u.username, u.display_name, u.picture_url,
                        (SELECT password_hash FROM identities WHERE user_id = u.id AND kind = 'local'),
                        u.last_household_id, u.notify_due, u.notify_done
                 FROM users u WHERE u.id = ?1",
                [id],
                |r| {
                    Ok(Row {
                        username: r.get(0)?,
                        display_name: r.get(1)?,
                        picture_url: r.get(2)?,
                        hash: r.get(3)?,
                        last_household_id: r.get(4)?,
                        notify_due: r.get(5)?,
                        notify_done: r.get(6)?,
                    })
                },
            )
        })
        .await?)
}

fn render(st: &AppState, u: &CurrentUser, r: &Row) -> Value {
    let account_url = (u.kind == AccountKind::Sso)
        .then(|| st.cfg.oidc.as_ref().and_then(|o| o.account_url.as_ref()))
        .flatten();
    let mut v = user_json(
        u.id,
        r.username.as_deref(),
        &r.display_name,
        u.kind,
        r.picture_url.as_deref(),
        account_url.map(|u| u.as_str()),
    );
    v["last_household_id"] = r.last_household_id.into();
    v["notify_due"] = r.notify_due.into();
    v["notify_done"] = r.notify_done.into();
    v
}

pub async fn get(State(st): State<AppState>, u: CurrentUser) -> Result<Json<Value>, ApiError> {
    let r = load(&st, u.id).await?;
    Ok(Json(render(&st, &u, &r)))
}

#[derive(Deserialize)]
#[serde(deny_unknown_fields)]
pub struct PatchMe {
    display_name: Option<String>,
    #[serde(rename = "current_password")]
    current: Option<String>,
    #[serde(rename = "new_password")]
    new: Option<String>,
    notify_due: Option<bool>,
    notify_done: Option<bool>,
}

pub async fn patch(
    State(st): State<AppState>,
    ip: ClientIp,
    u: CurrentUser,
    ApiJson(b): ApiJson<PatchMe>,
) -> Result<Json<Value>, ApiError> {
    let account_change = b.display_name.is_some() || b.current.is_some() || b.new.is_some();
    if !account_change && b.notify_due.is_none() && b.notify_done.is_none() {
        return Err(ApiError::bad_request(
            "nothing_to_change",
            "Nothing to change.",
        ));
    }
    if account_change && u.kind == AccountKind::Sso {
        return Err(ApiError::forbidden(
            "sso_managed",
            "This account is managed by the single sign-on provider.",
        ));
    }
    if (b.new.is_some() || b.current.is_some()) && !st.cfg.local_accounts {
        return Err(ApiError::forbidden(
            "local_accounts_disabled",
            "Local accounts are disabled on this server.",
        ));
    }
    let display_name = b
        .display_name
        .as_deref()
        .map(check_display_name)
        .transpose()?;
    let row = load(&st, u.id).await?;
    let mut new_hash = None;
    if b.new.is_some() || b.current.is_some() {
        let new = b.new.ok_or_else(|| {
            ApiError::bad_request("new_password_required", "A new password is required.")
        })?;
        let current = b.current.ok_or_else(|| {
            ApiError::bad_request(
                "current_password_required",
                "The current password is required.",
            )
        })?;
        check_password(&new)?;
        let name = row.username.clone().unwrap_or_default();
        let attempt = local::reserve(&st, Some(&name), ip).await?;
        let (attempt, ok) = if current.len() <= 1024 {
            attempt.verify(&st, current, row.hash.clone()).await?
        } else {
            (attempt, false)
        };
        if !ok {
            return Err(ApiError::forbidden(
                "wrong_password",
                "The current password is wrong.",
            ));
        }
        attempt.succeed(&st.db).await?;
        new_hash = Some(st.hasher.hash(new).await?);
    }
    let (id, keep, dn, nh) = (u.id, u.session_hash.clone(), display_name.clone(), new_hash);
    let (due, done) = (b.notify_due, b.notify_done);
    st.db
        .run(move |c| {
            let tx = c.transaction()?;
            if let Some(v) = due {
                tx.execute("UPDATE users SET notify_due = ?2 WHERE id = ?1", rusqlite::params![id, v])?;
            }
            if let Some(v) = done {
                tx.execute("UPDATE users SET notify_done = ?2 WHERE id = ?1", rusqlite::params![id, v])?;
            }
            if let Some(dn) = dn {
                tx.execute("UPDATE users SET display_name = ?2 WHERE id = ?1", rusqlite::params![id, dn])?;
            }
            if let Some(h) = nh {
                tx.execute(
                    "UPDATE identities SET password_hash = ?2 WHERE user_id = ?1 AND kind = 'local'",
                    rusqlite::params![id, h],
                )?;
                tx.execute(
                    "DELETE FROM sessions WHERE user_id = ?1 AND token_hash != ?2",
                    rusqlite::params![id, keep],
                )?;
            }
            tx.commit()
        })
        .await?;
    let row = load(&st, u.id).await?;
    Ok(Json(render(&st, &u, &row)))
}

#[derive(Deserialize)]
#[serde(deny_unknown_fields)]
pub struct DeleteMe {
    confirm_username: Option<String>,
    current_password: Option<String>, // leak-check: ok
}

/// Local accounts confirm with their current password, SSO accounts by typing their display name.
pub async fn delete(
    State(st): State<AppState>,
    u: CurrentUser,
    ip: ClientIp,
    ApiJson(b): ApiJson<DeleteMe>,
) -> Result<Response, ApiError> {
    let row = load(&st, u.id).await?;
    match u.kind {
        AccountKind::Local => {
            let current = b.current_password.ok_or_else(|| {
                ApiError::bad_request(
                    "current_password_required",
                    "The current password is required.",
                )
            })?;
            let name = row.username.clone().unwrap_or_default();
            let attempt = local::reserve(&st, Some(&name), ip).await?;
            let (attempt, ok) = if current.len() <= 1024 {
                attempt.verify(&st, current, row.hash.clone()).await?
            } else {
                (attempt, false)
            };
            if !ok {
                return Err(ApiError::forbidden(
                    "wrong_password",
                    "The current password is wrong.",
                ));
            }
            attempt.succeed(&st.db).await?;
        }
        AccountKind::Sso => {
            if b.confirm_username.as_deref().map(str::trim) != Some(row.display_name.as_str()) {
                return Err(ApiError::bad_request(
                    "confirmation_mismatch",
                    "The confirmation does not match your account.",
                ));
            }
        }
    }
    let (id, now) = (u.id, ts(st.clock.now()));
    st.db
        .run(move |c| {
            let tx = c.transaction()?;
            tx.execute(
                "UPDATE invites SET cancelled_at = ?2 WHERE created_by = ?1 AND used_at IS NULL AND cancelled_at IS NULL",
                rusqlite::params![id, now],
            )?;
            let households: Vec<i64> = tx
                .prepare("SELECT household_id FROM memberships WHERE user_id = ?1")?
                .query_map([id], |r| r.get(0))?
                .collect::<rusqlite::Result<_>>()?;
            for sql in [
                "DELETE FROM identities WHERE user_id = ?1",
                "DELETE FROM sessions WHERE user_id = ?1",
                "DELETE FROM memberships WHERE user_id = ?1",
                "DELETE FROM push_subscriptions WHERE user_id = ?1",
            ] {
                tx.execute(sql, [id])?;
            }
            for hid in households {
                tx.execute(
                    "DELETE FROM households WHERE id = ?1 AND NOT EXISTS (SELECT 1 FROM memberships WHERE household_id = ?1)",
                    [hid],
                )?;
            }
            tx.execute(
                "UPDATE users SET former = 1, username = NULL, picture_url = NULL, last_household_id = NULL, deleted_at = ?2 WHERE id = ?1",
                rusqlite::params![id, now],
            )?;
            tx.commit()
        })
        .await?;
    if u.bearer {
        return Ok(StatusCode::NO_CONTENT.into_response());
    }
    Ok(local::clear_session_response(StatusCode::NO_CONTENT).into_response())
}

#[derive(Deserialize)]
#[serde(deny_unknown_fields)]
pub struct LastHousehold {
    id: i64,
}

pub async fn set_last_household(
    State(st): State<AppState>,
    u: CurrentUser,
    ApiJson(b): ApiJson<LastHousehold>,
) -> Result<StatusCode, ApiError> {
    let (uid, hid) = (u.id, b.id);
    let n = st
        .db
        .run(move |c| {
            c.execute(
                "UPDATE users SET last_household_id = ?2
                 WHERE id = ?1 AND EXISTS (SELECT 1 FROM memberships WHERE user_id = ?1 AND household_id = ?2)",
                rusqlite::params![uid, hid],
            )
        })
        .await?;
    if n == 0 {
        return Err(super::households::not_found());
    }
    Ok(StatusCode::NO_CONTENT)
}

/// Public id of a session: a hash of its token hash, so the stored hash never leaves the server.
fn device_id(token_hash: &str) -> String {
    crate::auth::session::hash_token(&format!("device:{token_hash}"))[..16].to_string()
}

pub async fn devices(State(st): State<AppState>, u: CurrentUser) -> Result<Json<Value>, ApiError> {
    let (uid, now) = (u.id, ts(st.clock.now()));
    let rows: Vec<(String, String, Option<String>, String, String)> = st
        .db
        .run(move |c| {
            c.prepare(
                "SELECT token_hash, kind, device_name, created_at, last_seen_at FROM sessions
                 WHERE user_id = ?1 AND expires_at > ?2 ORDER BY last_seen_at DESC, created_at DESC",
            )?
            .query_map(rusqlite::params![uid, now], |r| {
                Ok((r.get(0)?, r.get(1)?, r.get(2)?, r.get(3)?, r.get(4)?))
            })?
            .collect()
        })
        .await?;
    Ok(Json(Value::Array(
        rows.into_iter()
            .map(|(hash, kind, name, created, seen)| {
                serde_json::json!({
                    "id": device_id(&hash),
                    "kind": kind,
                    "device_name": name,
                    "created_at": created,
                    "last_seen_at": seen,
                    "current": hash == u.session_hash,
                })
            })
            .collect(),
    )))
}

/// Signs out one of the user's own sessions; the current one through the cookie also clears it.
pub async fn delete_device(
    State(st): State<AppState>,
    u: CurrentUser,
    axum::extract::Path(id): axum::extract::Path<String>,
) -> Result<Response, ApiError> {
    let uid = u.id;
    let hashes: Vec<String> = st
        .db
        .run(move |c| {
            c.prepare("SELECT token_hash FROM sessions WHERE user_id = ?1")?
                .query_map([uid], |r| r.get(0))?
                .collect()
        })
        .await?;
    let Some(hash) = hashes.into_iter().find(|h| device_id(h) == id) else {
        return Err(ApiError::not_found(
            "device_not_found",
            "This device is not signed in.",
        ));
    };
    let current = hash == u.session_hash;
    st.db
        .run(move |c| {
            c.execute(
                "DELETE FROM sessions WHERE token_hash = ?1 AND user_id = ?2",
                rusqlite::params![hash, uid],
            )
        })
        .await?;
    if current && !u.bearer {
        return Ok(local::clear_session_response(StatusCode::NO_CONTENT));
    }
    Ok(StatusCode::NO_CONTENT.into_response())
}
