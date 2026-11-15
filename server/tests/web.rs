mod support;

use axum::{
    body::Body,
    http::{HeaderMap, Method, Request, header},
};
use serde_json::Value;
use support::{ORIGIN, TestApp};
use tower::ServiceExt;

async fn raw(app: &TestApp, method: Method, path: &str) -> (u16, HeaderMap, Vec<u8>) {
    let res = app
        .router
        .clone()
        .oneshot(
            Request::builder()
                .method(method)
                .uri(path)
                .header(header::ORIGIN, ORIGIN)
                .header("x-wgc", "1")
                .body(Body::empty())
                .unwrap(),
        )
        .await
        .unwrap();
    let (status, headers) = (res.status().as_u16(), res.headers().clone());
    let body = axum::body::to_bytes(res.into_body(), 1 << 24)
        .await
        .unwrap();
    (status, headers, body.to_vec())
}

fn h<'a>(headers: &'a HeaderMap, name: &str) -> &'a str {
    headers.get(name).map(|v| v.to_str().unwrap()).unwrap_or("")
}

#[tokio::test]
async fn spa_fallback_serves_index() {
    let app = TestApp::new();
    for path in ["/", "/households/3", "/invite/abc123", "/settings/look"] {
        let (status, headers, body) = raw(&app, Method::GET, path).await;
        assert_eq!(status, 200, "{path}");
        assert!(
            h(&headers, "content-type").starts_with("text/html"),
            "{path}"
        );
        assert_eq!(h(&headers, "cache-control"), "no-cache", "{path}");
        assert!(String::from_utf8_lossy(&body).contains("<html"), "{path}");
    }
    let (status, _, body) = raw(&app, Method::HEAD, "/households/3").await;
    assert_eq!(status, 200);
    assert!(body.is_empty());
}

#[tokio::test]
async fn api_404_stays_json() {
    let app = TestApp::new();
    for path in [
        "/api/v1/nope",
        "/api/nothing",
        "/api",
        "/auth",
        "/auth/nope",
        "/healthz/x",
    ] {
        let (status, headers, body) = raw(&app, Method::GET, path).await;
        assert_eq!(status, 404, "{path}");
        assert!(
            h(&headers, "content-type").starts_with("application/json"),
            "{path}"
        );
        let v: Value = serde_json::from_slice(&body).unwrap();
        assert_eq!(v["error"], "not_found");
    }
    let (status, headers, _) = raw(&app, Method::GET, "/assets/missing-abc.js").await;
    assert_eq!(status, 404);
    assert!(!h(&headers, "content-type").starts_with("text/html"));
    let (status, _, _) = raw(&app, Method::POST, "/households/3").await;
    assert_eq!(status, 404);
}

#[tokio::test]
async fn asset_cache_headers() {
    let app = TestApp::new();
    let paths = wg_chores::web::embedded_paths();
    let asset = paths
        .iter()
        .find(|p| p.starts_with("assets/"))
        .expect("an asset");
    let (status, headers, _) = raw(&app, Method::GET, &format!("/{asset}")).await;
    assert_eq!(status, 200);
    assert_eq!(
        h(&headers, "cache-control"),
        "public, max-age=31536000, immutable"
    );
    assert!(!h(&headers, "content-type").starts_with("text/html"));
    for p in ["sw.js", "manifest.webmanifest", "index.html"] {
        let (status, headers, _) = raw(&app, Method::GET, &format!("/{p}")).await;
        assert_eq!(status, 200, "{p}");
        assert_eq!(h(&headers, "cache-control"), "no-cache", "{p}");
    }
    let (_, headers, _) = raw(&app, Method::GET, "/sw.js").await;
    assert!(h(&headers, "content-type").contains("javascript"));
    let (_, headers, _) = raw(&app, Method::GET, "/manifest.webmanifest").await;
    assert_eq!(h(&headers, "content-type"), "application/manifest+json");
}

#[tokio::test]
async fn csp_has_no_unsafe() {
    let app = TestApp::new();
    for path in [
        "/",
        "/healthz",
        "/api/v1/config",
        "/api/v1/me",
        "/api/v1/nope",
        "/assets/x.js",
        "/auth/oidc/start",
    ] {
        let (_, headers, _) = raw(&app, Method::GET, path).await;
        let csp = h(&headers, "content-security-policy");
        for want in [
            "default-src 'self'",
            "script-src 'self'",
            "style-src 'self'",
            "img-src 'self' data: https:",
            "connect-src 'self'",
            "worker-src 'self'",
            "manifest-src 'self'",
            "frame-ancestors 'none'",
            "base-uri 'none'",
            "form-action 'self'",
        ] {
            assert!(csp.contains(want), "{path}: {csp}");
        }
        assert!(!csp.contains("unsafe"), "{path}: {csp}");
        assert_eq!(h(&headers, "x-content-type-options"), "nosniff", "{path}");
        assert_eq!(
            h(&headers, "referrer-policy"),
            "strict-origin-when-cross-origin"
        );
        assert_eq!(h(&headers, "x-frame-options"), "DENY");
        assert_eq!(h(&headers, "cross-origin-opener-policy"), "same-origin");
        assert!(h(&headers, "permissions-policy").contains("camera=()"));
        assert!(headers.get("strict-transport-security").is_none());
    }
}

