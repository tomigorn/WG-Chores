use super::local::{drop_incoming_session, normalize_username};
use super::session::{self, SessionKind, ts};
use crate::app::AppState;
use crate::config::OidcConfig;
use axum::{
    extract::{RawQuery, State},
    http::{HeaderMap, HeaderValue, StatusCode, header},
    response::{IntoResponse, Response},
};
use base64::{Engine, engine::general_purpose::URL_SAFE_NO_PAD};
use chrono::{DateTime, Duration, Utc};
use hmac::{Hmac, KeyInit, Mac};
use openidconnect::core::{
    CoreAuthDisplay, CoreAuthPrompt, CoreErrorResponseType, CoreGenderClaim, CoreJsonWebKey,
    CoreJweContentEncryptionAlgorithm, CoreJwsSigningAlgorithm, CoreProviderMetadata,
    CoreResponseType, CoreRevocableToken, CoreRevocationErrorResponse,
    CoreTokenIntrospectionResponse, CoreTokenType,
};
use openidconnect::{
    AdditionalClaims, AuthenticationFlow, AuthorizationCode, ClaimsVerificationError, Client,
    ClientId, ClientSecret, CsrfToken, EmptyExtraTokenFields, EndpointMaybeSet, EndpointNotSet,
    EndpointSet, IdTokenFields, IssuerUrl, Nonce, PkceCodeChallenge, PkceCodeVerifier, RedirectUrl,
    RequestTokenError, Scope, StandardErrorResponse, StandardTokenResponse, TokenResponse,
};
use serde::{Deserialize, Serialize};
use sha2::Sha256;
use std::sync::Arc;

pub const STATE_COOKIE: &str = "__Host-wgc_oidc";
const STATE_TTL: Duration = Duration::minutes(10);
const DISCOVERY_TTL: Duration = Duration::hours(1);
const DISCOVERY_RETRY: Duration = Duration::seconds(30);
const MAC_CONTEXT: &[u8] = b"wgc-oidc-state-v1.";

#[derive(Clone, Debug, Serialize, Deserialize)]
pub struct ExtraClaims {
    #[serde(default, skip_serializing_if = "Option::is_none")]
    groups: Option<serde_json::Value>,
}

impl AdditionalClaims for ExtraClaims {}

type Fields = IdTokenFields<
    ExtraClaims,
    EmptyExtraTokenFields,
    CoreGenderClaim,
    CoreJweContentEncryptionAlgorithm,
    CoreJwsSigningAlgorithm,
>;

type OidcClient = Client<
    ExtraClaims,
    CoreAuthDisplay,
    CoreGenderClaim,
    CoreJweContentEncryptionAlgorithm,
    CoreJsonWebKey,
    CoreAuthPrompt,
    StandardErrorResponse<CoreErrorResponseType>,
    StandardTokenResponse<Fields, CoreTokenType>,
    CoreTokenIntrospectionResponse,
    CoreRevocableToken,
    CoreRevocationErrorResponse,
    EndpointSet,
    EndpointNotSet,
    EndpointNotSet,
    EndpointNotSet,
    EndpointMaybeSet,
    EndpointMaybeSet,
>;

#[derive(Clone, Copy, Debug, PartialEq)]
enum Fail {
    Failed,
    Unavailable,
    NotAllowed,
}

impl Fail {
    fn code(self) -> &'static str {
        match self {
            Fail::Failed => "sso_failed",
            Fail::Unavailable => "sso_unavailable",
            Fail::NotAllowed => "not_allowed",
        }
    }
}

enum Cached {
    Ready(DateTime<Utc>, Arc<OidcClient>),
    Failed(DateTime<Utc>),
}

/// HTTP client and the provider metadata, discovered lazily: cached for an hour, a failure for 30 s.
pub struct Provider {
    http: openidconnect::reqwest::Client,
    cached: tokio::sync::Mutex<Option<Cached>>,
}

impl Provider {
    pub fn new() -> Provider {
        let http = openidconnect::reqwest::ClientBuilder::new()
            .redirect(openidconnect::reqwest::redirect::Policy::none())
            .connect_timeout(std::time::Duration::from_secs(5))
            .timeout(std::time::Duration::from_secs(10))
            .build()
            .expect("http client");
        Provider {
            http,
            cached: tokio::sync::Mutex::new(None),
        }
    }

