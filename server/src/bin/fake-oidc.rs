use wg_chores::fake_oidc::{CLIENT_ID, CLIENT_SECRET, FakeOidc};

const HELP: &str = "Usage: fake-oidc [PORT]\n\nFake OpenID provider for tests, on 127.0.0.1 (port from the argument or FAKE_OIDC_PORT, default 8081).\nPOST /_test/user with {sub, name, preferred_username, picture, email, groups} sets the next user.\n";

#[tokio::main]
async fn main() {
    let arg = std::env::args().nth(1);
    if matches!(arg.as_deref(), Some("-h" | "--help")) {
        print!("{HELP}");
        return;
    }
    let port = arg
        .or_else(|| std::env::var("FAKE_OIDC_PORT").ok())
        .unwrap_or_else(|| "8081".into());
    let Ok(port) = port.trim().parse::<u16>() else {
        eprintln!("invalid port {port}\n\n{HELP}");
        std::process::exit(2);
    };
    let fake = match FakeOidc::bind(([127, 0, 0, 1], port).into()).await {
        Ok(f) => f,
        Err(e) => {
            eprintln!("cannot bind 127.0.0.1:{port}: {e}");
            std::process::exit(2);
        }
    };
    println!(
        "OIDC_ISSUER={} OIDC_CLIENT_ID={CLIENT_ID} OIDC_CLIENT_SECRET={CLIENT_SECRET}", // leak-check: ok
        fake.issuer()
    );
    tokio::signal::ctrl_c().await.ok();
}
