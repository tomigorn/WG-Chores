use wg_chores::{
    app::{AppState, router},
    config::Config,
    db::Db,
};

#[tokio::main]
async fn main() {
    let args: Vec<String> = std::env::args().skip(1).collect();
    let cmd = match wg_chores::cli::parse(&args) {
        Ok(c) => c,
        Err(u) => {
            eprintln!("error: {}\n{}\n{}", u.message, u.usage, u.hint);
            std::process::exit(2);
        }
    };
    match cmd {
        wg_chores::cli::Command::Serve => serve().await,
        cmd => admin(cmd).await,
    }
}

async fn admin(cmd: wg_chores::cli::Command) {
    use wg_chores::cli::{Command, Ctx};
    let help = matches!(cmd, Command::Help(_));
    let name = match &cmd {
        Command::MigrateV1 { .. } => "migrate-v1",
        Command::Backup { .. } => "backup",
        Command::CreateUser { .. } => "create-user",
        Command::DemoReset => "demo-reset",
        Command::Healthcheck => "healthcheck",
        Command::RevokeSessions { .. } => "revoke-sessions",
        _ => "",
    };
    let ctx = Ctx {
        get: Box::new(|k| std::env::var(k).ok()),
        hasher: wg_chores::auth::local::Hasher::new(),
        now: chrono::Utc::now(),
        prompt_password: Box::new(prompt_password), // leak-check: ok
    };
    match wg_chores::cli::run(cmd, ctx).await {
        Ok(out) => println!("{out}"),
        Err(e) if help => eprintln!("{e}"),
        Err(e) => {
            eprintln!("error: {e}\nNext: wg-chores {name} --help");
            std::process::exit(1);
        }
    }
}

fn prompt_password() -> Result<String, String> {
    let first =
        rpassword::prompt_password("Password: ") // leak-check: ok
            .map_err(|e| format!("cannot read the password: {e}; use --password-file"))?;
    let second = rpassword::prompt_password("Repeat password: ").map_err(|e| e.to_string())?; // leak-check: ok
    if first == second {
        Ok(first)
    } else {
        Err("the passwords do not match".into())
    }
}

async fn serve() {
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
    let vapid_path = cfg.data_dir.join("vapid.key");
    let vapid = match wg_chores::notify::webpush::Vapid::load_or_create(&vapid_path) {
        Ok(v) => v,
        Err(e) => {
            eprintln!("cannot use {}: {e}", vapid_path.display());
            std::process::exit(2);
        }
    };
    let addr = cfg.listen;
    let listener = tokio::net::TcpListener::bind(addr).await.expect("bind");
    eprintln!("listening on {addr}, public url {}", cfg.public_url);
    let mut state = AppState::new(cfg, db);
    state.secret = std::sync::Arc::new(secret); // leak-check: ok
    state.push = std::sync::Arc::new(wg_chores::notify::Push::new(vapid));
    wg_chores::notify::scheduler::spawn(state.clone());
    let push = state.push.clone();
    let app = router(state);
    axum::serve(
        listener,
        app.into_make_service_with_connect_info::<std::net::SocketAddr>(),
    )
    .with_graceful_shutdown(shutdown_signal())
    .await
    .expect("serve");
    let _ = tokio::time::timeout(std::time::Duration::from_secs(10), push.wait_idle()).await;
}

async fn shutdown_signal() {
    let mut term = tokio::signal::unix::signal(tokio::signal::unix::SignalKind::terminate())
        .expect("sigterm handler");
    tokio::select! {
        _ = tokio::signal::ctrl_c() => {}
        _ = term.recv() => {}
    }
}
