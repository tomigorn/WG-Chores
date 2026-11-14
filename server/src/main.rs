use std::sync::Arc;
use wg_chores::{
    app::{AppState, router},
    config::Config,
    db::Db,
};

#[tokio::main]
async fn main() {
    let cfg = match Config::from_env(|k| std::env::var(k).ok()) {
        Ok(c) => c,
        Err(e) => {
            eprintln!("config error: {e}");
            std::process::exit(2);
        }
    };
    if let Err(e) = std::fs::create_dir_all(&cfg.data_dir) {
        eprintln!("cannot create {}: {e}", cfg.data_dir.display());
        std::process::exit(2);
    }
    let db = match Db::open(&cfg.data_dir.join("wg-chores.db")) {
        Ok(d) => d,
        Err(e) => {
            eprintln!("{e}");
            std::process::exit(2);
        }
    };
    let addr = cfg.listen;
    let listener = tokio::net::TcpListener::bind(addr).await.expect("bind");
    eprintln!("listening on {addr}, public url {}", cfg.public_url);
    let app = router(AppState {
        cfg: Arc::new(cfg),
        db,
    });
    axum::serve(listener, app)
        .with_graceful_shutdown(shutdown_signal())
        .await
        .expect("serve");
}

async fn shutdown_signal() {
    let mut term = tokio::signal::unix::signal(tokio::signal::unix::SignalKind::terminate())
        .expect("sigterm handler");
    tokio::select! {
        _ = tokio::signal::ctrl_c() => {}
        _ = term.recv() => {}
    }
}
