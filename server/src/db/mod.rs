use rusqlite::Connection;
use std::path::Path;
use std::sync::{Arc, Mutex};

const MIGRATIONS: &[&str] = &[include_str!("migrations/0001_init.sql")];

#[derive(Debug, thiserror::Error)]
pub enum DbError {
    #[error("database: {0}")]
    Sqlite(#[from] rusqlite::Error),
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

fn migrate(conn: &mut Connection) -> rusqlite::Result<()> {
    let current: usize = conn.query_row("PRAGMA user_version", [], |r| r.get(0))?;
    for (i, sql) in MIGRATIONS.iter().enumerate().skip(current) {
        let tx = conn.transaction()?;
        tx.execute_batch(sql)?;
        tx.pragma_update(None, "user_version", i + 1)?;
        tx.commit()?;
    }
    Ok(())
}

#[cfg(test)]
mod tests {
    use super::*;

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
        assert_eq!(v, 1);
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
    async fn deleting_user_cascades_but_keeps_completions() {
        let db = Db::open_memory().unwrap();
        let ok = db
            .run(|c| {
                c.execute_batch(
                    "INSERT INTO users (id, display_name, created_at) VALUES (1, 'n', 'x');
                     INSERT INTO households (id, name, created_at) VALUES (1, 'h', 'x');
                     INSERT INTO memberships VALUES (1, 1, 'x');
                     INSERT INTO sessions VALUES ('t', 1, 'web', 'x', 'x', 'x');
                     INSERT INTO chores (id, household_id, name, created_at) VALUES (1, 1, 'c', 'x');
                     INSERT INTO completions (id, chore_id, user_id, done_on, created_at) VALUES ('u', 1, 1, '2026-01-01', 'x');",
                )?;
                Ok(c.execute("DELETE FROM users WHERE id = 1", []).is_err())
            })
            .await
            .unwrap();
        assert!(ok, "user with completions must not be hard-deletable");
    }

    fn tempdir(name: &str) -> std::path::PathBuf {
        let d = std::env::temp_dir().join(format!("wgc-db-{name}-{}", std::process::id()));
        std::fs::create_dir_all(&d).unwrap();
        d
    }
}
