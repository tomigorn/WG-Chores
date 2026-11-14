use crate::api::households::not_found;
use crate::app::AppState;
use crate::auth::session::ts;
use crate::auth::{ApiJson, CurrentUser};
use crate::error::ApiError;
use crate::notify::webpush::{b64_decode, check_endpoint, valid_public_key};
use axum::{Json, extract::State, http::StatusCode};
use base64::{Engine, engine::general_purpose::URL_SAFE_NO_PAD};
use rusqlite::{OptionalExtension, params};
use serde::Deserialize;
use serde_json::{Value, json};

const MAX_PER_USER: i64 = 20;

pub async fn key(State(st): State<AppState>) -> Json<Value> {
    Json(json!({"key": st.push.vapid.public_b64()}))
}

#[derive(Deserialize)]
#[serde(deny_unknown_fields)]
pub struct Keys {
    p256dh: String,
    auth: String,
}

#[derive(Deserialize)]
#[serde(deny_unknown_fields)]
pub struct Subscribe {
    endpoint: String,
    keys: Keys,
    lang: Option<String>,
    #[serde(rename = "expirationTime", default)]
    _expiration_time: Option<Value>,
}

fn invalid() -> ApiError {
    ApiError::bad_request(
        "invalid_subscription",
        "The push subscription is not valid.",
    )
}

pub async fn subscribe(
    State(st): State<AppState>,
    u: CurrentUser,
    ApiJson(b): ApiJson<Subscribe>,
) -> Result<StatusCode, ApiError> {
    check_endpoint(&b.endpoint, st.cfg.push_allow_loopback_http).ok_or_else(invalid)?;
    let p256dh = b64_decode(&b.keys.p256dh)
        .filter(|k| valid_public_key(k))
        .ok_or_else(invalid)?;
    let auth = b64_decode(&b.keys.auth)
        .filter(|a| a.len() == 16)
        .ok_or_else(invalid)?;
    let lang = match b.lang.as_deref() {
        None => "en",
        Some(l @ ("en" | "de")) => l,
        Some(_) => {
            return Err(ApiError::bad_request(
                "invalid_lang",
                "The language must be en or de.",
            ));
        }
    };
    let (uid, now, lang) = (u.id, ts(st.clock.now()), lang.to_string());
    let (endpoint, p256dh, auth) = (
        b.endpoint,
        URL_SAFE_NO_PAD.encode(p256dh),
        URL_SAFE_NO_PAD.encode(auth),
    );
    st.db
        .run(move |c| {
            let tx = c.transaction()?;
            let owner: Option<(i64, i64)> = tx
                .query_row(
                    "SELECT id, user_id FROM push_subscriptions WHERE endpoint = ?1",
                    [&endpoint],
                    |r| Ok((r.get(0)?, r.get(1)?)),
                )
                .optional()?;
            match owner {
                Some((id, owner)) if owner == uid => {
                    tx.execute(
                        "UPDATE push_subscriptions SET p256dh = ?2, auth = ?3, lang = ?4 WHERE id = ?1",
                        params![id, p256dh, auth, lang],
                    )?;
                }
                _ => {
                    tx.execute("DELETE FROM push_subscriptions WHERE endpoint = ?1", [&endpoint])?;
                    tx.execute(
                        "INSERT INTO push_subscriptions (user_id, endpoint, p256dh, auth, lang, created_at)
                         VALUES (?1, ?2, ?3, ?4, ?5, ?6)",
                        params![uid, endpoint, p256dh, auth, lang, now],
                    )?;
                    tx.execute(
                        "DELETE FROM push_subscriptions WHERE user_id = ?1 AND id NOT IN
                           (SELECT id FROM push_subscriptions WHERE user_id = ?1 ORDER BY created_at DESC, id DESC LIMIT ?2)",
                        params![uid, MAX_PER_USER],
                    )?;
                }
            }
            tx.commit()
        })
        .await?;
    Ok(StatusCode::NO_CONTENT)
}

#[derive(Deserialize)]
#[serde(deny_unknown_fields)]
pub struct Unsubscribe {
    endpoint: String,
}

pub async fn unsubscribe(
    State(st): State<AppState>,
    u: CurrentUser,
    ApiJson(b): ApiJson<Unsubscribe>,
) -> Result<StatusCode, ApiError> {
    let uid = u.id;
    let n = st
        .db
        .run(move |c| {
            c.execute(
                "DELETE FROM push_subscriptions WHERE endpoint = ?1 AND user_id = ?2",
                params![b.endpoint, uid],
            )
        })
        .await?;
    if n == 0 {
        return Err(not_found());
    }
    Ok(StatusCode::NO_CONTENT)
}
