use chrono::{NaiveDate, TimeZone, Utc};
use std::collections::{HashMap, HashSet};
use std::path::PathBuf;
use wg_chores::auth::hasher::Hasher;
use wg_chores::cli::{self, Command, Ctx, Who};
use wg_chores::db::Db;
use wg_chores::domain::{DueLevel, Interval, due_level, interval_days};

struct Scratch(PathBuf);

impl Scratch {
    fn new(name: &str) -> Scratch {
        let d = std::env::temp_dir().join(format!("wgc-adm-{name}-{}", std::process::id()));
        let _ = std::fs::remove_dir_all(&d);
        std::fs::create_dir_all(&d).unwrap();
        Scratch(d)
    }
    fn path(&self, f: &str) -> PathBuf {
        self.0.join(f)
    }
    fn s(&self, f: &str) -> String {
        self.path(f).to_string_lossy().into_owned()
    }
}

impl Drop for Scratch {
    fn drop(&mut self) {
        let _ = std::fs::remove_dir_all(&self.0);
    }
}

fn ctx(env: &[(&str, String)], password: &'static str) -> Ctx {
    let m: HashMap<String, String> = env
        .iter()
        .map(|(k, v)| (k.to_string(), v.clone()))
        .collect();
    Ctx {
        get: Box::new(move |k| m.get(k).cloned()),
        hasher: Hasher::insecure_for_tests(),
        now: Utc.with_ymd_and_hms(2026, 6, 15, 10, 0, 0).unwrap(),
        prompt_password: Box::new(move || Ok(password.to_string())), // leak-check: ok
    }
}

fn args(a: &[&str]) -> Result<Command, cli::Usage> {
    cli::parse(&a.iter().map(|s| s.to_string()).collect::<Vec<_>>())
}

fn db_path(d: &Scratch) -> PathBuf {
    d.path("data/wg-chores.db")
}

fn data_env(d: &Scratch) -> Vec<(&'static str, String)> {
    vec![("DATA_DIR", d.s("data")), ("TZ", "Europe/Zurich".into())]
}

fn demo_env(d: &Scratch) -> Vec<(&'static str, String)> {
    let pw = d.path("demo.pw");
    std::fs::write(&pw, "demo-password-123\n").unwrap();
    let mut e = data_env(d);
    e.push(("DEMO_MODE", "true".into()));
    e.push(("DEMO_PASSWORD_FILE", pw.to_string_lossy().into_owned()));
    e
}

fn sample_rows(path: &std::path::Path) -> (i64, i64, i64, i64) {
    let c = rusqlite::Connection::open(path).unwrap();
    let n = |sql: &str| c.query_row(sql, [], |r| r.get::<_, i64>(0)).unwrap();
    (
        n("SELECT count(*) FROM users"),
        n("SELECT count(*) FROM households"),
        n("SELECT count(*) FROM chores"),
        n("SELECT count(*) FROM categories"),
    )
}

#[tokio::test]
async fn demo_reset_is_repeatable() {
    let d = Scratch::new("demo");
    let env = demo_env(&d);
    cli::run(Command::DemoReset, ctx(&env, "x")).await.unwrap();
    let db = Db::open(&db_path(&d)).unwrap();
    let first = sample_rows(&db_path(&d));
    db.run(|c| {
        c.execute(
            "INSERT INTO users (username, display_name, created_at) VALUES ('stranger', 's', 'x')",
            [],
        )
    })
    .await
    .unwrap();
    drop(db);
    cli::run(Command::DemoReset, ctx(&env, "x")).await.unwrap();
    let db = Db::open(&db_path(&d)).unwrap();
    let second = sample_rows(&db_path(&d));
    assert_eq!(first, second);
    assert_eq!((second.0, second.1, second.3), (1, 1, 4));
    assert!(second.2 >= 10);

    let (hash, name, hh): (String, String, String) = db
        .run(|c| {
            c.query_row(
                "SELECT i.password_hash, u.username, h.name FROM users u
                 JOIN identities i ON i.user_id = u.id
                 JOIN memberships m ON m.user_id = u.id JOIN households h ON h.id = m.household_id",
                [],
                |r| Ok((r.get(0)?, r.get(1)?, r.get(2)?)),
            )
        })
        .await
        .unwrap();
    assert_eq!((name.as_str(), hh.as_str()), ("reviewer", "Demo WG"));
    assert!(
        Hasher::insecure_for_tests()
            .verify("demo-password-123".into(), Some(hash))
            .await
            .unwrap()
    );

    let rows: Vec<(Option<String>, Option<String>)> = db
        .run(|c| {
            let mut s = c.prepare(
                "SELECT ch.interval, (SELECT max(done_on) FROM completions co WHERE co.chore_id = ch.id AND co.deleted_at IS NULL)
                 FROM chores ch",
            )?;
            s.query_map([], |r| Ok((r.get(0)?, r.get(1)?)))?.collect()
        })
        .await
        .unwrap();
    let today = NaiveDate::from_ymd_opt(2026, 6, 15).unwrap();
    let levels: HashSet<String> = rows
        .iter()
        .map(|(iv, last)| {
            let since = last
                .as_ref()
                .map(|d| (today - d.parse::<NaiveDate>().unwrap()).num_days());
            format!(
                "{:?}",
                due_level(
                    since,
                    interval_days(iv.as_deref().and_then(Interval::from_code)),
                )
            )
        })
        .collect();
    for want in [
        DueLevel::Fresh,
        DueLevel::Soon,
        DueLevel::Close,
        DueLevel::Due,
        DueLevel::Overdue,
        DueLevel::LongOverdue,
        DueLevel::Never,
    ] {
        assert!(levels.contains(&format!("{want:?}")), "{want:?} missing");
    }
}

