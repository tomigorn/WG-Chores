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
    let secret_path = cfg.data_dir.join("secret.key"); // leak-check: ok
    #[rustfmt::skip]
    let secret = match wg_chores::secret::load_or_create(&secret_path) { // leak-check: ok
        Ok(s) => s,
        Err(e) => {
            eprintln!("cannot use {}: {e}", secret_path.display());
            std::process::exit(2);
        }
    };
    let addr = cfg.listen;
    let listener = tokio::net::TcpListener::bind(addr).await.expect("bind");
    eprintln!("listening on {addr}, public url {}", cfg.public_url);
    let mut state = AppState::new(cfg, db);
    state.secret = std::sync::Arc::new(secret); // leak-check: ok
    let app = router(state);
    axum::serve(
        listener,
        app.into_make_service_with_connect_info::<std::net::SocketAddr>(),
    )
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
