use super::session::{self, hash_token, parse_ts, ts};
use super::{ApiJson, ClientIp};
use crate::app::AppState;
use crate::error::ApiError;
use axum::{Json, extract::State};
use base64::{Engine, engine::general_purpose::URL_SAFE_NO_PAD};
use chrono::Duration;
use rusqlite::OptionalExtension;
use serde::Deserialize;
use serde_json::{Value, json};
use sha2::{Digest, Sha256};

pub const REDIRECT: &str = "wgchores://auth";
const CODE_TTL: Duration = Duration::seconds(60);

/// base64url without padding of a SHA-256: exactly 43 characters.
pub fn valid_challenge(c: &str) -> bool {
    c.len() == 43
        && c.bytes()
            .all(|b| b.is_ascii_alphanumeric() || b == b'-' || b == b'_')
}

/// RFC 7636: 43 to 128 unreserved characters.
pub fn valid_verifier(v: &str) -> bool {
    (43..=128).contains(&v.len())
        && v.bytes()
            .all(|b| b.is_ascii_alphanumeric() || matches!(b, b'-' | b'.' | b'_' | b'~'))
}

fn s256(verifier: &str) -> String {
    URL_SAFE_NO_PAD.encode(Sha256::digest(verifier.as_bytes()))
}

fn same(a: &str, b: &str) -> bool {
    a.len() == b.len()
        && a.bytes()
            .zip(b.bytes())
            .fold(0u8, |acc, (x, y)| acc | (x ^ y))
            == 0
}

/// Stores a one-time code (hashed) for the user and the app's challenge; returns the code.
pub async fn issue_code(
    st: &AppState,
    user_id: i64,
    challenge: String,
) -> crate::db::Result<String> {
    let mut bytes = [0u8; 32];
    getrandom::fill(&mut bytes).expect("os randomness");
    let code = URL_SAFE_NO_PAD.encode(bytes);
    let hash = hash_token(&code);
    let now = st.clock.now();
    let (now_s, exp_s) = (ts(now), ts(now + CODE_TTL));
    st.db
        .run(move |c| {
            c.execute("DELETE FROM app_codes WHERE expires_at < ?1", [&now_s])?;
            c.execute(
                "INSERT INTO app_codes (code_hash, user_id, challenge, expires_at) VALUES (?1, ?2, ?3, ?4)",
                rusqlite::params![hash, user_id, challenge, exp_s],
            )
        })
        .await?;
    Ok(code)
}

#[derive(Deserialize)]
#[serde(deny_unknown_fields)]
pub struct Exchange {
    code: String,
    verifier: String,
    device_name: Option<String>,
}

fn invalid_code() -> ApiError {
    ApiError::bad_request(
        "invalid_code",
        "The sign-in code is invalid or expired. Sign in again.",
    )
}

/// `POST /api/v1/auth/app-token`: the code from the SSO redirect plus the PKCE verifier → device token.
pub async fn token(
    State(st): State<AppState>,
    ip: ClientIp,
    ApiJson(b): ApiJson<Exchange>,
) -> Result<Json<Value>, ApiError> {
    let attempt = super::local::reserve_app_code(&st, ip).await?;
    let hash = hash_token(&b.code);
    let found = st
        .db
        .run(move |c| {
            let tx = c.transaction()?;
            let row: Option<(i64, String, String)> = tx
                .query_row(
                    "SELECT a.user_id, a.challenge, a.expires_at FROM app_codes a
                     JOIN users u ON u.id = a.user_id AND u.former = 0 WHERE a.code_hash = ?1",
                    [&hash],
                    |r| Ok((r.get(0)?, r.get(1)?, r.get(2)?)),
                )
                .optional()?;
            tx.execute("DELETE FROM app_codes WHERE code_hash = ?1", [&hash])?;
            tx.commit()?;
            Ok(row)
        })
        .await?;
    let now = st.clock.now();
    let user = found.and_then(|(id, challenge, exp)| {
        let fresh = parse_ts(&exp).is_some_and(|e| now < e);
        let ok = fresh && valid_verifier(&b.verifier) && same(&s256(&b.verifier), &challenge);
        ok.then_some(id)
    });
    let Some(id) = user else {
        return Err(invalid_code());
    };
    attempt.succeed(&st.db).await?;
    let device = session::create_device(
        &st.db,
        &*st.clock,
        id,
        b.device_name.as_deref().unwrap_or(""),
    )
    .await?;
    Ok(Json(json!({"token": device}))) // leak-check: ok
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn rfc7636_example_matches() {
        let v = "dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk";
        assert!(valid_verifier(v));
        assert_eq!(s256(v), "E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM");
        assert!(valid_challenge(&s256(v)));
        assert!(!valid_verifier(&"a".repeat(42)));
        assert!(valid_verifier(&"a.~_-".repeat(9)));
        assert!(!valid_challenge(
            "E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-c="
        ));
    }
}
