use argon2::{Algorithm, Argon2, Params, PasswordHasher, PasswordVerifier, Version};
use std::sync::Arc;
use tokio::sync::Semaphore;

pub struct Hasher {
    argon: Argon2<'static>,
    dummy: String,
    slots: Semaphore,
}

impl Hasher {
    pub fn new() -> Arc<Hasher> {
        Self::with(Argon2::default())
    }

    /// Cheap parameters, for tests only.
    pub fn insecure_for_tests() -> Arc<Hasher> {
        let params = Params::new(8, 1, 1, None).expect("params");
        Self::with(Argon2::new(Algorithm::Argon2id, Version::V0x13, params))
    }

    fn with(argon: Argon2<'static>) -> Arc<Hasher> {
        let dummy = argon
            .hash_password(b"dummy password for timing")
            .expect("hash")
            .to_string();
        Arc::new(Hasher {
            argon,
            dummy,
            slots: Semaphore::new(4),
        })
    }

    pub async fn hash(self: &Arc<Self>, pw: String) -> Option<String> {
        let me = self.clone();
        let _permit = self.slots.acquire().await.ok()?;
        tokio::task::spawn_blocking(move || {
            me.argon
                .hash_password(pw.as_bytes())
                .ok()
                .map(|h| h.to_string())
        })
        .await
        .ok()
        .flatten()
    }

    /// `hash` None verifies against a dummy hash so unknown users cost the same time.
    pub async fn verify(self: &Arc<Self>, pw: String, hash: Option<String>) -> bool {
        let me = self.clone();
        let known = hash.is_some();
        let Ok(_permit) = self.slots.acquire().await else {
            return false;
        };
        let ok = tokio::task::spawn_blocking(move || {
            let h = hash.as_deref().unwrap_or(&me.dummy);
            me.argon.verify_password(pw.as_bytes(), h).is_ok()
        })
        .await
        .unwrap_or(false);
        ok && known
    }
}

use super::session::{self, SessionKind, parse_ts, ts};
use super::{ApiJson, ClientIp};
use crate::app::AppState;
use crate::db::Db;
use crate::error::ApiError;
use axum::{
    Json,
    extract::State,
    http::{HeaderMap, HeaderValue, StatusCode, header},
    response::{IntoResponse, Response},
};
use chrono::{DateTime, Duration, Utc};
use serde::Deserialize;

const WINDOW: Duration = Duration::minutes(15);
const MAX_PER_USER: i64 = 5;
const MAX_PER_IP: i64 = 20;
const MAX_PASSWORD_BYTES: usize = 1024;

pub fn user_key(username: &str) -> String {
    let lower: String = username.trim().to_lowercase().chars().take(64).collect();
    format!("user:{lower}")
}

pub fn ip_key(ip: ClientIp) -> String {
    match ip.0 {
        Some(ip) => format!("ip:{ip}"),
        None => "ip:unknown".into(),
    }
}

pub fn normalize_username(raw: &str) -> Option<String> {
    let u = raw.to_lowercase();
    let ok = (3..=32).contains(&u.chars().count())
        && u.chars()
            .all(|c| c.is_ascii_lowercase() || c.is_ascii_digit() || matches!(c, '.' | '_' | '-'));
    ok.then_some(u)
}

pub fn check_password(pw: &str) -> Result<(), ApiError> {
    if pw.chars().count() < 12 {
        return Err(ApiError::bad_request(
            "password_too_short",
            "The password must be at least 12 characters long.",
        ));
    }
    if pw.len() > MAX_PASSWORD_BYTES {
        return Err(ApiError::bad_request(
            "password_too_long",
            "The password is too long.",
        ));
    }
    Ok(())
}

pub fn check_display_name(raw: &str) -> Result<String, ApiError> {
    let n = raw.trim();
    if n.is_empty() || n.chars().count() > 60 {
        return Err(ApiError::bad_request(
            "invalid_display_name",
            "The display name must be 1 to 60 characters long.",
        ));
    }
    Ok(n.to_string())
}