#[tokio::test]
async fn demo_reset_refused_without_demo_mode() {
    let d = Scratch::new("demo-off");
    let mut env = demo_env(&d);
    env.retain(|(k, _)| *k != "DEMO_MODE");
    let e = cli::run(Command::DemoReset, ctx(&env, "x"))
        .await
        .unwrap_err();
    assert!(e.contains("DEMO_MODE=true"), "{e}");
    assert!(!db_path(&d).exists());
    env.push(("DEMO_MODE", "false".into()));
    assert!(cli::run(Command::DemoReset, ctx(&env, "x")).await.is_err());
}

#[tokio::test]
async fn demo_reset_needs_a_good_password() {
    let d = Scratch::new("demo-pw");
    let mut env = demo_env(&d);
    std::fs::write(d.path("demo.pw"), "short\n").unwrap();
    let e = cli::run(Command::DemoReset, ctx(&env, "x"))
        .await
        .unwrap_err();
    assert!(e.contains("12"), "{e}");
    env.retain(|(k, _)| *k != "DEMO_PASSWORD_FILE");
    let e = cli::run(Command::DemoReset, ctx(&env, "x"))
        .await
        .unwrap_err();
    assert!(e.contains("DEMO_PASSWORD_FILE"), "{e}");
}

#[tokio::test]
async fn backup_produces_openable_db() {
    let d = Scratch::new("backup");
    let env = demo_env(&d);
    cli::run(Command::DemoReset, ctx(&env, "x")).await.unwrap();
    let out = d.s("copy.db");
    let cmd = args(&["backup", &out]).unwrap();
    cli::run(cmd, ctx(&env, "x")).await.unwrap();
    let copy = Db::open(&d.path("copy.db")).unwrap();
    let (n, ok): (i64, String) = copy
        .run(|c| {
            Ok((
                c.query_row("SELECT count(*) FROM chores", [], |r| r.get(0))?,
                c.query_row("PRAGMA integrity_check", [], |r| r.get(0))?,
            ))
        })
        .await
        .unwrap();
    assert!(n >= 10);
    assert_eq!(ok, "ok");
    drop(copy);
    use std::os::unix::fs::PermissionsExt;
    let mode = |p: PathBuf| std::fs::metadata(p).unwrap().permissions().mode() & 0o777;
    assert_eq!(mode(d.path("copy.db")), 0o600);
    let e = cli::run(args(&["backup", &out]).unwrap(), ctx(&env, "x"))
        .await
        .unwrap_err();
    assert!(e.contains("--force"), "{e}");
    std::fs::set_permissions(d.path("copy.db"), std::fs::Permissions::from_mode(0o644)).unwrap();
    cli::run(args(&["backup", &out, "--force"]).unwrap(), ctx(&env, "x"))
        .await
        .unwrap();
    assert_eq!(mode(d.path("copy.db")), 0o600);
    assert!(!d.path("copy.db-wal").exists());
}

#[tokio::test]
async fn backup_without_database_errors() {
    let d = Scratch::new("backup-none");
    let env = data_env(&d);
    let e = cli::run(args(&["backup", &d.s("o.db")]).unwrap(), ctx(&env, "x"))
        .await
        .unwrap_err();
    assert!(e.contains("wg-chores.db"), "{e}");
    assert!(!d.path("o.db").exists());
}

