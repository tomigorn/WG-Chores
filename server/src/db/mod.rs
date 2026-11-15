use rusqlite::Connection;
use std::path::Path;
use std::sync::{Arc, Mutex};

const MIGRATIONS: &[&str] = &[
    include_str!("migrations/0001_init.sql"),
    include_str!("migrations/0002_app.sql"),
    include_str!("migrations/0003_app_confirm.sql"),
    include_str!("migrations/0004_interval_2m.sql"),
];

#[derive(Debug, thiserror::Error)]
pub enum DbError {
    #[error("database: {0}")]
    Sqlite(#[from] rusqlite::Error),
    #[error("database schema version {found} is newer than this build supports ({known})")]
    SchemaTooNew { found: usize, known: usize },
    #[error("database migration {version} would leave broken foreign keys, nothing changed")]
    ForeignKeys { version: usize },
    #[error(
        "foreign keys could not be switched off for the migration (open transaction?), nothing changed"
    )]
    ForeignKeysStillOn,
    #[error("database task failed: {0}")]
    Task(#[from] tokio::task::JoinError),
}

pub type Result<T> = std::result::Result<T, DbError>;

#[derive(Clone)]
pub struct Db {
    conn: Arc<Mutex<Connection>>,
}

impl Db {
    pub fn open(path: &Path) -> Result<Db> {
        let conn = Connection::open(path)?;
        conn.pragma_update(None, "journal_mode", "WAL")?;
        conn.pragma_update(None, "synchronous", "NORMAL")?;
        Self::init(conn)
    }

    pub fn open_memory() -> Result<Db> {
        Self::init(Connection::open_in_memory()?)
    }

    fn init(mut conn: Connection) -> Result<Db> {
        conn.pragma_update(None, "foreign_keys", "ON")?;
        conn.create_scalar_function(
            "ulower",
            1,
            rusqlite::functions::FunctionFlags::SQLITE_UTF8
                | rusqlite::functions::FunctionFlags::SQLITE_DETERMINISTIC,
            |ctx| Ok(ctx.get::<Option<String>>(0)?.map(|s| s.to_lowercase())),
        )?;
        conn.busy_timeout(std::time::Duration::from_secs(5))?;
        migrate(&mut conn)?;
        Ok(Db {
            conn: Arc::new(Mutex::new(conn)),
        })
    }

    pub async fn run<T: Send + 'static>(
        &self,
        f: impl FnOnce(&mut Connection) -> rusqlite::Result<T> + Send + 'static,
    ) -> Result<T> {
        let conn = self.conn.clone();
        let out = tokio::task::spawn_blocking(move || {
            let mut guard = conn.lock().unwrap_or_else(|e| e.into_inner());
            f(&mut guard)
        })
        .await?;
        Ok(out?)
    }
}

fn migrate(conn: &mut Connection) -> Result<()> {
    let version: i64 = conn.query_row("PRAGMA user_version", [], |r| r.get(0))?;
    let current = usize::try_from(version)
        .map_err(|_| rusqlite::Error::IntegralValueOutOfRange(0, version))?;
    if current > MIGRATIONS.len() {
        return Err(DbError::SchemaTooNew {
            found: current,
            known: MIGRATIONS.len(),
        });
    }
    if current == MIGRATIONS.len() {
        return Ok(());
    }
    // table rebuilds need foreign keys off (a DROP TABLE would cascade), see sqlite.org/lang_altertable.html
    let fk: bool = conn.query_row("PRAGMA foreign_keys", [], |r| r.get(0))?;
    conn.pragma_update(None, "foreign_keys", "OFF")?;
    let still_on: bool = conn.query_row("PRAGMA foreign_keys", [], |r| r.get(0))?;
    if still_on {
        return Err(DbError::ForeignKeysStillOn);
    }
    let out = apply(conn, current);
    conn.pragma_update(None, "foreign_keys", fk)?;
    out
}

fn apply(conn: &mut Connection, current: usize) -> Result<()> {
    for (i, sql) in MIGRATIONS.iter().enumerate().skip(current) {
        let tx = conn.transaction()?;
        tx.execute_batch(sql)?;
        let broken = tx
            .prepare("PRAGMA foreign_key_check")?
            .query([])?
            .next()?
            .is_some();
        if broken {
            return Err(DbError::ForeignKeys { version: i + 1 });
        }
        tx.pragma_update(None, "user_version", (i + 1) as i64)?;
        tx.commit()?;
    }
    Ok(())
}

