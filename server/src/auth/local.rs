use super::session::{self, SessionKind, parse_ts, ts};
use super::{ApiJson, ClientIp};
use crate::app::AppState;
use crate::db::Db;
use crate::error::ApiError;
use crate::model::user_json;
use axum::{
    Json,
    extract::State,
    http::{HeaderMap, HeaderValue, StatusCode, header},
    response::{IntoResponse, Response},
};
use chrono::Duration;
use serde::Deserialize;

const WINDOW: Duration = Duration::minutes(15);
const COUNTER_WINDOW: Duration = Duration::hours(1);
const MAX_PER_USER: i64 = 5;
const MAX_PER_IP: i64 = 20;
const MAX_PASSWORD_BYTES: usize = 1024;

/// Per account and client, so failures from one address never lock the account for others; the per-ip limit stays the hard stop.
pub fn user_key(username: &str, ip: ClientIp) -> String {
    let lower: String = username.trim().to_lowercase().chars().take(64).collect();
    format!("user:{lower}@{}", ip_key(ip))
}

/// IPv6 clients are keyed by their /64, since one client owns at least that many addresses.
pub fn ip_key(ip: ClientIp) -> String {
    match ip.0 {
        Some(std::net::IpAddr::V6(v6)) => {
            let net = std::net::Ipv6Addr::from(u128::from(v6) & !((1u128 << 64) - 1));
            format!("ip:{net}/64")
        }
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
    let n = crate::model::clean_text(raw);
    if n.is_empty() || n.chars().count() > 60 {
        return Err(ApiError::bad_request(
            "invalid_display_name",
            "The display name must be 1 to 60 characters long.",
        ));
    }
    Ok(n)
}

/// A reserved attempt: the failure rows are inserted up front so concurrent requests cannot overshoot the limits.
pub struct Attempt {
    rows: Vec<i64>,
    user: Option<String>,
}

/// Atomically checks the limits and reserves a failure row for the ip and the optional account key.
pub async fn reserve(st: &AppState, user: Option<&str>, ip: ClientIp) -> Result<Attempt, ApiError> {
    let user_key = user.map(|u| user_key(u, ip));
    let mut keys = vec![(ip_key(ip), MAX_PER_IP)];
    keys.extend(user_key.clone().map(|k| (k, MAX_PER_USER)));
    reserve_keys(st, keys, user_key).await
}

/// Registrations count in their own per-ip bucket, so they never lock logins.
pub async fn reserve_registration(st: &AppState, ip: ClientIp) -> Result<(), ApiError> {
    reserve_keys(st, vec![(format!("reg:{}", ip_key(ip)), MAX_PER_IP)], None).await?;
    Ok(())
}

/// Failed app code exchanges count in their own per-ip bucket.
pub async fn reserve_app_code(st: &AppState, ip: ClientIp) -> Result<Attempt, ApiError> {
    reserve_keys(st, vec![(format!("app:{}", ip_key(ip)), MAX_PER_IP)], None).await
}

/// Counts one attempt against `key`, refusing with 429 once `max` are within the last hour.
pub async fn reserve_counter(st: &AppState, key: String, max: i64) -> Result<(), ApiError> {
    reserve_window(st, vec![(key, max)], None, COUNTER_WINDOW).await?;
    Ok(())
}

async fn reserve_keys(
    st: &AppState,
    keys: Vec<(String, i64)>,
    user_key: Option<String>,
) -> Result<Attempt, ApiError> {
    reserve_window(st, keys, user_key, WINDOW).await
}

async fn reserve_window(
    st: &AppState,
    keys: Vec<(String, i64)>,
    user_key: Option<String>,
    window: Duration,
) -> Result<Attempt, ApiError> {
    let now = st.clock.now();
    let out = st
        .db
        .run(move |c| {
            let tx = c.transaction()?;
            let oldest = ts(now - COUNTER_WINDOW);
            tx.execute("DELETE FROM login_failures WHERE at < ?1", [&oldest])?;
            let cutoff = ts(now - window);
            let mut wait: Option<i64> = None;
            for (key, max) in &keys {
                let n: i64 = tx.query_row(
                    "SELECT count(*) FROM login_failures WHERE key = ?1 AND at >= ?2",
                    rusqlite::params![key, cutoff],
                    |r| r.get(0),
                )?;
                if n >= *max {
                    let at: String = tx.query_row(
                        "SELECT at FROM login_failures WHERE key = ?1 AND at >= ?2 ORDER BY at, rowid LIMIT 1 OFFSET ?3",
                        rusqlite::params![key, cutoff, n - max],
                        |r| r.get(0),
                    )?;
                    let secs = parse_ts(&at).map_or(window.num_seconds(), |t| {
                        (t + window - now).num_seconds().max(1)
                    });
                    wait = Some(wait.map_or(secs, |w| w.max(secs)));
                }
            }
            if let Some(w) = wait {
                return Ok(Err(w as u64));
            }
            let mut rows = Vec::with_capacity(keys.len());
            for (key, _) in &keys {
                tx.execute(
                    "INSERT INTO login_failures (key, at) VALUES (?1, ?2)",
                    rusqlite::params![key, ts(now)],
                )?;
                rows.push(tx.last_insert_rowid());
            }
            tx.commit()?;
            Ok(Ok(rows))
        })
        .await?;
    match out {
        Ok(rows) => Ok(Attempt {
            rows,
            user: user_key,
        }),
        Err(wait) => Err(too_many(wait)),
    }
}

impl Attempt {
    /// Success: the reserved ip row is released and the account's failures are cleared.
    pub async fn succeed(self, db: &Db) -> crate::db::Result<()> {
        db.run(move |c| {
            if let Some(ip_row) = self.rows.first() {
                c.execute("DELETE FROM login_failures WHERE rowid = ?1", [ip_row])?;
            }
            if let Some(k) = self.user {
                c.execute("DELETE FROM login_failures WHERE key = ?1", [k])?;
            }
            Ok(())
        })
        .await
    }

    /// The check never ran (e.g. the hasher was busy): only the rows reserved by this attempt go, earlier failures stay.
    pub async fn release(self, db: &Db) -> crate::db::Result<()> {
        db.run(move |c| {
            for row in &self.rows {
                c.execute("DELETE FROM login_failures WHERE rowid = ?1", [row])?;
            }
            Ok(())
        })
        .await
    }

    /// Verifies a password; when that cannot run, the reservation is released and the error returned.
    pub async fn verify(
        self,
        st: &AppState,
        pw: String,
        hash: Option<String>,
    ) -> Result<(Attempt, bool), ApiError> {
        match st.hasher.verify(pw, hash).await {
            Ok(ok) => Ok((self, ok)),
            Err(e) => {
                self.release(&st.db).await?;
                Err(e)
            }
        }
    }
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
    client: Option<String>,
    device_name: Option<String>,
}

/// `client` "android" answers with a bearer token instead of the session cookie.
/// A browser sign-in carrying a bearer token is refused: bearer requests never get cookies.
fn is_app(client: Option<&str>, headers: &HeaderMap) -> Result<bool, ApiError> {
    match client {
        None | Some("web") if super::bearer(headers).is_some() => Err(ApiError::bad_request(
            "ambiguous_auth",
            "A browser sign-in cannot carry a bearer token.",
        )),
        None | Some("web") => Ok(false),
        Some("android") => Ok(true),
        Some(_) => Err(ApiError::bad_request("invalid_client", "Unknown client.")),
    }
}

/// Signs the user in: an app gets `{token, user}`, the browser the session cookie and the user.
async fn signed_in(
    st: &AppState,
    headers: &HeaderMap,
    status: StatusCode,
    id: i64,
    app: Option<Option<String>>,
    user: serde_json::Value,
) -> Result<Response, ApiError> {
    if let Some(name) = app {
        let device =
            session::create_device(&st.db, &*st.clock, id, name.as_deref().unwrap_or("")).await?;
        return Ok((
            status,
            Json(serde_json::json!({"token": device, "user": user})),
        )
            .into_response());
    }
    drop_incoming_session(st, headers).await?;
    let sid = session::create(&st.db, &*st.clock, id, SessionKind::Web).await?;
    Ok(with_cookie(status, session::set_cookie(&sid), Some(user)))
}

pub async fn register(
    State(st): State<AppState>,
    ip: ClientIp,
    headers: HeaderMap,
    ApiJson(b): ApiJson<Register>,
) -> Result<Response, ApiError> {
    require_local(&st)?;
    let app = is_app(b.client.as_deref(), &headers)?.then(|| b.device_name.clone());
    let invite_hash = b
        .invite
        .as_deref()
        .map(crate::api::invites::token_hash)
        .transpose()?;
    if invite_hash.is_none() && !st.cfg.open_registration {
        return Err(ApiError::forbidden(
            "registration_closed",
            "Registration is closed on this server.",
        ));
    }
    reserve_registration(&st, ip).await?;
    if let Some(h) = &invite_hash {
        crate::api::invites::ensure_open(&st, h.clone()).await?;
    }
    let username = normalize_username(&b.username).ok_or_else(|| {
        ApiError::bad_request(
            "invalid_username",
            "The username must be 3 to 32 characters: a-z, 0-9, dot, underscore, hyphen.",
        )
    })?;
    check_password(&b.pw)?;
    let display_name = check_display_name(&b.display_name)?;
    let taken_check = username.clone();
    let taken: bool = st
        .db
        .run(move |c| {
            c.query_row(
                "SELECT EXISTS (SELECT 1 FROM users WHERE username = ?1)",
                [taken_check],
                |r| r.get(0),
            )
        })
        .await?;
    if taken {
        return Err(username_taken());
    }
    let hash = st.hasher.hash(b.pw).await?;
    let now = ts(st.clock.now());
    let (u, d) = (username.clone(), display_name.clone());
    let inserted = st
        .db
        .run(move |c| {
            let tx = c.transaction()?;
            let id = insert_local_user(&tx, &u, &d, &hash, &now)?;
            if let Some(h) = &invite_hash
                && let Err(e) = crate::api::invites::accept_in_tx(&tx, h, id, &now)?
            {
                return Ok(Err(e));
            }
            tx.commit()?;
            Ok(Ok(id))
        })
        .await;
    let id = match inserted {
        Ok(id) => id?,
        Err(crate::db::DbError::Sqlite(rusqlite::Error::SqliteFailure(e, _)))
            if e.extended_code == rusqlite::ffi::SQLITE_CONSTRAINT_UNIQUE =>
        {
            return Err(username_taken());
        }
        Err(e) => return Err(e.into()),
    };
    let user = user_json(
        id,
        Some(&username),
        &display_name,
        super::AccountKind::Local,
        None,
        None,
    );
    signed_in(&st, &headers, StatusCode::CREATED, id, app, user).await
}

/// A user row plus its local password identity; returns the user id.
pub fn insert_local_user(
    c: &rusqlite::Connection,
    username: &str,
    display_name: &str,
    hash: &str,
    now: &str,
) -> rusqlite::Result<i64> {
    c.execute(
        "INSERT INTO users (username, display_name, created_at) VALUES (?1, ?2, ?3)",
        rusqlite::params![username, display_name, now],
    )?;
    let id = c.last_insert_rowid();
    c.execute(
        "INSERT INTO identities (user_id, kind, issuer, subject, password_hash) VALUES (?1, 'local', '', ?2, ?3)",
        rusqlite::params![id, username, hash],
    )?;
    Ok(id)
}

fn username_taken() -> ApiError {
    ApiError::new(
        StatusCode::CONFLICT,
        "username_taken",
        "This username is already taken.",
    )
}

pub(crate) async fn drop_incoming_session(
    st: &AppState,
    headers: &HeaderMap,
) -> Result<(), ApiError> {
    if let Some(raw) = super::session_cookie(headers) {
        session::delete(&st.db, &raw).await?;
    }
    Ok(())
}

#[derive(Deserialize)]
pub struct Login {
    username: String,
    #[serde(rename = "password")]
    pw: String,
    client: Option<String>,
    device_name: Option<String>,
}

pub async fn login(
    State(st): State<AppState>,
    ip: ClientIp,
    headers: HeaderMap,
    ApiJson(b): ApiJson<Login>,
) -> Result<Response, ApiError> {
    require_local(&st)?;
    let app = is_app(b.client.as_deref(), &headers)?.then(|| b.device_name.clone());
    let attempt = reserve(&st, Some(&b.username), ip).await?;
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
    let (attempt, ok) = if too_long {
        (attempt, false)
    } else {
        attempt.verify(&st, b.pw, hash).await?
    };
    let Some((id, _, display_name, username)) = found.filter(|_| ok) else {
        return Err(ApiError::new(
            StatusCode::UNAUTHORIZED,
            "invalid_credentials",
            "Wrong username or password.",
        ));
    };
    attempt.succeed(&st.db).await?;
    let user = user_json(
        id,
        username.as_deref(),
        &display_name,
        super::AccountKind::Local,
        None,
        None,
    );
    signed_in(&st, &headers, StatusCode::OK, id, app, user).await
}

#[derive(Deserialize, Default)]
#[serde(deny_unknown_fields)]
struct Logout {
    push_endpoint: Option<String>,
}

/// Optional body `{push_endpoint}`: that push subscription is deleted too when it belongs to the signed-in user.
pub async fn logout(
    State(st): State<AppState>,
    headers: HeaderMap,
    body: axum::body::Bytes,
) -> Result<Response, ApiError> {
    let b: Logout = if body.iter().all(u8::is_ascii_whitespace) {
        Logout::default()
    } else {
        serde_json::from_slice(&body)
            .map_err(|_| ApiError::bad_request("invalid_body", "The request body is not valid."))?
    };
    let cred = super::credential(&headers)?;
    if let (Some(endpoint), Some((raw, _))) = (b.push_endpoint, cred.clone()) {
        let hash = session::hash_token(&raw);
        st.db
            .run(move |c| {
                c.execute(
                    "DELETE FROM push_subscriptions WHERE endpoint = ?1
                     AND user_id = (SELECT user_id FROM sessions WHERE token_hash = ?2)",
                    rusqlite::params![endpoint, hash],
                )
            })
            .await?;
    }
    match cred {
        Some((raw, true)) => {
            session::delete(&st.db, &raw).await?;
            Ok(StatusCode::NO_CONTENT.into_response())
        }
        _ => {
            drop_incoming_session(&st, &headers).await?;
            Ok(clear_session_response(StatusCode::NO_CONTENT))
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn ipv6_key_is_the_64() {
        let ip = |s: &str| ClientIp(Some(s.parse().unwrap()));
        assert_eq!(ip_key(ip("2001:db8:1:2:3:4:5:6")), "ip:2001:db8:1:2::/64");
        assert_eq!(ip_key(ip("192.0.2.1")), "ip:192.0.2.1");
    }
}
