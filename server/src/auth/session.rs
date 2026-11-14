use super::{AccountKind, CurrentUser};
use crate::clock::Clock;
use crate::db::Db;
use base64::{Engine, engine::general_purpose::URL_SAFE_NO_PAD};
use chrono::{DateTime, Duration, SecondsFormat, Utc};
use sha2::{Digest, Sha256};

pub const COOKIE_NAME: &str = "__Host-wgc_session";
const IDLE: Duration = Duration::days(30);
const ABSOLUTE: Duration = Duration::days(90);
const TOUCH_EVERY: Duration = Duration::hours(1);

#[derive(Clone, Copy, PartialEq, Debug)]
pub enum SessionKind {
    Web,
    Android,
}

impl SessionKind {
    fn as_str(self) -> &'static str {
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

fn expiry(created: DateTime<Utc>, seen: DateTime<Utc>) -> DateTime<Utc> {
    (seen + IDLE).min(created + ABSOLUTE)
}

pub async fn create(
    db: &Db,
    clock: &dyn Clock,
    user_id: i64,
    kind: SessionKind,
) -> crate::db::Result<String> {
    let mut bytes = [0u8; 32];
    getrandom::fill(&mut bytes).expect("os randomness");
    let raw = URL_SAFE_NO_PAD.encode(bytes);
    let hash = hash_token(&raw);
    let now = clock.now();
    let (now_s, exp_s) = (ts(now), ts(expiry(now, now)));
    db.run(move |c| {
        c.execute("DELETE FROM sessions WHERE expires_at < ?1", [&now_s])?;
        c.execute(
            "INSERT INTO sessions (token_hash, user_id, kind, created_at, last_seen_at, expires_at)
             VALUES (?1, ?2, ?3, ?4, ?4, ?5)",
            rusqlite::params![hash, user_id, kind.as_str(), now_s, exp_s],
        )
    })
    .await?;
    Ok(raw)
}

pub async fn lookup(
    db: &Db,
    clock: &dyn Clock,
    raw: &str,
    local_enabled: bool,
) -> crate::db::Result<Option<CurrentUser>> {
    let hash = hash_token(raw);
    let now = clock.now();
    db.run(move |c| {
        let row = c.query_row(
            "SELECT s.created_at, s.last_seen_at, s.expires_at, u.id, u.display_name,
                    EXISTS (SELECT 1 FROM identities i WHERE i.user_id = u.id AND i.kind = 'local'),
                    EXISTS (SELECT 1 FROM identities i WHERE i.user_id = u.id AND i.kind != 'local')
             FROM sessions s JOIN users u ON u.id = s.user_id
             WHERE s.token_hash = ?1 AND u.former = 0",
            [&hash],
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
        let (Some(created), Some(seen), Some(expires)) =
            (parse_ts(&created), parse_ts(&seen), parse_ts(&expires))
        else {
            return Ok(None);
        };
        if (!local_enabled && local && !other) || now >= expires {
            c.execute("DELETE FROM sessions WHERE token_hash = ?1", [&hash])?;
            return Ok(None);
        }
        if now - seen >= TOUCH_EVERY {
            c.execute(
                "UPDATE sessions SET last_seen_at = ?2, expires_at = ?3 WHERE token_hash = ?1",
                rusqlite::params![hash, ts(now), ts(expiry(created, now))],
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