#[cfg(test)]
mod tests {
    use super::*;

    #[tokio::test]
    async fn ulower_folds_unicode() {
        let db = Db::open_memory().unwrap();
        let v: String = db
            .run(|c| c.query_row("SELECT ulower('ÄRGER')", [], |r| r.get(0)))
            .await
            .unwrap();
        assert_eq!(v, "ärger");
    }

    #[tokio::test]
    async fn migrations_apply_twice_is_noop() {
        let dir = tempdir("twice");
        let path = dir.join("a.db");
        let db = Db::open(&path).unwrap();
        db.run(|c| {
            c.execute(
                "INSERT INTO households (name, created_at) VALUES ('h', 'x')",
                [],
            )
        })
        .await
        .unwrap();
        drop(db);
        let db = Db::open(&path).unwrap();
        let (v, n): (i64, i64) = db
            .run(|c| {
                Ok((
                    c.query_row("PRAGMA user_version", [], |r| r.get(0))?,
                    c.query_row("SELECT count(*) FROM households", [], |r| r.get(0))?,
                ))
            })
            .await
            .unwrap();
        assert_eq!(v, MIGRATIONS.len() as i64);
        assert_eq!(n, 1);
    }

    #[tokio::test]
    async fn foreign_keys_enforced() {
        let db = Db::open_memory().unwrap();
        let r = db
            .run(|c| {
                c.execute(
                    "INSERT INTO chores (household_id, name, created_at) VALUES (999, 'x', 'x')",
                    [],
                )
            })
            .await;
        assert!(r.is_err());
    }

    #[tokio::test]
    async fn wal_mode_on_file_db() {
        let db = Db::open(&tempdir("wal").join("w.db")).unwrap();
        let m: String = db
            .run(|c| c.query_row("PRAGMA journal_mode", [], |r| r.get(0)))
            .await
            .unwrap();
        assert_eq!(m, "wal");
    }