/// Returns the seconds until the attempt is allowed again when one of the keys is over its limit.
pub async fn blocked(
    db: &Db,
    now: DateTime<Utc>,
    keys: [(String, i64); 2],
) -> crate::db::Result<Option<u64>> {
    db.run(move |c| {
        let cutoff = ts(now - WINDOW);
        c.execute("DELETE FROM login_failures WHERE at < ?1", [&cutoff])?;
        let mut wait: Option<i64> = None;
        for (key, max) in keys {
            let n: i64 = c.query_row(
                "SELECT count(*) FROM login_failures WHERE key = ?1",
                [&key],
                |r| r.get(0),
            )?;
            if n >= max {
                let at: String = c.query_row(
                    "SELECT at FROM login_failures WHERE key = ?1 ORDER BY at LIMIT 1 OFFSET ?2",
                    rusqlite::params![key, n - max],
                    |r| r.get(0),
                )?;
                let secs = parse_ts(&at).map_or(900, |t| (t + WINDOW - now).num_seconds().max(1));
                wait = Some(wait.map_or(secs, |w| w.max(secs)));
            }
        }
        Ok(wait.map(|w| w as u64))
    })
    .await
}

pub async fn record_failure(
    db: &Db,
    now: DateTime<Utc>,
    keys: [String; 2],
) -> crate::db::Result<()> {
    db.run(move |c| {
        for k in keys {
            c.execute(
                "INSERT INTO login_failures (key, at) VALUES (?1, ?2)",
                rusqlite::params![k, ts(now)],
            )?;
        }
        Ok(())
    })
    .await
}

pub async fn clear_failures(db: &Db, key: String) -> crate::db::Result<()> {
    db.run(move |c| {
        c.execute("DELETE FROM login_failures WHERE key = ?1", [key])
            .map(|_| ())
    })
    .await
}

pub fn limits(username: &str, ip: ClientIp) -> [(String, i64); 2] {
    [(user_key(username), MAX_PER_USER), (ip_key(ip), MAX_PER_IP)]
}

pub fn too_many(wait: u64) -> ApiError {
    let mut e = ApiError::new(
        StatusCode::TOO_MANY_REQUESTS,
        "too_many_attempts",
        "Too many failed attempts. Try again later.",
    );
    e.retry_after = Some(wait);
    e
}

fn with_cookie(status: StatusCode, cookie: String, body: Option<serde_json::Value>) -> Response {
    let mut res = match body {
        Some(b) => (status, Json(b)).into_response(),
        None => status.into_response(),
    };
    if let Ok(v) = HeaderValue::from_str(&cookie) {
        res.headers_mut().append(header::SET_COOKIE, v);
    }
    res
}

pub fn clear_session_response(status: StatusCode) -> Response {
    with_cookie(status, session::clear_cookie(), None)
}

fn require_local(st: &AppState) -> Result<(), ApiError> {
    if st.cfg.local_accounts {
        Ok(())
    } else {
        Err(ApiError::forbidden(
            "local_accounts_disabled",
            "Local accounts are disabled on this server.",
        ))
    }
}

#[derive(Deserialize)]
pub struct Register {
    username: String,
    #[serde(rename = "password")]
    pw: String,
    display_name: String,
    invite: Option<String>,
}

