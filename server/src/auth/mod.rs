pub mod app;
pub mod confirm;
pub mod csrf;
pub mod hasher;
pub mod local;
pub mod oidc;
pub mod session;

use crate::app::AppState;
use crate::error::ApiError;
use axum::{
    extract::{ConnectInfo, FromRequest, FromRequestParts, Request},
    http::{HeaderMap, header, request::Parts},
};
use serde::{Deserialize, Serialize, de::DeserializeOwned};
use std::net::{IpAddr, SocketAddr};

#[derive(Clone, Copy, Debug, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "lowercase")]
pub enum AccountKind {
    Local,
    Sso,
}

#[derive(Clone, Debug)]
pub struct CurrentUser {
    pub id: i64,
    pub display_name: String,
    pub kind: AccountKind,
    pub session_hash: String,
    /// Signed in with an app token in `Authorization: Bearer`, not the session cookie.
    pub bearer: bool,
}

pub(crate) fn session_cookie(headers: &HeaderMap) -> Option<String> {
    cookie(headers, session::COOKIE_NAME)
}

/// The token of `Authorization: Bearer <token>`; other schemes (e.g. Basic from a proxy) are ignored.
pub(crate) fn bearer(headers: &HeaderMap) -> Option<String> {
    let v = headers.get(header::AUTHORIZATION)?.to_str().ok()?;
    let (scheme, token) = v.split_once(' ').unwrap_or((v, ""));
    scheme
        .eq_ignore_ascii_case("bearer")
        .then(|| token.trim().to_string())
}

/// The session token of a request and whether it came as bearer; both cookie and bearer is refused.
pub(crate) fn credential(headers: &HeaderMap) -> Result<Option<(String, bool)>, ApiError> {
    match (session_cookie(headers), bearer(headers)) {
        (Some(_), Some(_)) => Err(ApiError::bad_request(
            "ambiguous_auth",
            "Send either the session cookie or a bearer token, not both.",
        )),
        (Some(c), None) => Ok(Some((c, false))),
        (None, Some(b)) => Ok(Some((b, true))),
        (None, None) => Ok(None),
    }
}

pub(crate) fn cookie(headers: &HeaderMap, name: &str) -> Option<String> {
    let prefix = format!("{name}=");
    headers
        .get_all(header::COOKIE)
        .iter()
        .filter_map(|v| v.to_str().ok())
        .flat_map(|v| v.split(';'))
        .find_map(|c| c.trim().strip_prefix(prefix.as_str()).map(str::to_string))
}

impl FromRequestParts<AppState> for CurrentUser {
    type Rejection = ApiError;

    async fn from_request_parts(parts: &mut Parts, st: &AppState) -> Result<Self, ApiError> {
        let (raw, bearer) = credential(&parts.headers)?.ok_or_else(ApiError::unauthenticated)?;
        if raw.is_empty() {
            return Err(ApiError::unauthenticated());
        }
        let kind = if bearer {
            session::SessionKind::Android
        } else {
            session::SessionKind::Web
        };
        session::lookup(&st.db, &*st.clock, &raw, st.cfg.local_accounts, kind)
            .await?
            .ok_or_else(ApiError::unauthenticated)
    }
}

/// Client address: the socket peer, or the rightmost X-Forwarded-For entry that is not a trusted proxy when the peer is one; an unparseable entry ends the walk at the last trusted hop.
#[derive(Clone, Copy, Debug)]
pub struct ClientIp(pub Option<IpAddr>);

impl FromRequestParts<AppState> for ClientIp {
    type Rejection = std::convert::Infallible;

    async fn from_request_parts(parts: &mut Parts, st: &AppState) -> Result<Self, Self::Rejection> {
        let peer = parts
            .extensions
            .get::<ConnectInfo<SocketAddr>>()
            .map(|c| c.0.ip().to_canonical());
        let trusted = |ip: IpAddr| st.cfg.trusted_proxies.iter().any(|n| n.contains(ip));
        let Some(peer) = peer else {
            return Ok(ClientIp(None));
        };
        if !trusted(peer) {
            return Ok(ClientIp(Some(peer)));
        }
        let entries: Vec<Option<IpAddr>> = parts
            .headers
            .get_all("x-forwarded-for")
            .iter()
            .flat_map(|v| match v.to_str() {
                Ok(s) => s
                    .split(',')
                    .map(|e| e.trim().parse::<IpAddr>().ok())
                    .collect(),
                Err(_) => vec![None],
            })
            .collect();
        let mut last_trusted = peer;
        for entry in entries.into_iter().rev() {
            let Some(ip) = entry.map(|i| i.to_canonical()) else {
                break;
            };
            if !trusted(ip) {
                return Ok(ClientIp(Some(ip)));
            }
            last_trusted = ip;
        }
        Ok(ClientIp(Some(last_trusted)))
    }
}

pub struct ApiJson<T>(pub T);

impl<T: DeserializeOwned, S: Send + Sync> FromRequest<S> for ApiJson<T> {
    type Rejection = ApiError;

    async fn from_request(req: Request, state: &S) -> Result<Self, ApiError> {
        axum::Json::<T>::from_request(req, state)
            .await
            .map(|j| ApiJson(j.0))
            .map_err(|_| ApiError::bad_request("invalid_body", "The request body is not valid."))
    }
}