    #[tokio::test]
    async fn category_name_unique_case_insensitive() {
        let db = Db::open_memory().unwrap();
        db.run(|c| {
            c.execute(
                "INSERT INTO households (name, created_at) VALUES ('h', 'x')",
                [],
            )
        })
        .await
        .unwrap();
        let ins = |n: &'static str| {
            move |c: &mut rusqlite::Connection| {
                c.execute(
                    "INSERT INTO categories (household_id, name) VALUES (1, ?1)",
                    [n],
                )
            }
        };
        db.run(ins("Kitchen")).await.unwrap();
        assert!(db.run(ins("kitchen")).await.is_err());
    }

    #[tokio::test]
    async fn username_unique_case_insensitive_and_nullable() {
        let db = Db::open_memory().unwrap();
        let ins = |u: Option<&'static str>| {
            move |c: &mut rusqlite::Connection| {
                c.execute(
                    "INSERT INTO users (username, display_name, created_at) VALUES (?1, 'n', 'x')",
                    [u],
                )
            }
        };
        db.run(ins(None)).await.unwrap();
        db.run(ins(None)).await.unwrap();
        db.run(ins(Some("Anna"))).await.unwrap();
        assert!(db.run(ins(Some("anna"))).await.is_err());
    }

    #[tokio::test]
    async fn invalid_interval_rejected() {
        let db = Db::open_memory().unwrap();
        db.run(|c| {
            c.execute(
                "INSERT INTO households (name, created_at) VALUES ('h', 'x')",
                [],
            )
        })
        .await
        .unwrap();
        let ins = |i: Option<&'static str>| {
            move |c: &mut rusqlite::Connection| {
                c.execute("INSERT INTO chores (household_id, name, interval, created_at) VALUES (1, 'c', ?1, 'x')", [i])
            }
        };
        db.run(ins(Some("1w"))).await.unwrap();
        db.run(ins(None)).await.unwrap();
        assert!(db.run(ins(Some("2d"))).await.is_err());
    }

    #[tokio::test]
    async fn deleting_user_without_completions_cascades() {
        let db = Db::open_memory().unwrap();
        let counts: (i64, i64, i64) = db
            .run(|c| {
                c.execute_batch(
                    "INSERT INTO users (id, display_name, created_at) VALUES (1, 'n', 'x');
                     INSERT INTO households (id, name, created_at) VALUES (1, 'h', 'x');
                     INSERT INTO memberships VALUES (1, 1, 'x');
                     INSERT INTO sessions (token_hash, user_id, kind, created_at, last_seen_at, expires_at) VALUES ('t', 1, 'web', 'x', 'x', 'x');
                     INSERT INTO identities (user_id, kind, subject) VALUES (1, 'local', 'anna');
                     DELETE FROM users WHERE id = 1;",
                )?;
                Ok((
                    c.query_row("SELECT count(*) FROM memberships", [], |r| r.get(0))?,
                    c.query_row("SELECT count(*) FROM sessions", [], |r| r.get(0))?,
                    c.query_row("SELECT count(*) FROM identities", [], |r| r.get(0))?,
                ))
            })
            .await
            .unwrap();
        assert_eq!(counts, (0, 0, 0));
    }

    #[tokio::test]
    async fn user_with_completions_cannot_be_hard_deleted() {
        let db = Db::open_memory().unwrap();
        let failed: (bool, i64) = db
            .run(|c| {
                c.execute_batch(
                    "INSERT INTO users (id, display_name, created_at) VALUES (1, 'n', 'x');
                     INSERT INTO households (id, name, created_at) VALUES (1, 'h', 'x');
                     INSERT INTO chores (id, household_id, name, created_at) VALUES (1, 1, 'c', 'x');
                     INSERT INTO completions (id, chore_id, user_id, done_on, created_at) VALUES ('u', 1, 1, '2026-01-01', 'x');",
                )?;
                let err = c.execute("DELETE FROM users WHERE id = 1", []).unwrap_err();
                let fk = matches!(err, rusqlite::Error::SqliteFailure(ref e, _)
                    if e.extended_code == rusqlite::ffi::SQLITE_CONSTRAINT_FOREIGNKEY);
                let rows: i64 = c.query_row("SELECT count(*) FROM users WHERE id = 1", [], |r| r.get(0))?;
                Ok((fk, rows))
            })
            .await
            .unwrap();
        assert_eq!(failed, (true, 1));
    }

    #[tokio::test]
    async fn deleting_household_clears_last_household_id() {
        let db = Db::open_memory().unwrap();
        let v: Option<i64> = db
            .run(|c| {
                c.execute_batch(
                    "INSERT INTO households (id, name, created_at) VALUES (1, 'h', 'x');
                     INSERT INTO users (id, display_name, last_household_id, created_at) VALUES (1, 'n', 1, 'x');
                     DELETE FROM households WHERE id = 1;",
                )?;
                c.query_row("SELECT last_household_id FROM users WHERE id = 1", [], |r| r.get(0))
            })
            .await
            .unwrap();
        assert_eq!(v, None);
    }

    #[test]
    fn version_1_database_upgrades_keeping_sessions() {
        let mut conn = Connection::open_in_memory().unwrap();
        conn.create_scalar_function(
            "ulower",
            1,
            rusqlite::functions::FunctionFlags::SQLITE_DETERMINISTIC,
            |ctx| ctx.get::<Option<String>>(0),
        )
        .unwrap();
        conn.execute_batch(MIGRATIONS[0]).unwrap();
        conn.pragma_update(None, "user_version", 1).unwrap();
        conn.execute_batch(
            "INSERT INTO users (id, display_name, created_at) VALUES (1, 'n', 'x');
             INSERT INTO sessions VALUES ('t', 1, 'web', 'x', 'x', 'x');",
        )
        .unwrap();
        migrate(&mut conn).unwrap();
        let (v, name): (i64, Option<String>) = conn
            .query_row(
                "SELECT (SELECT user_version FROM pragma_user_version), device_name FROM sessions",
                [],
                |r| Ok((r.get(0)?, r.get(1)?)),
            )
            .unwrap();
        assert_eq!((v, name), (MIGRATIONS.len() as i64, None));
        let n: i64 = conn
            .query_row("SELECT count(*) FROM device_notification_state", [], |r| {
                r.get(0)
            })
            .unwrap();
        assert_eq!(n, 0);
    }

    fn conn_at_version(v: usize) -> Connection {
        let mut conn = Connection::open_in_memory().unwrap();
        conn.pragma_update(None, "foreign_keys", "ON").unwrap();
        conn.create_scalar_function(
            "ulower",
            1,
            rusqlite::functions::FunctionFlags::SQLITE_DETERMINISTIC,
            |ctx| Ok(ctx.get::<Option<String>>(0)?.map(|s| s.to_lowercase())),
        )
        .unwrap();
        for (i, sql) in MIGRATIONS.iter().take(v).enumerate() {
            let tx = conn.transaction().unwrap();
            tx.execute_batch(sql).unwrap();
            tx.pragma_update(None, "user_version", (i + 1) as i64)
                .unwrap();
            tx.commit().unwrap();
        }
        conn
    }

    fn rows(conn: &Connection, sql: &str) -> Vec<String> {
        let mut st = conn.prepare(sql).unwrap();
        let n = st.column_count();
        st.query_map([], |r| {
            (0..n)
                .map(|i| {
                    r.get::<_, rusqlite::types::Value>(i)
                        .map(|v| format!("{v:?}"))
                })
                .collect::<rusqlite::Result<Vec<_>>>()
                .map(|v| v.join("|"))
        })
        .unwrap()
        .collect::<rusqlite::Result<Vec<_>>>()
        .unwrap()
    }

    #[test]
    fn version_3_database_gains_two_month_interval_keeping_data() {
        let mut conn = conn_at_version(3);
        conn.execute_batch(
            "INSERT INTO users (id, display_name, created_at) VALUES (1, 'n', 'x');
             INSERT INTO households (id, name, created_at) VALUES (1, 'h', 'x');
             INSERT INTO categories (id, household_id, name) VALUES (4, 1, 'Kitchen');
             INSERT INTO chores (id, household_id, category_id, name, interval, archived_at, created_at) VALUES
                 (3, 1, 4, 'Bins', '1w', NULL, 'a'),
                 (7, 1, NULL, 'Oven', '3m', 'z', 'b'),
                 (9, 1, 4, 'Plants', NULL, NULL, 'c');
             INSERT INTO completions (id, chore_id, user_id, done_on, note, created_at) VALUES
                 ('u1', 3, 1, '2026-01-01', 'ok', 'x'),
                 ('u2', 7, 1, '2026-01-02', NULL, 'x');
             INSERT INTO done_events (completion_id) VALUES ('u1');
             INSERT INTO sessions (token_hash, user_id, kind, created_at, last_seen_at, expires_at) VALUES ('t', 1, 'android', 'x', 'x', 'x');
             INSERT INTO device_notification_state (session_hash, chore_id, told_on) VALUES ('t', 3, '2026-01-03');
             INSERT INTO push_subscriptions (id, user_id, endpoint, p256dh, auth, created_at) VALUES (1, 1, 'e', 'p', 'a', 'x');
             INSERT INTO notification_state VALUES (1, 7, '2026-01-04');",
        )
        .unwrap();
        let tables = [
            "SELECT * FROM chores ORDER BY id",
            "SELECT * FROM completions ORDER BY id",
            "SELECT * FROM done_events",
            "SELECT * FROM device_notification_state",
            "SELECT * FROM notification_state",
        ];
        let before: Vec<Vec<String>> = tables.iter().map(|q| rows(&conn, q)).collect();
        migrate(&mut conn).unwrap();
        let after: Vec<Vec<String>> = tables.iter().map(|q| rows(&conn, q)).collect();
        assert_eq!(before, after);
        assert_eq!(
            rows(&conn, "PRAGMA foreign_key_check"),
            Vec::<String>::new()
        );
        assert_eq!(rows(&conn, "PRAGMA integrity_check"), ["Text(\"ok\")"]);
        assert_eq!(rows(&conn, "PRAGMA foreign_keys"), ["Integer(1)"]);
        assert_eq!(
            rows(
                &conn,
                "SELECT name FROM sqlite_schema WHERE tbl_name = 'chores' AND type = 'index'"
            ),
            ["Text(\"chores_household\")"]
        );
        let ins = |c: &Connection, iv: &str| {
            c.execute(
                "INSERT INTO chores (household_id, name, interval, created_at) VALUES (1, 'c', ?1, 'x')",
                [iv],
            )
        };
        ins(&conn, "2m").unwrap();
        assert!(ins(&conn, "2d").is_err());
        assert!(
            conn.execute(
                "INSERT INTO chores (household_id, name, created_at) VALUES (999, 'c', 'x')",
                []
            )
            .is_err()
        );
        conn.execute("DELETE FROM chores WHERE id = 3", []).unwrap();
        let left: i64 = conn
            .query_row(
                "SELECT (SELECT count(*) FROM completions) + (SELECT count(*) FROM done_events) + (SELECT count(*) FROM device_notification_state)",
                [],
                |r| r.get(0),
            )
            .unwrap();
        assert_eq!(left, 1);
        conn.execute("DELETE FROM households WHERE id = 1", [])
            .unwrap();
        let chores: i64 = conn
            .query_row("SELECT count(*) FROM chores", [], |r| r.get(0))
            .unwrap();
        assert_eq!(chores, 0);
    }

    #[test]
    fn migration_with_broken_foreign_keys_rolls_back() {
        let mut conn = conn_at_version(3);
        conn.pragma_update(None, "foreign_keys", "OFF").unwrap();
        conn.execute_batch(
            "INSERT INTO chores (id, household_id, name, interval, created_at) VALUES (1, 42, 'orphan', '1w', 'x');",
        )
        .unwrap();
        conn.pragma_update(None, "foreign_keys", "ON").unwrap();
        assert!(matches!(
            migrate(&mut conn),
            Err(DbError::ForeignKeys { version: 4 })
        ));
        let v: i64 = conn
            .query_row("PRAGMA user_version", [], |r| r.get(0))
            .unwrap();
        assert_eq!(v, 3);
        assert_eq!(rows(&conn, "PRAGMA foreign_keys"), ["Integer(1)"]);
        assert_eq!(rows(&conn, "SELECT name FROM chores"), ["Text(\"orphan\")"]);
    }

    #[test]
    fn migration_inside_an_open_transaction_is_refused() {
        let mut conn = conn_at_version(3);
        conn.execute_batch(
            "INSERT INTO households (id, name, created_at) VALUES (1, 'h', 'x');
             INSERT INTO chores (id, household_id, name, interval, created_at) VALUES (1, 1, 'c', '1w', 'x');
             BEGIN;",
        )
        .unwrap();
        assert!(matches!(
            migrate(&mut conn),
            Err(DbError::ForeignKeysStillOn)
        ));
        conn.execute_batch("ROLLBACK;").unwrap();
        let v: i64 = conn
            .query_row("PRAGMA user_version", [], |r| r.get(0))
            .unwrap();
        assert_eq!(v, 3);
        assert_eq!(rows(&conn, "SELECT name FROM chores"), ["Text(\"c\")"]);
    }

    #[test]
    fn newer_database_is_refused() {
        let mut conn = Connection::open_in_memory().unwrap();
        conn.pragma_update(None, "user_version", 99).unwrap();
        assert!(matches!(
            migrate(&mut conn),
            Err(DbError::SchemaTooNew { .. })
        ));
    }

    struct TempDir(std::path::PathBuf);

    impl TempDir {
        fn join(&self, f: &str) -> std::path::PathBuf {
            self.0.join(f)
        }
    }

    impl Drop for TempDir {
        fn drop(&mut self) {
            let _ = std::fs::remove_dir_all(&self.0);
        }
    }

    fn tempdir(name: &str) -> TempDir {
        static N: std::sync::atomic::AtomicUsize = std::sync::atomic::AtomicUsize::new(0);
        let n = N.fetch_add(1, std::sync::atomic::Ordering::Relaxed);
        let d = std::env::temp_dir().join(format!("wgc-db-{name}-{}-{n}", std::process::id()));
        std::fs::create_dir_all(&d).unwrap();
        TempDir(d)
    }
}
