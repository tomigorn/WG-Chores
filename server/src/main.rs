use std::sync::Arc;
use wg_chores::{app::{AppState, router}, config::Config};

#[tokio::main]
async fn main() {
    let cfg = match Config::from_env(|k| std::env::var(k).ok()) {
        Ok(c) => c,
        Err(e) => {
            eprintln!("config error: {e}");
            std::process::exit(2);
        }
    };
    let addr = std::env::var("LISTEN").unwrap_or_else(|_| "0.0.0.0:8080".into());
    let listener = tokio::net::TcpListener::bind(&addr).await.expect("bind");
    eprintln!("listening on {addr}, public url {}", cfg.public_url);
    let app = router(AppState { cfg: Arc::new(cfg) });
    axum::serve(listener, app)
        .with_graceful_shutdown(async { tokio::signal::ctrl_c().await.ok(); })
        .await
        .expect("serve");
}
