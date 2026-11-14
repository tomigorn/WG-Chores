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
}

async fn load(st: &AppState, id: i64) -> Result<Row, ApiError> {
    Ok(st
        .db
        .run(move |c| {
            c.query_row(
                "SELECT u.username, u.display_name, u.picture_url,
                        (SELECT password_hash FROM identities WHERE user_id = u.id AND kind = 'local')
                 FROM users u WHERE u.id = ?1",
                [id],
                |r| {
                    Ok(Row {
                        username: r.get(0)?,
                        display_name: r.get(1)?,
                        picture_url: r.get(2)?,
                        hash: r.get(3)?,
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
    user_json(
        u.id,
        r.username.as_deref(),
        &r.display_name,
        u.kind,
        r.picture_url.as_deref(),
        account_url.map(|u| u.as_str()),
    )
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
}

pub async fn patch(
    State(st): State<AppState>,
    ip: ClientIp,
    u: CurrentUser,
    ApiJson(b): ApiJson<PatchMe>,
) -> Result<Json<Value>, ApiError> {
    if b.display_name.is_none() && b.current.is_none() && b.new.is_none() {
        return Err(ApiError::bad_request(
            "nothing_to_change",
            "Nothing to change.",
        ));
    }
    if u.kind == AccountKind::Sso {
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
        let ok = current.len() <= 1024 && st.hasher.verify(current, row.hash.clone()).await;
        if !ok {
            return Err(ApiError::forbidden(
                "wrong_password",
                "The current password is wrong.",
            ));
        }
        attempt.succeed(&st.db).await?;
        new_hash = Some(st.hasher.hash(new).await.ok_or_else(ApiError::internal)?);
    }
    let (id, keep, dn, nh) = (u.id, u.session_hash.clone(), display_name.clone(), new_hash);
    st.db
        .run(move |c| {
            let tx = c.transaction()?;
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
    confirm_username: String,
}

pub async fn delete(
    State(st): State<AppState>,
    u: CurrentUser,
    ApiJson(b): ApiJson<DeleteMe>,
) -> Result<Response, ApiError> {
    let row = load(&st, u.id).await?;
    let given = b.confirm_username.trim();
    let matches = match u.kind {
        AccountKind::Local => row
            .username
            .as_deref()
            .is_some_and(|n| n.eq_ignore_ascii_case(given)),
        AccountKind::Sso => given == row.display_name,
    };
    if !matches {
        return Err(ApiError::bad_request(
            "confirmation_mismatch",
            "The confirmation does not match your account.",
        ));
    }
    let (id, now) = (u.id, ts(st.clock.now()));
    st.db
        .run(move |c| {
            let tx = c.transaction()?;
            for sql in [
                "DELETE FROM identities WHERE user_id = ?1",
                "DELETE FROM sessions WHERE user_id = ?1",
                "DELETE FROM memberships WHERE user_id = ?1",
                "DELETE FROM push_subscriptions WHERE user_id = ?1",
            ] {
                tx.execute(sql, [id])?;
            }
            tx.execute(
                "UPDATE users SET former = 1, username = NULL, picture_url = NULL, last_household_id = NULL, deleted_at = ?2 WHERE id = ?1",
                rusqlite::params![id, now],
            )?;
            tx.commit()
        })
        .await?;
    Ok(local::clear_session_response(StatusCode::NO_CONTENT).into_response())
}