pub async fn register(
    State(st): State<AppState>,
    ApiJson(b): ApiJson<Register>,
) -> Result<Response, ApiError> {
    require_local(&st)?;
    if b.invite.is_some() {
        return Err(ApiError::bad_request(
            "invite_not_supported_yet",
            "Invites are not supported yet.",
        ));
    }
    if !st.cfg.open_registration {
        return Err(ApiError::forbidden(
            "registration_closed",
            "Registration is closed on this server.",
        ));
    }
    let username = normalize_username(&b.username).ok_or_else(|| {
        ApiError::bad_request(
            "invalid_username",
            "The username must be 3 to 32 characters: a-z, 0-9, dot, underscore, hyphen.",
        )
    })?;
    check_password(&b.pw)?;
    let display_name = check_display_name(&b.display_name)?;
    let hash = st.hasher.hash(b.pw).await.ok_or_else(ApiError::internal)?;
    let now = ts(st.clock.now());
    let (u, d) = (username.clone(), display_name.clone());
    let inserted = st
        .db
        .run(move |c| {
            let tx = c.transaction()?;
            tx.execute(
                "INSERT INTO users (username, display_name, created_at) VALUES (?1, ?2, ?3)",
                rusqlite::params![u, d, now],
            )?;
            let id = tx.last_insert_rowid();
            tx.execute(
                "INSERT INTO identities (user_id, kind, issuer, subject, password_hash) VALUES (?1, 'local', '', ?2, ?3)",
                rusqlite::params![id, u, hash],
            )?;
            tx.commit()?;
            Ok(id)
        })
        .await;
    let id = match inserted {
        Ok(id) => id,
        Err(crate::db::DbError::Sqlite(rusqlite::Error::SqliteFailure(e, _)))
            if e.extended_code == rusqlite::ffi::SQLITE_CONSTRAINT_UNIQUE =>
        {
            return Err(ApiError::new(
                StatusCode::CONFLICT,
                "username_taken",
                "This username is already taken.",
            ));
        }
        Err(e) => return Err(e.into()),
    };
    let sid = session::create(&st.db, &*st.clock, id, SessionKind::Web).await?;
    Ok(with_cookie(
        StatusCode::CREATED,
        session::set_cookie(&sid),
        Some(crate::api::me::user_json(
            id,
            Some(&username),
            &display_name,
            super::AccountKind::Local,
            None,
            None,
        )),
    ))
}

#[derive(Deserialize)]
pub struct Login {
    username: String,
    #[serde(rename = "password")]
    pw: String,
}

pub async fn login(
    State(st): State<AppState>,
    ip: ClientIp,
    ApiJson(b): ApiJson<Login>,
) -> Result<Response, ApiError> {
    require_local(&st)?;
    let now = st.clock.now();
    if let Some(wait) = blocked(&st.db, now, limits(&b.username, ip)).await? {
        return Err(too_many(wait));
    }
    let subject = b.username.trim().to_lowercase();
    let found: Option<(i64, Option<String>, String, Option<String>)> = st
        .db
        .run(move |c| {
            match c.query_row(
                "SELECT u.id, i.password_hash, u.display_name, u.username FROM identities i JOIN users u ON u.id = i.user_id
                 WHERE i.kind = 'local' AND i.issuer = '' AND i.subject = ?1 AND u.former = 0",
                [subject],
                |r| Ok((r.get(0)?, r.get(1)?, r.get(2)?, r.get(3)?)),
            ) {
                Ok(v) => Ok(Some(v)),
                Err(rusqlite::Error::QueryReturnedNoRows) => Ok(None),
                Err(e) => Err(e),
            }
        })
        .await?;
    let too_long = b.pw.len() > MAX_PASSWORD_BYTES;
    let hash = found.as_ref().and_then(|f| f.1.clone());
    let ok = !too_long && st.hasher.verify(b.pw, hash).await;
    let Some((id, _, display_name, username)) = found.filter(|_| ok) else {
        record_failure(&st.db, now, [user_key(&b.username), ip_key(ip)]).await?;
        return Err(ApiError::new(
            StatusCode::UNAUTHORIZED,
            "invalid_credentials",
            "Wrong username or password.",
        ));
    };
    clear_failures(&st.db, user_key(&b.username)).await?;
    let sid = session::create(&st.db, &*st.clock, id, SessionKind::Web).await?;
    Ok(with_cookie(
        StatusCode::OK,
        session::set_cookie(&sid),
        Some(crate::api::me::user_json(
            id,
            username.as_deref(),
            &display_name,
            super::AccountKind::Local,
            None,
            None,
        )),
    ))
}

pub async fn logout(State(st): State<AppState>, headers: HeaderMap) -> Result<Response, ApiError> {
    let prefix = concat!("__Host-wgc_session", "=");
    let raw = headers
        .get_all(header::COOKIE)
        .iter()
        .filter_map(|v| v.to_str().ok())
        .flat_map(|v| v.split(';'))
        .find_map(|c| c.trim().strip_prefix(prefix).map(str::to_string));
    if let Some(raw) = raw {
        session::delete(&st.db, &raw).await?;
    }
    Ok(clear_session_response(StatusCode::NO_CONTENT))
}
