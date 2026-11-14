use axum::{body::Body, http::Request};
use std::sync::Arc;
use tower::ServiceExt;
use wg_chores::app::{AppState, router};
use wg_chores::config::Config;

#[tokio::test]
async fn healthz_returns_ok() {
    let cfg =
        Config::from_env(|k| (k == "PUBLIC_URL").then(|| "https://chores.example.com".to_string()))
            .unwrap();
    let app = router(AppState { cfg: Arc::new(cfg) });
    let res = app
        .oneshot(Request::get("/healthz").body(Body::empty()).unwrap())
        .await
        .unwrap();
    assert_eq!(res.status(), 200);
    let body = axum::body::to_bytes(res.into_body(), 1024).await.unwrap();
    assert_eq!(&body[..], b"ok");
}