    async fn client(&self, st: &AppState, cfg: &OidcConfig) -> Result<Arc<OidcClient>, Fail> {
        let mut cached = self.cached.lock().await;
        let now = st.clock.now();
        match cached.as_ref() {
            Some(Cached::Ready(at, c)) if now - *at < DISCOVERY_TTL => return Ok(c.clone()),
            Some(Cached::Failed(at)) if now - *at < DISCOVERY_RETRY => {
                return Err(Fail::Unavailable);
            }
            _ => {}
        }
        let issuer = IssuerUrl::new(cfg.issuer_id.clone()).map_err(|_| Fail::Unavailable)?;
        let meta = match CoreProviderMetadata::discover_async(issuer, &self.http).await {
            Ok(m) => m,
            Err(e) => {
                eprintln!("oidc discovery failed: {}", chain(&e));
                *cached = Some(Cached::Failed(now));
                return Err(Fail::Unavailable);
            }
        };
        let redirect = format!(
            "{}/auth/oidc/callback",
            st.cfg.public_url.as_str().trim_end_matches('/')
        );
        let client = OidcClient::from_provider_metadata(
            meta,
            ClientId::new(cfg.client_id.clone()),
            Some(ClientSecret::new(cfg.client_secret.0.clone())), // leak-check: ok
        )
        .set_redirect_uri(RedirectUrl::new(redirect).map_err(|_| Fail::Unavailable)?);
        let client = Arc::new(client);
        *cached = Some(Cached::Ready(now, client.clone()));
        Ok(client)
    }

    async fn forget(&self) {
        *self.cached.lock().await = None;
    }
}

impl Default for Provider {
    fn default() -> Self {
        Provider::new()
    }
}

#[derive(Serialize, Deserialize)]
struct Pending {
    state: String,
    nonce: String,
    verifier: String,
    invite: Option<String>,
    exp: i64,
    /// Set when the app started the login: its S256 PKCE challenge for the one-time code.
    #[serde(default, skip_serializing_if = "Option::is_none")]
    app: Option<String>,
}

fn mac(key: &[u8; 32], payload: &str) -> Hmac<Sha256> {
    let mut m = <Hmac<Sha256> as KeyInit>::new_from_slice(key).expect("hmac key");
    m.update(MAC_CONTEXT);
    m.update(payload.as_bytes());
    m
}

fn seal(key: &[u8; 32], p: &Pending) -> String {
    let payload = URL_SAFE_NO_PAD.encode(serde_json::to_vec(p).expect("json"));
    let tag = mac(key, &payload).finalize().into_bytes();
    format!("{payload}.{}", URL_SAFE_NO_PAD.encode(tag))
}

fn open(key: &[u8; 32], raw: &str) -> Option<Pending> {
    let (payload, tag) = raw.split_once('.')?;
    let tag = URL_SAFE_NO_PAD.decode(tag).ok()?;
    mac(key, payload).verify_slice(&tag).ok()?;
    serde_json::from_slice(&URL_SAFE_NO_PAD.decode(payload).ok()?).ok()
}

fn same(a: &str, b: &str) -> bool {
    a.len() == b.len()
        && a.bytes()
            .zip(b.bytes())
            .fold(0u8, |acc, (x, y)| acc | (x ^ y))
            == 0
}

fn valid_invite(t: &str) -> bool {
    (1..=128).contains(&t.len())
        && t.bytes()
            .all(|b| b.is_ascii_alphanumeric() || b == b'_' || b == b'-')
}

fn state_cookie(value: &str, max_age: i64) -> String {
    format!("{STATE_COOKIE}={value}; HttpOnly; Secure; SameSite=Lax; Path=/; Max-Age={max_age}")
}

fn redirect(to: &str, cookies: &[String]) -> Response {
    let mut res = (StatusCode::FOUND, [(header::LOCATION, to.to_string())]).into_response();
    let h = res.headers_mut();
    h.insert(header::CACHE_CONTROL, HeaderValue::from_static("no-store"));
    for c in cookies {
        if let Ok(v) = HeaderValue::from_str(c) {
            h.append(header::SET_COOKIE, v);
        }
    }
    res
}

