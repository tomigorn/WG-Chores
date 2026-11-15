use crate::auth::local::{Hasher, check_display_name, check_password, normalize_username};
use crate::auth::session::ts;
use crate::db::Db;
use crate::migrate_v1::{self, Options};
use chrono::{DateTime, Utc};
use chrono_tz::Tz;
use std::path::{Path, PathBuf};
use std::sync::Arc;

#[derive(Debug)]
pub enum Command {
    Serve,
    MigrateV1 {
        from: PathBuf,
        household: String,
        map: Vec<String>,
        force: bool,
    },
    Backup {
        file: PathBuf,
        force: bool,
    },
    CreateUser {
        username: String,
        display_name: Option<String>,
        password_file: Option<PathBuf>, // leak-check: ok
    },
    DemoReset,
    Healthcheck,
    Help(String),
}

#[derive(Debug)]
pub struct Usage {
    pub message: String,
    pub usage: String,
    pub hint: String,
}

pub const MAIN_HELP: &str = "\
wg-chores: WG Chores server and admin commands

Usage: wg-chores [serve | <command> ...]

Commands:
  serve        run the server (default)
  migrate-v1   import one household from a v1 SQLite file
  backup       write a consistent copy of the database
  create-user  create a local account
  demo-reset   wipe everything and load the demo data (DEMO_MODE=true only)
  healthcheck  exit 0 when the local server answers /healthz (container health checks)

Options:
  -h, --help   show this help; also works after a command

Environment: serve reads the full configuration (PUBLIC_URL, LISTEN, ...; see the README).
The other commands read only DATA_DIR (default /data) and TZ (default UTC), plus the variables named in their own help.

Next: wg-chores <command> --help";

const SERVE_HELP: &str = "\
Usage: wg-chores [serve]

Runs the server. Environment: PUBLIC_URL (required) and the rest of the configuration, see the README.";

const MIGRATE_HELP: &str = "\
Usage: wg-chores migrate-v1 --from <v1.db> --household <id|name> [--map <old>=<username>[,...]]... [--force]

Imports ONE household from a copy of the v1 SQLite file (opened read-only) in a single transaction.
  --from FILE        the v1 database file
  --household H      v1 household id, or its exact name (case-sensitive)
  --map OLD=USER     v1 member name or history name -> existing active v2 username; repeatable or comma-separated
  --force            import even though a v2 household with that name exists (creates another one)

Names without --map become former members. Missing v2 users: create them first with create-user.
Environment: DATA_DIR (default /data), TZ (default UTC; day of each completion).

Example: wg-chores migrate-v1 --from v1.db --household 'Flat Alpha' --map anna=anna,ben=ben2";

const BACKUP_HELP: &str = "\
Usage: wg-chores backup <file> [--force]

Writes a consistent copy of DATA_DIR/wg-chores.db (SQLite online backup, safe while the server runs).
  --force   overwrite an existing file

Environment: DATA_DIR (default /data).

Example: wg-chores backup /backups/wg-chores.db";

const USER_HELP: &str = "\
Usage: wg-chores create-user <username> [--display-name <name>] [--password-file <file>]

Creates a local account with the same rules as registration (username 3-32 of a-z 0-9 . _ -, password 12+ characters).
  --display-name N    default: the username
  --password-file F   read the password from F (last newline dropped); without it the password is asked twice on the terminal

Works with LOCAL_ACCOUNTS=false too, but the user cannot log in until local accounts are on.
Environment: DATA_DIR (default /data); LOCAL_ACCOUNTS is only read for the warning.

Example: wg-chores create-user jane.doe --display-name 'Jane Doe'";

const DEMO_HELP: &str = "\
Usage: wg-chores demo-reset

Deletes ALL data, then creates the user `reviewer` (password from DEMO_PASSWORD_FILE), the household \"Demo WG\" and sample chores with history relative to today.
Refuses to run unless DEMO_MODE=true.

Environment: DEMO_MODE=true, DEMO_PASSWORD_FILE (12+ characters), DATA_DIR (default /data), TZ (default UTC).";

const HEALTH_HELP: &str = "\
Usage: wg-chores healthcheck

Sends GET /healthz to the running server on this machine (LISTEN port, loopback when LISTEN is 0.0.0.0 or ::) and exits 0 on 200, else 1. Made for container health checks, which have no curl in this image.

Environment: LISTEN (default 0.0.0.0:8080).