#[tokio::test]
async fn create_user_enforces_register_rules() {
    let d = Scratch::new("user");
    let env = data_env(&d);
    let run = |a: &[&str], pw: &'static str| {
        let cmd = args(a).unwrap();
        let env = env.clone();
        async move { cli::run(cmd, ctx(&env, pw)).await }
    };
    run(
        &["create-user", "Jane.Doe", "--display-name", "Jane Doe"],
        "correct horse battery",
    )
    .await
    .unwrap();
    assert!(
        run(&["create-user", "jane.doe"], "correct horse battery")
            .await
            .is_err()
    );
    assert!(
        run(&["create-user", "x"], "correct horse battery")
            .await
            .is_err()
    );
    assert!(
        run(&["create-user", "bad name"], "correct horse battery")
            .await
            .is_err()
    );
    let e = run(&["create-user", "second"], "short").await.unwrap_err();
    assert!(e.contains("12"), "{e}");
    let db = Db::open(&db_path(&d)).unwrap();
    let (u, dn, hash): (String, String, String) = db
        .run(|c| {
            c.query_row(
                "SELECT u.username, u.display_name, i.password_hash FROM users u JOIN identities i ON i.user_id = u.id",
                [],
                |r| Ok((r.get(0)?, r.get(1)?, r.get(2)?)),
            )
        })
        .await
        .unwrap();
    assert_eq!((u.as_str(), dn.as_str()), ("jane.doe", "Jane Doe"));
    assert!(
        Hasher::insecure_for_tests()
            .verify("correct horse battery".into(), Some(hash))
            .await
            .unwrap()
    );
}

#[tokio::test]
async fn create_user_password_file_and_local_accounts_warning() {
    let d = Scratch::new("user-file");
    let mut env = data_env(&d);
    std::fs::write(d.path("pw"), "a long enough secret\n").unwrap();
    let cmd = args(&["create-user", "ben2", "--password-file", &d.s("pw")]).unwrap();
    let out = cli::run(cmd, ctx(&env, "unused")).await.unwrap();
    assert!(!out.contains("warning"), "{out}");
    env.push(("LOCAL_ACCOUNTS", "false".into()));
    let cmd = args(&["create-user", "carl2", "--password-file", &d.s("pw")]).unwrap();
    let out = cli::run(cmd, ctx(&env, "unused")).await.unwrap();
    assert!(out.contains("LOCAL_ACCOUNTS"), "{out}");
}

#[test]
fn parse_commands() {
    assert!(matches!(args(&[]).unwrap(), Command::Serve));
    assert!(matches!(args(&["serve"]).unwrap(), Command::Serve));
    assert!(matches!(args(&["--help"]).unwrap(), Command::Help(_)));
    assert!(matches!(args(&["backup", "-h"]).unwrap(), Command::Help(t) if t.contains("backup")));
    assert!(matches!(
        args(&["healthcheck"]).unwrap(),
        Command::Healthcheck
    ));
    assert!(matches!(args(&["--help"]).unwrap(), Command::Help(t) if t.contains("healthcheck")));
    assert!(
        matches!(args(&["--help"]).unwrap(), Command::Help(t) if t.contains("revoke-sessions"))
    );
    assert!(
        matches!(args(&["revoke-sessions", "-h"]).unwrap(), Command::Help(t) if t.contains("group"))
    );
    assert!(matches!(
        args(&["revoke-sessions", "anna"]).unwrap(),
        Command::RevokeSessions { who: Who::Name(n) } if n == "anna"
    ));
    assert!(matches!(
        args(&["revoke-sessions", "--user-id", "7"]).unwrap(),
        Command::RevokeSessions { who: Who::Id(7) }
    ));
    assert!(
        matches!(args(&["revoke-sessions", "-h"]).unwrap(), Command::Help(t) if t.contains("--user-id"))
    );
    match args(&[
        "migrate-v1",
        "--from",
        "v1.db",
        "--household",
        "Flat",
        "--map",
        "a=b,c=d",
        "--map",
        "e=f",
        "--force",
    ])
    .unwrap()
    {
        Command::MigrateV1 {
            from,
            household,
            map,
            force,
        } => {
            assert_eq!(from, PathBuf::from("v1.db"));
            assert_eq!(household, "Flat");
            assert_eq!(map, ["a=b,c=d", "e=f"]);
            assert!(force);
        }
        _ => panic!(),
    }
}

