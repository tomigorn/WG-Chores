use crate::error::ApiError;
use axum::{
    http::{HeaderValue, Method, StatusCode, Uri, header},
    response::{IntoResponse, Response},
};

mod embedded {
    include!(concat!(env!("OUT_DIR"), "/embedded.rs"));
}

#[derive(Clone, Copy)]
pub struct TextOnly;

pub fn text_only() -> TextOnly {
    TextOnly
}

impl tower_http::compression::predicate::Predicate for TextOnly {
    fn should_compress<B>(&self, response: &axum::http::Response<B>) -> bool
    where
        B: axum::body::HttpBody,
    {
        let ct = response
            .headers()
            .get(header::CONTENT_TYPE)
            .and_then(|v| v.to_str().ok())
            .unwrap_or("");
        let essence = ct.split(';').next().unwrap_or("").trim();
        essence.starts_with("text/")
            || matches!(
                essence,
                "application/json" | "application/manifest+json" | "image/svg+xml"
            )
    }
}

const IMMUTABLE: &str = "public, max-age=31536000, immutable";

pub fn embedded_paths() -> Vec<&'static str> {
    embedded::FILES.iter().map(|(p, _)| *p).collect()
}

fn find(path: &str) -> Option<&'static [u8]> {
    embedded::FILES
        .iter()
        .find(|(p, _)| *p == path)
        .map(|(_, b)| *b)
}

fn mime(path: &str) -> &'static str {
    match path.rsplit('.').next().unwrap_or("") {
        "html" => "text/html; charset=utf-8",
        "js" | "mjs" => "text/javascript; charset=utf-8",
        "css" => "text/css; charset=utf-8",
        "json" | "map" => "application/json",
        "webmanifest" => "application/manifest+json",
        "svg" => "image/svg+xml",
        "png" => "image/png",
        "jpg" | "jpeg" => "image/jpeg",
        "webp" => "image/webp",
        "ico" => "image/x-icon",
        "woff2" => "font/woff2",
        "txt" => "text/plain; charset=utf-8",
        _ => "application/octet-stream",
    }
}

fn cache_control(path: &str) -> &'static str {
    if path.starts_with("assets/") {
        IMMUTABLE
    } else if matches!(path, "index.html" | "sw.js" | "manifest.webmanifest") {
        "no-cache"
    } else {
        "public, max-age=86400"
    }
}

fn file(path: &str, body: &'static [u8], head: bool) -> Response {
    let mut res = (if head { &[][..] } else { body }).into_response();
    let h = res.headers_mut();
    h.insert(header::CONTENT_TYPE, HeaderValue::from_static(mime(path)));
    h.insert(
        header::CACHE_CONTROL,
        HeaderValue::from_static(cache_control(path)),
    );
    if head {
        h.insert(header::CONTENT_LENGTH, HeaderValue::from(body.len()));
    }
    res
}

fn not_found() -> Response {
    ApiError::not_found("not_found", "Not found.").into_response()
}

/// Router fallback: embedded file, else index.html for browser routes.
pub async fn serve(method: Method, uri: Uri) -> Response {
    let head = method == Method::HEAD;
    if !head && method != Method::GET {
        return not_found();
    }
    let path = uri.path().trim_start_matches('/');
    let api = ["api", "auth", "healthz"].iter().any(|p| {
        path.strip_prefix(p)
            .is_some_and(|r| r.is_empty() || r.starts_with('/'))
    }) || path.starts_with("healthz");
    if api {
        return not_found();
    }
    if !path.is_empty()
        && let Some(body) = find(path)
    {
        return file(path, body, head);
    }
    if path.starts_with("assets/") || path.rsplit('/').next().is_some_and(|s| s.contains('.')) {
        return StatusCode::NOT_FOUND.into_response();
    }
    match find("index.html") {
        Some(body) => file("index.html", body, head),
        None => not_found(),
    }
}