fn to_login(f: Fail, invite: Option<&str>, app: bool, cookies: &[String]) -> Response {
    if app {
        return redirect(
            &format!("{}?error={}", super::app::REDIRECT, f.code()),
            cookies,
        );
    }
    let to = match invite.filter(|t| valid_invite(t)) {
        Some(t) => format!("/login?error={}&invite={t}", f.code()),
        None => format!("/login?error={}", f.code()),
    };
    redirect(&to, cookies)
}

fn query_param(q: &Option<String>, name: &str) -> Option<String> {
    url::form_urlencoded::parse(q.as_deref().unwrap_or("").as_bytes())
        .find(|(k, _)| k == name)
        .map(|(_, v)| v.into_owned())
}

pub async fn start(State(st): State<AppState>, RawQuery(q): RawQuery) -> Response {
    let (Some(cfg), Some(p)) = (st.cfg.oidc.as_ref(), st.oidc.as_ref()) else {
        return StatusCode::NOT_FOUND.into_response();
    };
    let invite = query_param(&q, "invite");
    let app = match query_param(&q, "client").as_deref() {
        None | Some("web") => None,
        Some("android") => {
            match query_param(&q, "challenge").filter(|c| super::app::valid_challenge(c)) {
                Some(c) => Some(c),
                None => return StatusCode::BAD_REQUEST.into_response(),
            }
        }
        Some(_) => return StatusCode::BAD_REQUEST.into_response(),
    };
    let client = match p.client(&st, cfg).await {
        Ok(c) => c,
        Err(f) => return to_login(f, invite.as_deref(), app.is_some(), &[]),
    };
    let (challenge, verifier) = PkceCodeChallenge::new_random_sha256();
    let (url, state, nonce) = client
        .authorize_url(
            AuthenticationFlow::<CoreResponseType>::AuthorizationCode,
            CsrfToken::new_random, // leak-check: ok
            Nonce::new_random,
        )
        .add_scope(Scope::new("profile".into()))
        .add_scope(Scope::new("email".into()))
        .add_scope(Scope::new("groups".into()))
        .set_pkce_challenge(challenge)
        .url();
    let pending = Pending {
        state: state.secret().clone(),
        nonce: nonce.secret().clone(),
        verifier: verifier.secret().clone(),
        invite: invite.filter(|t| valid_invite(t)),
        exp: (st.clock.now() + STATE_TTL).timestamp(),
        app,
    };
    let cookie = state_cookie(&seal(&st.secret, &pending), STATE_TTL.num_seconds());
    redirect(url.as_str(), &[cookie])
}

pub async fn callback(
    State(st): State<AppState>,
    headers: HeaderMap,
    RawQuery(q): RawQuery,
) -> Response {
    let (Some(cfg), Some(p)) = (st.cfg.oidc.as_ref(), st.oidc.as_ref()) else {
        return StatusCode::NOT_FOUND.into_response();
    };
    let clear = state_cookie("", 0);
    let done = match finish(&st, cfg, p, &headers, &q).await {
        Ok((id, pending)) => signed_in(&st, &headers, id, pending).await,
        Err(f) => Err(f),
    };
    match done {
        Ok((target, Some(sid))) => redirect(&target, &[clear, session::set_cookie(&sid)]),
        Ok((target, None)) => redirect(&target, &[clear]),
        Err(f) => {
            let pending =
                super::cookie(&headers, STATE_COOKIE).and_then(|raw| open(&st.secret, &raw));
            let app = pending.as_ref().is_some_and(|p| p.app.is_some());
            let invite = pending.and_then(|p| p.invite);
            to_login(f, invite.as_deref(), app, &[clear])
        }
    }
}