#[test]
fn usage_errors_name_the_next_command() {
    for bad in [
        vec!["nope"],
        vec!["backup"],
        vec!["backup", "a", "b"],
        vec!["migrate-v1", "--from", "x"],
        vec!["migrate-v1", "--bogus"],
        vec!["create-user"],
        vec!["create-user", "u", "--display-name"],
        vec!["demo-reset", "extra"],
        vec!["healthcheck", "extra"],
        vec!["revoke-sessions"],
        vec!["revoke-sessions", "a", "b"],
        vec!["revoke-sessions", "--user-id"],
        vec!["revoke-sessions", "--user-id", "x"],
        vec!["revoke-sessions", "--user-id", "0"],
        vec!["revoke-sessions", "--user-id", "7", "anna"],
    ] {
        let u = args(&bad).err().unwrap_or_else(|| panic!("{bad:?} parsed"));
        assert!(
            u.hint.contains("wg-chores") && u.hint.contains("--help"),
            "{bad:?}: {}",
            u.hint
        );
        assert!(!u.message.is_empty());
    }
}

#[test]
fn completions_list_every_subcommand() {
    for f in ["completions/wg-chores.fish", "completions/wg-chores.bash"] {
        let s = std::fs::read_to_string(format!("{}/{f}", env!("CARGO_MANIFEST_DIR"))).unwrap();
        for w in [
            "serve",
            "migrate-v1",
            "backup",
            "create-user",
            "demo-reset",
            "force",
            "map",
            "password-file",
            "household",
            "display-name",
            "from",
            "healthcheck",
            "revoke-sessions",
            "user-id",
        ] {
            assert!(s.contains(w), "{f}: {w}");
        }
    }
}

async fn serve_once(status: &'static str) -> u16 {
    let l = tokio::net::TcpListener::bind("127.0.0.1:0").await.unwrap();
    let port = l.local_addr().unwrap().port();
    tokio::spawn(async move {
        while let Ok((s, _)) = l.accept().await {
            let s = s.into_std().unwrap();
            s.set_nonblocking(false).unwrap();
            tokio::task::spawn_blocking(move || {
                use std::io::{Read, Write};
                let mut s = s;
                let mut buf = [0u8; 1024];
                let n = s.read(&mut buf).unwrap();
                let req = String::from_utf8_lossy(&buf[..n]).to_string();
                let line = if req.starts_with("GET /healthz ") {
                    status
                } else {
                    "404 Not Found"
                };
                let _ = write!(
                    s,
                    "HTTP/1.1 {line}\r\ncontent-length: 2\r\nconnection: close\r\n\r\nok"
                );
            });
        }
    });
    port
}

async fn healthcheck(listen: String) -> Result<String, String> {
    cli::run(Command::Healthcheck, ctx(&[("LISTEN", listen)], "unused")).await
}

#[tokio::test]
async fn healthcheck_passes_on_200_from_loopback() {
    let port = serve_once("200 OK").await;
    assert!(healthcheck(format!("0.0.0.0:{port}")).await.is_ok());
    assert!(healthcheck(format!("127.0.0.1:{port}")).await.is_ok());
}

#[tokio::test]
async fn healthcheck_fails_on_error_status_or_closed_port() {
    let port = serve_once("503 Service Unavailable").await;
    let e = healthcheck(format!("0.0.0.0:{port}")).await.unwrap_err();
    assert!(e.contains("503"), "{e}");
    let closed = std::net::TcpListener::bind("127.0.0.1:0").unwrap();
    let p = closed.local_addr().unwrap().port();
    drop(closed);
    assert!(healthcheck(format!("0.0.0.0:{p}")).await.is_err());
    assert!(healthcheck("nonsense".into()).await.is_err());
}

#[tokio::test]
async fn healthcheck_against_real_router() {
    let cfg = wg_chores::config::Config::from_env(|k| {
        (k == "PUBLIC_URL").then(|| "https://chores.example.com".to_string())
    })
    .unwrap();
    let app = wg_chores::app::router(wg_chores::app::AppState::new(
        cfg,
        Db::open_memory().unwrap(),
    ));
    let l = tokio::net::TcpListener::bind("127.0.0.1:0").await.unwrap();
    let port = l.local_addr().unwrap().port();
    tokio::spawn(async move {
        axum::serve(
            l,
            app.into_make_service_with_connect_info::<std::net::SocketAddr>(),
        )
        .await
        .unwrap()
    });
    assert!(healthcheck(format!("0.0.0.0:{port}")).await.is_ok());
}