Example (compose): healthcheck: { test: [\"CMD\", \"/wg-chores\", \"healthcheck\"] }";

fn usage_error(cmd: Option<&str>, message: impl Into<String>) -> Usage {
    let (usage, hint) = match cmd {
        Some(c) => (
            help_for(c).lines().next().unwrap_or("").to_string(),
            format!("Next: wg-chores {c} --help"),
        ),
        None => (
            "Usage: wg-chores [serve | migrate-v1 | backup | create-user | demo-reset | healthcheck]".into(),
            "Next: wg-chores --help".into(),
        ),
    };
    Usage {
        message: message.into(),
        usage,
        hint,
    }
}

fn help_for(cmd: &str) -> &'static str {
    match cmd {
        "serve" => SERVE_HELP,
        "migrate-v1" => MIGRATE_HELP,
        "backup" => BACKUP_HELP,
        "create-user" => USER_HELP,
        "demo-reset" => DEMO_HELP,
        "healthcheck" => HEALTH_HELP,
        _ => MAIN_HELP,
    }
}

pub fn parse(args: &[String]) -> Result<Command, Usage> {
    let Some(cmd) = args.first().map(String::as_str) else {
        return Ok(Command::Serve);
    };
    if matches!(cmd, "-h" | "--help" | "help") {
        return Ok(Command::Help(MAIN_HELP.into()));
    }
    let known = [
        "serve",
        "migrate-v1",
        "backup",
        "create-user",
        "demo-reset",
        "healthcheck",
    ];
    if !known.contains(&cmd) {
        return Err(usage_error(None, format!("unknown command \"{cmd}\"")));
    }
    let rest = &args[1..];
    if rest.iter().any(|a| a == "-h" || a == "--help") {
        return Ok(Command::Help(help_for(cmd).into()));
    }
    let err = |m: String| usage_error(Some(cmd), m);
    let mut it = rest.iter();
    let mut positional: Vec<String> = vec![];
    let (mut from, mut household, mut display, mut pwfile) = (None, None, None, None);
    let (mut map, mut force) = (vec![], false);
    while let Some(a) = it.next() {
        let mut value = |flag: &str| {
            it.next()
                .cloned()
                .ok_or_else(|| err(format!("{flag} needs a value")))
        };
        match (cmd, a.as_str()) {
            ("migrate-v1", "--from") => from = Some(value("--from")?),
            ("migrate-v1", "--household") => household = Some(value("--household")?),
            ("migrate-v1", "--map") => map.push(value("--map")?),
            ("migrate-v1" | "backup", "--force") => force = true,
            ("create-user", "--display-name") => display = Some(value("--display-name")?),
            ("create-user", "--password-file") => pwfile = Some(value("--password-file")?),
            (_, f) if f.starts_with('-') && f.len() > 1 => {
                return Err(err(format!("unknown option {f}")));
            }
            _ => positional.push(a.clone()),
        }
    }
    let no_positional = |p: &[String]| match p.first() {
        Some(x) => Err(err(format!("unexpected argument \"{x}\""))),
        None => Ok(()),
    };
    match cmd {
        "serve" => {
            no_positional(&positional)?;
            Ok(Command::Serve)
        }
        "demo-reset" => {
            no_positional(&positional)?;
            Ok(Command::DemoReset)
        }
        "healthcheck" => {
            no_positional(&positional)?;
            Ok(Command::Healthcheck)
        }
        "migrate-v1" => {
            no_positional(&positional)?;
            Ok(Command::MigrateV1 {
                from: from.ok_or_else(|| err("--from is required".into()))?.into(),
                household: household.ok_or_else(|| err("--household is required".into()))?,
                map,
                force,
            })
        }
        "backup" => match positional.as_slice() {
            [f] => Ok(Command::Backup {
                file: f.into(),
                force,
            }),
            [] => Err(err("the target file is required".into())),
            [_, x, ..] => Err(err(format!("unexpected argument \"{x}\""))),
        },
        _ => match positional.as_slice() {
            [u] => Ok(Command::CreateUser {
                username: u.clone(),
                display_name: display,
                password_file: pwfile.map(Into::into), // leak-check: ok
            }),
            [] => Err(err("the username is required".into())),
            [_, x, ..] => Err(err(format!("unexpected argument \"{x}\""))),
        },
    }
}

pub type EnvFn = Box<dyn Fn(&str) -> Option<String> + Send + Sync>;

pub struct Ctx {
    pub get: EnvFn,
    pub hasher: Arc<Hasher>,
    pub now: DateTime<Utc>,
    pub prompt_password: Box<dyn FnMut() -> Result<String, String> + Send>,
}

fn env_of(ctx: &Ctx, key: &str) -> Option<String> {
    (ctx.get)(key).filter(|v| !v.trim().is_empty())
}

fn data_dir(ctx: &Ctx) -> PathBuf {
    env_of(ctx, "DATA_DIR").map_or_else(|| "/data".into(), PathBuf::from)
}

fn tz(ctx: &Ctx) -> Result<Tz, String> {
    match env_of(ctx, "TZ") {
        None => Ok(chrono_tz::UTC),
        Some(v) => v.trim().parse().map_err(|e| format!("TZ is invalid: {e}")),
    }
}

fn open_db(ctx: &Ctx) -> Result<Db, String> {
    let dir = data_dir(ctx);
    std::fs::create_dir_all(&dir).map_err(|e| format!("cannot create {}: {e}", dir.display()))?;
    Db::open(&dir.join("wg-chores.db")).map_err(|e| e.to_string())
}

fn read_secret_file(var: &str, path: &Path) -> Result<String, String> {
    std::fs::read_to_string(path)
        .map(|s| s.trim_end_matches(['\n', '\r']).to_string())
        .map_err(|e| format!("cannot read {var} {}: {e}", path.display()))
}

pub async fn run(cmd: Command, mut ctx: Ctx) -> Result<String, String> {
    match cmd {
        Command::Help(t) => Ok(t),
        Command::Serve => Err("serve is started by the program itself".into()),
        Command::Backup { file, force } => backup(&ctx, &file, force),
        Command::DemoReset => demo_reset(&ctx).await,
        Command::Healthcheck => {
            let listen = env_of(&ctx, "LISTEN").unwrap_or_else(|| "0.0.0.0:8080".into());
            tokio::task::spawn_blocking(move || healthcheck(&listen))
                .await
                .map_err(|e| e.to_string())?
        }
        Command::CreateUser {
            username,
            display_name,
            password_file,
        } => {
            let username = normalize_username(&username).ok_or(
                "The username must be 3 to 32 characters: a-z, 0-9, dot, underscore, hyphen.",
            )?;
            let display = check_display_name(display_name.as_deref().unwrap_or(&username))
                .map_err(|e| e.message.to_string())?;
            let password = match password_file {
                Some(f) => read_secret_file("--password-file", &f)?,
                None => (ctx.prompt_password)()?,
            };
            check_password(&password).map_err(|e| e.message.to_string())?;
            let db = open_db(&ctx)?;
            create_user(&db, &ctx.hasher, &username, &display, password, ctx.now).await?;
            let mut out = format!("Created user {username}.");
            if matches!(
                env_of(&ctx, "LOCAL_ACCOUNTS").as_deref().map(str::trim),
                Some("false" | "0")
            ) {
                out.push_str("\nwarning: LOCAL_ACCOUNTS is off, so this user cannot log in until you set LOCAL_ACCOUNTS=true.");
            }
            out.push_str("\nNext: wg-chores serve");
            Ok(out)
        }
        Command::MigrateV1 {
            from,
            household,
            map,
            force,
        } => {
            let map = migrate_v1::parse_map(&map).map_err(|e| e.to_string())?;
            let opts = Options {
                household,
                map,
                force,
                tz: tz(&ctx)?,
            };
            let db = open_db(&ctx)?;
            let s = migrate_v1::run(&db, &from, opts, ctx.now)
                .await
                .map_err(|e| e.to_string())?;
            Ok(format!("{s}\nNext: wg-chores serve"))
        }
    }
}

fn healthcheck(listen: &str) -> Result<String, String> {
    use std::io::{Read, Write};
    use std::net::{IpAddr, Ipv4Addr, Ipv6Addr, SocketAddr, TcpStream};
    use std::time::Duration;
    let mut addr: SocketAddr = listen
        .trim()
        .parse()
        .map_err(|e| format!("LISTEN is invalid: {e}"))?;
    if addr.ip().is_unspecified() {
        addr.set_ip(match addr.ip() {
            IpAddr::V4(_) => Ipv4Addr::LOCALHOST.into(),
            IpAddr::V6(_) => Ipv6Addr::LOCALHOST.into(),
        });
    }
    let timeout = Duration::from_secs(3);
    let mut s = TcpStream::connect_timeout(&addr, timeout)
        .map_err(|e| format!("cannot reach {addr}: {e}"))?;
    s.set_read_timeout(Some(timeout))
        .map_err(|e| e.to_string())?;
    s.set_write_timeout(Some(timeout))
        .map_err(|e| e.to_string())?;
    write!(
        s,
        "GET /healthz HTTP/1.1\r\nHost: localhost\r\nConnection: close\r\n\r\n"
    )
    .map_err(|e| format!("cannot send to {addr}: {e}"))?;
    let mut buf = [0u8; 64];
    let mut n = 0;
    while n < buf.len() {
        match s.read(&mut buf[n..]) {
            Ok(0) => break,
            Ok(k) => n += k,
            Err(e) => return Err(format!("no answer from {addr}: {e}")),
        }
        if buf[..n].contains(&b'\n') {
            break;
        }
    }
    let head = String::from_utf8_lossy(&buf[..n]);
    let line = head.lines().next().unwrap_or("").trim();
    match line.split_whitespace().nth(1) {
        Some("200") => Ok("ok".into()),
        _ => Err(format!("unhealthy: {addr}/healthz answered \"{line}\"")),
    }
}

pub async fn create_user(
    db: &Db,
    hasher: &Arc<Hasher>,
    username: &str,
    display_name: &str,
    password: String, // leak-check: ok
    now: DateTime<Utc>,
) -> Result<(), String> {
    let hash = hasher
        .hash(password)
        .await
        .ok_or("cannot hash the password")?;
    let (u, d, now) = (username.to_string(), display_name.to_string(), ts(now));
    let inserted = db
        .run(move |c| {
            let tx = c.transaction()?;
            let taken: bool = tx.query_row(
                "SELECT EXISTS (SELECT 1 FROM users WHERE username = ?1)",
                [&u],
                |r| r.get(0),
            )?;
            if taken {
                return Ok(false);
            }
            tx.execute(
                "INSERT INTO users (username, display_name, created_at) VALUES (?1, ?2, ?3)",
                rusqlite::params![u, d, now],
            )?;
            let id = tx.last_insert_rowid();
            tx.execute(
                "INSERT INTO identities (user_id, kind, issuer, subject, password_hash) VALUES (?1, 'local', '', ?2, ?3)",
                rusqlite::params![id, u, hash],
            )?;
            tx.commit()?;
            Ok(true)
        })
        .await
        .map_err(|e| e.to_string())?;
    if inserted {
        Ok(())
    } else {
        Err(format!("the username {username} is already taken"))
    }
}

fn backup(ctx: &Ctx, file: &Path, force: bool) -> Result<String, String> {
    let src_path = data_dir(ctx).join("wg-chores.db");
    if !src_path.is_file() {
        return Err(format!(
            "no database at {}; set DATA_DIR to the server's data folder",
            src_path.display()
        ));
    }
    if file.exists() && !force {
        return Err(format!(
            "{} exists; use --force to overwrite it",
            file.display()
        ));
    }
    let tmp = file.with_extension("tmp-backup");
    let _ = std::fs::remove_file(&tmp);
    let copy = || -> rusqlite::Result<()> {
        let src = rusqlite::Connection::open(&src_path)?;
        let mut dst = rusqlite::Connection::open(&tmp)?;
        {
            let b = rusqlite::backup::Backup::new(&src, &mut dst)?;
            b.run_to_completion(256, std::time::Duration::from_millis(5), None)?;
        }
        let _: String = dst.query_row("PRAGMA journal_mode = DELETE", [], |r| r.get(0))?;
        Ok(())
    };
    let done = copy()
        .map_err(|e| e.to_string())
        .and_then(|()| std::fs::rename(&tmp, file).map_err(|e| e.to_string()));
    if let Err(e) = done {
        let _ = std::fs::remove_file(&tmp);
        return Err(format!("backup failed: {e}"));
    }
    Ok(format!(
        "Backup written to {}.\nRestore: stop the server, copy it to DATA_DIR/wg-chores.db, start the server.",
        file.display()
    ))
}

async fn demo_reset(ctx: &Ctx) -> Result<String, String> {
    if env_of(ctx, "DEMO_MODE").as_deref().map(str::trim) != Some("true") {
        return Err(
            "demo-reset deletes ALL data and only runs with DEMO_MODE=true; this server is not a demo server".into(),
        );
    }
    let pw_file = env_of(ctx, "DEMO_PASSWORD_FILE")
        .ok_or("DEMO_PASSWORD_FILE is required (a file with the reviewer password)")?;
    let password = read_secret_file("DEMO_PASSWORD_FILE", Path::new(&pw_file))?; // leak-check: ok
    check_password(&password).map_err(|e| format!("DEMO_PASSWORD_FILE: {}", e.message))?;
    let tz = tz(ctx)?;
    let today = ctx.now.with_timezone(&tz).date_naive();
    let db = open_db(ctx)?;
    crate::demo::reset(&db, &ctx.hasher, password, today, ctx.now).await?;
    Ok("Demo data reset: user reviewer, household \"Demo WG\".\nNext: wg-chores serve".into())
}
