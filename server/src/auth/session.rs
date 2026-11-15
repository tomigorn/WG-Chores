use super::{AccountKind, CurrentUser};
use crate::clock::Clock;
use crate::db::Db;
use base64::{Engine, engine::general_purpose::URL_SAFE_NO_PAD};
use chrono::{DateTime, Duration, SecondsFormat, Utc};
use sha2::{Digest, Sha256};

pub const COOKIE_NAME: &str = "__Host-wgc_session";
const IDLE: Duration = Duration::days(30);
const ABSOLUTE: Duration = Duration::days(90);
const ABSOLUTE_SSO: Duration = Duration::days(30);
const TOUCH_EVERY: Duration = Duration::hours(1);

#[derive(Clone, Copy, PartialEq, Debug)]
pub enum SessionKind {
    Web,
    Android,
}

impl SessionKind {
    pub fn as_str(self) -> &'static str {
        match self {
            SessionKind::Web => "web",
            SessionKind::Android => "android",
        }
    }
}

pub fn ts(t: DateTime<Utc>) -> String {
    t.to_rfc3339_opts(SecondsFormat::Millis, true)
}

pub fn parse_ts(s: &str) -> Option<DateTime<Utc>> {
    DateTime::parse_from_rfc3339(s)
        .ok()
        .map(|t| t.with_timezone(&Utc))
}

pub fn hash_token(raw: &str) -> String {
    Sha256::digest(raw.as_bytes())
        .iter()
        .map(|b| format!("{b:02x}"))
        .collect()
}

/// SSO accounts re-run the provider login sooner, so a removal there takes effect within 30 days.
fn expiry(created: DateTime<Utc>, seen: DateTime<Utc>, sso: bool) -> DateTime<Utc> {
    let absolute = if sso { ABSOLUTE_SSO } else { ABSOLUTE };
    (seen + IDLE).min(created + absolute)
}

/// Whether a stored session is still valid: its stored expiry, the idle/absolute rule for its account kind, and local-only accounts while local accounts are off.
#[allow(clippy::too_many_arguments)]
pub fn alive(
    created: &str,
    seen: &str,
    expires: &str,
    local: bool,
    sso: bool,
    local_enabled: bool,
    now: DateTime<Utc>,
) -> bool {
    let (Some(created), Some(seen), Some(expires)) =
        (parse_ts(created), parse_ts(seen), parse_ts(expires))
    else {
        return false;
    };
    !(!local_enabled && local && !sso) && now < expires.min(expiry(created, seen, sso))
}

pub async fn create(
    db: &Db,
    clock: &dyn Clock,
    user_id: i64,
    kind: SessionKind,
) -> crate::db::Result<String> {
    insert(db, clock, user_id, kind, None).await
}

/// An app session, used as bearer token; the name is cleaned by `device_name`.
pub async fn create_device(
    db: &Db,
    clock: &dyn Clock,
    user_id: i64,
    name: &str,
) -> crate::db::Result<String> {
    insert(
        db,
        clock,
        user_id,
        SessionKind::Android,
        Some(device_name(Some(name))),
    )
    .await
}

/// Trimmed, without control characters, at most 60 characters; "Android" when empty.
pub fn device_name(raw: Option<&str>) -> String {
    let n: String = crate::model::clean_text(raw.unwrap_or(""))
        .chars()
        .take(60)
        .collect();
    let n = n.trim();
    if n.is_empty() {
        "Android".into()
    } else {
        n.to_string()
    }
}

async fn insert(
    db: &Db,
    clock: &dyn Clock,
    user_id: i64,
    kind: SessionKind,
    name: Option<String>,
) -> crate::db::Result<String> {
    let mut bytes = [0u8; 32];
    getrandom::fill(&mut bytes).expect("os randomness");
    let raw = URL_SAFE_NO_PAD.encode(bytes);
    let hash = hash_token(&raw);
    let now = clock.now();
    let (now_s, exp_s) = (ts(now), ts(expiry(now, now, true)));
    db.run(move |c| {
        c.execute("DELETE FROM sessions WHERE expires_at < ?1", [&now_s])?;
        c.execute(
            "INSERT INTO sessions (token_hash, user_id, kind, created_at, last_seen_at, expires_at, device_name)
             VALUES (?1, ?2, ?3, ?4, ?4, ?5, ?6)",
            rusqlite::params![hash, user_id, kind.as_str(), now_s, exp_s, name],
        )
    })
    .await?;
    Ok(raw)
}

/// A session of `kind` only: a web token never works as bearer, an app token never as cookie.
pub async fn lookup(
    db: &Db,
    clock: &dyn Clock,
    raw: &str,
    local_enabled: bool,
    kind: SessionKind,
) -> crate::db::Result<Option<CurrentUser>> {
    let hash = hash_token(raw);
    let now = clock.now();
    db.run(move |c| {
        let row = c.query_row(
            "SELECT s.created_at, s.last_seen_at, s.expires_at, u.id, u.display_name,
                    EXISTS (SELECT 1 FROM identities i WHERE i.user_id = u.id AND i.kind = 'local'),
                    EXISTS (SELECT 1 FROM identities i WHERE i.user_id = u.id AND i.kind != 'local')
             FROM sessions s JOIN users u ON u.id = s.user_id
             WHERE s.token_hash = ?1 AND s.kind = ?2 AND u.former = 0",
            rusqlite::params![hash, kind.as_str()],
            |r| {
                Ok((
                    r.get::<_, String>(0)?,
                    r.get::<_, String>(1)?,
                    r.get::<_, String>(2)?,
                    r.get::<_, i64>(3)?,
                    r.get::<_, String>(4)?,
                    r.get::<_, bool>(5)?,
                    r.get::<_, bool>(6)?,
                ))
            },
        );
        let (created, seen, expires, id, display_name, local, other) = match row {
            Ok(v) => v,
            Err(rusqlite::Error::QueryReturnedNoRows) => return Ok(None),
            Err(e) => return Err(e),
        };
        if !alive(&created, &seen, &expires, local, other, local_enabled, now) {
            c.execute("DELETE FROM sessions WHERE token_hash = ?1", [&hash])?;
            return Ok(None);
        }
        let (Some(created), Some(seen)) = (parse_ts(&created), parse_ts(&seen)) else {
            return Ok(None);
        };
        if now - seen >= TOUCH_EVERY {
            c.execute(
                "UPDATE sessions SET last_seen_at = ?2, expires_at = ?3 WHERE token_hash = ?1",
                rusqlite::params![hash, ts(now), ts(expiry(created, now, other))],
            )?;
        }
        Ok(Some(CurrentUser {
            id,
            display_name,
            kind: if local {
                AccountKind::Local
            } else {
                AccountKind::Sso
            },
            session_hash: hash,
            bearer: kind == SessionKind::Android,
        }))
    })
    .await
}

pub async fn delete(db: &Db, raw: &str) -> crate::db::Result<()> {
    let hash = hash_token(raw);
    db.run(move |c| {
        c.execute("DELETE FROM sessions WHERE token_hash = ?1", [hash])
            .map(|_| ())
    })
    .await
}

pub fn set_cookie(raw: &str) -> String {
    format!(
        "{COOKIE_NAME}={raw}; HttpOnly; Secure; SameSite=Lax; Path=/; Max-Age={}",
        ABSOLUTE.num_seconds()
    )
}

pub fn clear_cookie() -> String {
    format!("{COOKIE_NAME}=; HttpOnly; Secure; SameSite=Lax; Path=/; Max-Age=0")
}
