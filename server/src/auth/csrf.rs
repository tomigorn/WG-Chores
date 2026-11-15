use crate::app::AppState;
use crate::error::ApiError;
use axum::{
    extract::{Request, State},
    http::{Method, header},
    middleware::Next,
    response::{IntoResponse, Response},
};

pub async fn check(State(st): State<AppState>, req: Request, next: Next) -> Response {
    if matches!(*req.method(), Method::GET | Method::HEAD | Method::OPTIONS) {
        return next.run(req).await;
    }
    let h = req.headers();
    if super::bearer(h).is_some() && !h.contains_key(header::COOKIE) {
        return next.run(req).await;
    }
    let header_ok = h.get("x-wgc").is_some_and(|v| v == "1");
    let origin_ok = match h.get(header::ORIGIN) {
        None => true,
        Some(o) => o
            .to_str()
            .is_ok_and(|o| o == st.cfg.public_url.origin().ascii_serialization()),
    };
    if header_ok && origin_ok {
        next.run(req).await
    } else {
        ApiError::forbidden("csrf", "Request rejected (missing or foreign origin).").into_response()
    }
}
