pub mod csrf;
pub mod local;
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
}

pub(crate) fn session_cookie(headers: &HeaderMap) -> Option<String> {
    let prefix = format!("{}=", session::COOKIE_NAME);
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
        let raw = session_cookie(&parts.headers).ok_or_else(ApiError::unauthenticated)?;
        session::lookup(&st.db, &*st.clock, &raw, st.cfg.local_accounts)
            .await?
            .ok_or_else(ApiError::unauthenticated)
    }
}

/// Client address: the socket peer, or the rightmost X-Forwarded-For entry that is not a trusted proxy when the peer is one.
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
        let hops: Vec<IpAddr> = parts
            .headers
            .get_all("x-forwarded-for")
            .iter()
            .filter_map(|v| v.to_str().ok())
            .flat_map(|v| v.split(','))
            .filter_map(|s| s.trim().parse::<IpAddr>().ok().map(|i| i.to_canonical()))
            .collect();
        let client = hops.into_iter().rev().find(|ip| !trusted(*ip));
        Ok(ClientIp(Some(client.unwrap_or(peer))))
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