#[tokio::test]
async fn revoke_sessions_deletes_every_session_of_that_user() {
    let d = Scratch::new("revoke");
    let env = data_env(&d);
    let db = Db::open(&db_path_created(&d)).unwrap();
    db.run(|c| {
        c.execute_batch(
            "INSERT INTO users (id, username, display_name, created_at) VALUES (1, 'anna', 'Anna', 'x'), (2, 'ben', 'Ben', 'x');
             INSERT INTO sessions (token_hash, user_id, kind, created_at, last_seen_at, expires_at) VALUES
               ('a1', 1, 'web', 'x', 'x', '2099-01-01T00:00:00.000Z'),
               ('a2', 1, 'android', 'x', 'x', '2099-01-01T00:00:00.000Z'),
               ('b1', 2, 'web', 'x', 'x', '2099-01-01T00:00:00.000Z');",
        )
    })
    .await
    .unwrap();
    drop(db);
    let out = cli::run(
        args(&["revoke-sessions", "ANNA"]).unwrap(),
        ctx(&env, "unused"),
    )
    .await
    .unwrap();
    assert!(out.contains("2 sessions"), "{out}");
    let db = Db::open(&db_path(&d)).unwrap();
    let left: Vec<String> = db
        .run(|c| {
            let mut st = c.prepare("SELECT token_hash FROM sessions ORDER BY 1")?;
            st.query_map([], |r| r.get(0))?.collect()
        })
        .await
        .unwrap();
    assert_eq!(left, ["b1"]);
    let e = cli::run(
        args(&["revoke-sessions", "nobody"]).unwrap(),
        ctx(&env, "unused"),
    )
    .await
    .unwrap_err();
    assert!(e.contains("nobody"), "{e}");
}

fn db_path_created(d: &Scratch) -> PathBuf {
    std::fs::create_dir_all(d.path("data")).unwrap();
    db_path(d)
}

#[tokio::test]
async fn revoke_sessions_finds_sso_users_by_display_name_or_id() {
    let d = Scratch::new("revoke-sso");
    let env = data_env(&d);
    let db = Db::open(&db_path_created(&d)).unwrap();
    db.run(|c| {
        c.execute_batch(
            "INSERT INTO users (id, username, display_name, created_at) VALUES
               (1, NULL, 'Jane Doe', 'x'), (2, NULL, 'Sam', 'x'), (3, NULL, 'Sam', 'x'), (4, 'anna', 'Ann B', 'x');
             INSERT INTO identities (user_id, kind, issuer, subject) VALUES
               (1, 'oidc', 'https://sso.example.com', 's1'), (2, 'oidc', 'https://sso.example.com', 's2'), (3, 'oidc', 'https://sso.example.com', 's3');
             INSERT INTO sessions (token_hash, user_id, kind, created_at, last_seen_at, expires_at) VALUES
               ('j1', 1, 'web', 'x', 'x', '2099-01-01T00:00:00.000Z'),
               ('s2', 2, 'web', 'x', 'x', '2099-01-01T00:00:00.000Z'),
               ('s3', 3, 'web', 'x', 'x', '2099-01-01T00:00:00.000Z'),
               ('a1', 4, 'web', 'x', 'x', '2099-01-01T00:00:00.000Z');",
        )
    })
    .await
    .unwrap();
    drop(db);
    let run = |a: &'static [&'static str]| {
        let env = env.clone();
        async move { cli::run(args(a).unwrap(), ctx(&env, "unused")).await }
    };
    let out = run(&["revoke-sessions", "Jane Doe"]).await.unwrap();
    assert!(out.contains("1 sessions"), "{out}");
    let e = run(&["revoke-sessions", "Sam"]).await.unwrap_err();
    assert!(e.contains("2, 3") && e.contains("--user-id"), "{e}");
    assert!(run(&["revoke-sessions", "Ann B"]).await.is_err());
    let out = run(&["revoke-sessions", "--user-id", "3"]).await.unwrap();
    assert!(out.contains("1 sessions"), "{out}");
    let e = run(&["revoke-sessions", "--user-id", "99"])
        .await
        .unwrap_err();
    assert!(e.contains("99"), "{e}");
    let db = Db::open(&db_path(&d)).unwrap();
    let left: Vec<String> = db
        .run(|c| {
            let mut st = c.prepare("SELECT token_hash FROM sessions ORDER BY 1")?;
            st.query_map([], |r| r.get(0))?.collect()
        })
        .await
        .unwrap();
    assert_eq!(left, ["a1", "s2"]);
}