/// The app gets a one-time code on its redirect URI; the browser gets the session cookie (returned as Some).
async fn signed_in(
    st: &AppState,
    headers: &HeaderMap,
    id: i64,
    pending: Pending,
) -> Result<(String, Option<String>), Fail> {
    if let Some(challenge) = pending.app {
        let code = super::app::issue_code(st, id, challenge)
            .await
            .map_err(|e| {
                eprintln!("oidc app login: storing the code failed: {e}");
                Fail::Failed
            })?;
        let mut target = format!("{}?code={code}", super::app::REDIRECT);
        if let Some(t) = pending.invite {
            target.push_str(&format!("&invite={t}"));
        }
        return Ok((target, None));
    }
    drop_incoming_session(st, headers).await.map_err(|e| {
        eprintln!("oidc login: dropping the old session failed: {}", e.code);
        Fail::Failed
    })?;
    let sid = session::create(&st.db, &*st.clock, id, SessionKind::Web)
        .await
        .map_err(|e| {
            eprintln!("oidc login: creating the session failed: {e}");
            Fail::Failed
        })?;
    let target = match pending.invite {
        Some(t) => format!("/invite/{t}"),
        None => "/".into(),
    };
    Ok((target, Some(sid)))
}

async fn finish(
    st: &AppState,
    cfg: &OidcConfig,
    p: &Provider,
    headers: &HeaderMap,
    q: &Option<String>,
) -> Result<(i64, Pending), Fail> {
    if query_param(q, "error").is_some() {
        return Err(Fail::Failed);
    }
    let pending = super::cookie(headers, STATE_COOKIE)
        .and_then(|raw| open(&st.secret, &raw))
        .ok_or(Fail::Failed)?;
    if st.clock.now().timestamp() >= pending.exp {
        return Err(Fail::Failed);
    }
    let state = query_param(q, "state").ok_or(Fail::Failed)?;
    if !same(&state, &pending.state) {
        return Err(Fail::Failed);
    }
    let code = query_param(q, "code").ok_or(Fail::Failed)?;
    let client = p.client(st, cfg).await?;
    let token = client // leak-check: ok
        .exchange_code(AuthorizationCode::new(code))
        .map_err(|e| {
            eprintln!("oidc token exchange not possible: {e}");
            Fail::Failed
        })?
        .set_pkce_verifier(PkceCodeVerifier::new(pending.verifier.clone()))
        .request_async(&p.http)
        .await
        .map_err(|e| {
            eprintln!("oidc token exchange failed: {}", chain(&e));
            match e {
                RequestTokenError::Request(_) => Fail::Unavailable, // leak-check: ok
                _ => Fail::Failed,
            }
        })?;
    let id_token = token.id_token().ok_or(Fail::Failed)?; // leak-check: ok
    let nonce = Nonce::new(pending.nonce.clone());
    let verify = |c: &OidcClient| {
        let clock = st.clock.clone();
        let verifier = c.id_token_verifier().set_time_fn(move || clock.now());
        id_token.claims(&verifier, &nonce)
    };
    let claims = match verify(&client) {
        Err(ClaimsVerificationError::SignatureVerification(e)) => {
            eprintln!(
                "oidc id token signature not verified ({e}), fetching the provider keys again"
            );
            p.forget().await;
            verify(&*p.client(st, cfg).await?)
        }
        other => other,
    }
    .map_err(|e| {
        eprintln!("oidc id token rejected: {e}");
        Fail::Failed
    })?;

    if let Some(required) = &cfg.required_group {
        let member = claims
            .additional_claims()
            .groups
            .as_ref()
            .and_then(|g| g.as_array())
            .is_some_and(|g| g.iter().any(|v| v.as_str() == Some(required.as_str())));
        if !member {
            return Err(Fail::NotAllowed);
        }
    }

    let preferred = claims
        .preferred_username()
        .map(|u| crate::model::clean_text(u.as_str()))
        .filter(|u| !u.is_empty());
    let name = claims
        .name()
        .and_then(|n| n.get(None))
        .map(|n| crate::model::clean_text(n.as_str()))
        .filter(|n| !n.is_empty());
    let display_name: String = name
        .or_else(|| preferred.clone())
        .unwrap_or_else(|| "SSO user".into())
        .chars()
        .take(60)
        .collect();
    let picture = claims
        .picture()
        .and_then(|p| p.get(None))
        .map(|p| p.as_str().to_string())
        .filter(|p| p.len() <= 2048)
        .and_then(|p| url::Url::parse(&p).ok())
        .filter(|u| u.scheme() == "https")
        .map(String::from);
    let username = preferred.as_deref().and_then(normalize_username);
    let user = User {
        issuer: cfg.issuer_id.clone(),
        subject: claims.subject().as_str().to_string(),
        display_name,
        picture,
        username,
        now: ts(st.clock.now()),
    };
    let id = st.db.run(move |c| upsert(c, user)).await.map_err(|e| {
        eprintln!("oidc user update failed: {e}");
        Fail::Failed
    })?;
    Ok((id, pending))
}