#[tokio::test]
async fn api_json_is_not_cached() {
    let app = TestApp::new();
    let (_, headers, _) = raw(&app, Method::GET, "/api/v1/config").await;
    assert_eq!(h(&headers, "cache-control"), "no-store");
    let (_, headers, _) = raw(&app, Method::GET, "/api/v1/nope").await;
    assert_eq!(h(&headers, "cache-control"), "no-store");
}

#[tokio::test]
async fn text_assets_are_gzipped() {
    let app = TestApp::new();
    let res = app
        .router
        .clone()
        .oneshot(
            Request::get("/")
                .header(header::ACCEPT_ENCODING, "gzip")
                .body(Body::empty())
                .unwrap(),
        )
        .await
        .unwrap();
    assert_eq!(res.headers()[header::CONTENT_ENCODING], "gzip");
    let res = app
        .router
        .clone()
        .oneshot(
            Request::get("/api/v1/config")
                .header(header::ACCEPT_ENCODING, "gzip")
                .body(Body::empty())
                .unwrap(),
        )
        .await
        .unwrap();
    assert!(res.headers().get(header::CONTENT_ENCODING).is_none());
}

#[test]
fn only_text_types_are_compressed() {
    use tower_http::compression::predicate::Predicate;
    let p = wg_chores::web::text_only();
    let with = |ct: &str| {
        axum::http::Response::builder()
            .header(header::CONTENT_TYPE, ct)
            .body(Body::from(vec![0u8; 4096]))
            .unwrap()
    };
    for yes in [
        "text/html; charset=utf-8",
        "text/javascript; charset=utf-8",
        "text/css; charset=utf-8",
        "application/json",
        "image/svg+xml",
        "application/manifest+json",
    ] {
        assert!(p.should_compress(&with(yes)), "{yes}");
    }
    for no in [
        "image/png",
        "image/webp",
        "font/woff2",
        "application/octet-stream",
        "image/x-icon",
    ] {
        assert!(!p.should_compress(&with(no)), "{no}");
    }
}

fn source_routes() -> Vec<String> {
    let root = env!("CARGO_MANIFEST_DIR");
    let mut out = vec![];
    for f in ["src/app.rs", "src/api/mod.rs"] {
        let src = std::fs::read_to_string(format!("{root}/{f}")).unwrap();
        let mut rest = src.as_str();
        while let Some(i) = rest.find(".route(") {
            rest = &rest[i + 7..];
            let rest_trim = rest.trim_start();
            let q = rest_trim.strip_prefix('"').expect("route path literal");
            out.push(q[..q.find('"').unwrap()].to_string());
        }
    }
    out.sort();
    out.dedup();
    out
}

#[tokio::test]
async fn openapi_lists_all_routes() {
    let app = TestApp::new();
    let (status, headers, body) = raw(&app, Method::GET, "/api/v1/openapi.json").await;
    assert_eq!(status, 200);
    assert!(h(&headers, "content-type").starts_with("application/json"));
    let doc: Value = serde_json::from_slice(&body).unwrap();
    assert!(doc["openapi"].as_str().unwrap().starts_with("3."));
    let paths = doc["paths"].as_object().unwrap();

    let mut documented: Vec<String> = paths.keys().cloned().collect();
    documented.sort();
    assert_eq!(documented, source_routes(), "router paths vs openapi paths");

    for (path, item) in paths {
        let concrete = path
            .split('/')
            .map(|s| if s.starts_with('{') { "1" } else { s })
            .collect::<Vec<_>>()
            .join("/");
        for m in ["get", "post", "put", "patch", "delete"] {
            let (status, _, _) = raw(&app, m.to_uppercase().parse().unwrap(), &concrete).await;
            let routed = status != 405;
            assert_eq!(routed, item.get(m).is_some(), "{m} {path}");
        }
    }
}

#[test]
fn openapi_documents_retry_logout_body_and_limits() {
    let doc: Value =
        serde_json::from_str(include_str!("../openapi.json")).unwrap();
    let p = &doc["paths"];
    let create = &p["/api/v1/chores/{id}/completions"]["post"];
    assert!(create["responses"]["200"].is_object());
    assert!(create["responses"]["201"].is_object());
    let note = |op: &Value| op["requestBody"]["content"]["application/json"]["schema"]["properties"]["note"]["nullable"].clone();
    assert_eq!(note(create), true);
    assert_eq!(note(&p["/api/v1/completions/{id}"]["patch"]), true);
    let logout = &p["/api/v1/auth/logout"]["post"]["requestBody"];
    assert_eq!(logout["required"], false);
    assert!(logout["content"]["application/json"]["schema"]["properties"]["push_endpoint"].is_object());
    assert!(p["/api/v1/households/{id}/members"]["post"]["responses"]["429"].is_object());
    assert!(p["/api/v1/auth/login"]["post"]["responses"]["503"].is_object());
}