/// An error with its causes, e.g. "Request failed: client error: connection refused".
fn chain(e: &dyn std::error::Error) -> String {
    let mut out = e.to_string();
    let mut cur = e.source();
    while let Some(c) = cur {
        out.push_str(": ");
        out.push_str(&c.to_string());
        cur = c.source();
    }
    out
}

struct User {
    issuer: String,
    subject: String,
    display_name: String,
    picture: Option<String>,
    username: Option<String>,
    now: String,
}

fn upsert(c: &mut rusqlite::Connection, u: User) -> rusqlite::Result<i64> {
    let tx = c.transaction()?;
    tx.execute(
        "DELETE FROM identities WHERE kind = 'oidc' AND issuer = ?1 AND subject = ?2
         AND user_id IN (SELECT id FROM users WHERE former = 1)",
        rusqlite::params![u.issuer, u.subject],
    )?;
    let existing: Option<i64> = match tx.query_row(
        "SELECT user_id FROM identities WHERE kind = 'oidc' AND issuer = ?1 AND subject = ?2",
        rusqlite::params![u.issuer, u.subject],
        |r| r.get(0),
    ) {
        Ok(id) => Some(id),
        Err(rusqlite::Error::QueryReturnedNoRows) => None,
        Err(e) => return Err(e),
    };
    let username = match &u.username {
        Some(name) => {
            let taken: bool = tx.query_row(
                "SELECT EXISTS (SELECT 1 FROM users WHERE username = ?1 AND id IS NOT ?2)",
                rusqlite::params![name, existing],
                |r| r.get(0),
            )?;
            (!taken).then(|| name.clone())
        }
        None => None,
    };
    let id = match existing {
        Some(id) => {
            tx.execute(
                "UPDATE users SET display_name = ?2, picture_url = ?3, username = ?4 WHERE id = ?1",
                rusqlite::params![id, u.display_name, u.picture, username],
            )?;
            id
        }
        None => {
            tx.execute(
                "INSERT INTO users (username, display_name, picture_url, created_at) VALUES (?1, ?2, ?3, ?4)",
                rusqlite::params![username, u.display_name, u.picture, u.now],
            )?;
            let id = tx.last_insert_rowid();
            tx.execute(
                "INSERT INTO identities (user_id, kind, issuer, subject) VALUES (?1, 'oidc', ?2, ?3)",
                rusqlite::params![id, u.issuer, u.subject],
            )?;
            id
        }
    };
    tx.commit()?;
    Ok(id)
}

#[cfg(test)]
mod tests {
    use super::*;

    fn pending() -> Pending {
        Pending {
            state: "s".into(),
            nonce: "n".into(),
            verifier: "v".into(),
            invite: None,
            exp: 1,
            app: None,
        }
    }

    #[test]
    fn sealed_state_roundtrips_and_detects_tampering() {
        let key = [7u8; 32];
        let raw = seal(&key, &pending());
        assert_eq!(open(&key, &raw).unwrap().state, "s");
        assert!(open(&[8u8; 32], &raw).is_none());
        let (payload, tag) = raw.split_once('.').unwrap();
        let mut other = pending();
        other.state = "evil".into();
        let forged = URL_SAFE_NO_PAD.encode(serde_json::to_vec(&other).unwrap());
        assert!(open(&key, &format!("{forged}.{tag}")).is_none());
        assert!(open(&key, payload).is_none());
    }

    #[test]
    fn invite_tokens_limited_to_url_safe_chars() {
        assert!(valid_invite("abc_DEF-123"));
        assert!(!valid_invite("../evil"));
        assert!(!valid_invite(""));
        assert!(!valid_invite(&"a".repeat(129)));
    }
}
