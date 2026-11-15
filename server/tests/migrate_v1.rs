use chrono::{TimeZone, Utc};
use chrono_tz::Tz;
use rusqlite::Connection;
use std::path::{Path, PathBuf};
use wg_chores::db::Db;
use wg_chores::migrate_v1::{self, Options};

struct Scratch(PathBuf);

impl Scratch {
    fn new(name: &str) -> Scratch {
        let d = std::env::temp_dir().join(format!("wgc-mig-{name}-{}", std::process::id()));
        let _ = std::fs::remove_dir_all(&d);
        std::fs::create_dir_all(&d).unwrap();
        Scratch(d)
    }
    fn path(&self, f: &str) -> PathBuf {
        self.0.join(f)
    }
}

impl Drop for Scratch {
    fn drop(&mut self) {
        let _ = std::fs::remove_dir_all(&self.0);
    }
}

fn v1_file(dir: &Scratch) -> PathBuf {
    let p = dir.path("v1.db");
    let sql = std::fs::read_to_string(concat!(
        env!("CARGO_MANIFEST_DIR"),
        "/tests/fixtures/v1.sql"
    ))
    .unwrap();
    Connection::open(&p).unwrap().execute_batch(&sql).unwrap();
    p
}

fn opts(household: &str, map: &[&str], tz: Tz) -> Options {
    Options {
        household: household.into(),
        map: migrate_v1::parse_map(&map.iter().map(|s| s.to_string()).collect::<Vec<_>>()).unwrap(),
        force: false,
        tz,
    }
}

fn now() -> chrono::DateTime<Utc> {
    Utc.with_ymd_and_hms(2026, 6, 1, 12, 0, 0).unwrap()
}

async fn v2_with_users(dir: &Scratch, users: &[&str]) -> Db {
    let db = Db::open(&dir.path("v2.db")).unwrap();
    for u in users {
        let u = u.to_string();
        db.run(move |c| {
            c.execute(
                "INSERT INTO users (username, display_name, created_at) VALUES (?1, ?1, 'x')",
                [&u],
            )
        })
        .await
        .unwrap();
    }
    db
}

async fn count(db: &Db, sql: &'static str) -> i64 {
    db.run(move |c| c.query_row(sql, [], |r| r.get(0)))
        .await
        .unwrap()
}

const ALL: [&str; 3] = ["anna=ann", "ben=ben2", "carl=carl2"];

#[tokio::test]
async fn imports_only_chosen_household() {
    let d = Scratch::new("only");
    let v1 = v1_file(&d);
    let db = v2_with_users(&d, &["ann", "ben2", "carl2"]).await;
    let s = migrate_v1::run(&db, &v1, opts("1", &ALL, Tz::UTC), now())
        .await
        .unwrap();
    assert_eq!(s.household, "Flat Alpha");
    assert_eq!((s.chores, s.categories, s.completions), (5, 3, 4));
    assert_eq!(s.skipped_missing_chore, 1);
    assert_eq!(s.former, ["Old Flatmate"]);
    assert_eq!(count(&db, "SELECT count(*) FROM households").await, 1);
    assert_eq!(count(&db, "SELECT count(*) FROM chores").await, 5);
    assert_eq!(
        count(
            &db,
            "SELECT count(*) FROM categories WHERE name = 'Balcony'"
        )
        .await,
        0
    );
    assert_eq!(count(&db, "SELECT count(*) FROM memberships").await, 3);
    assert_eq!(
        count(
            &db,
            "SELECT count(*) FROM chores WHERE interval IS NOT NULL"
        )
        .await,
        0
    );
}

#[tokio::test]
async fn household_by_name_and_ambiguity() {
    let d = Scratch::new("byname");
    let v1 = v1_file(&d);
    let db = v2_with_users(&d, &["ann", "ben2", "carl2"]).await;
    let s = migrate_v1::run(&db, &v1, opts("Flat Alpha", &ALL, Tz::UTC), now())
        .await
        .unwrap();
    assert_eq!(s.chores, 5);
    let e = migrate_v1::run(&db, &v1, opts("flat alpha", &ALL, Tz::UTC), now())
        .await
        .unwrap_err();
    assert!(e.to_string().contains("Flat Beta"), "{e}");
    Connection::open(&v1)
        .unwrap()
        .execute(
            "INSERT INTO Households (Id, Name, Code) VALUES (3, 'Flat Beta', 'CCC333')",
            [],
        )
        .unwrap();
    let e = migrate_v1::run(&db, &v1, opts("Flat Beta", &[], Tz::UTC), now())
        .await
        .unwrap_err();
    let m = e.to_string();
    assert!(
        m.contains("ambiguous") && m.contains('2') && m.contains('3'),
        "{m}"
    );
}

#[tokio::test]
async fn room_null_becomes_other() {
    let d = Scratch::new("room");
    let v1 = v1_file(&d);
    let db = v2_with_users(&d, &["ann", "ben2", "carl2"]).await;
    migrate_v1::run(&db, &v1, opts("1", &ALL, Tz::UTC), now())
        .await
        .unwrap();
    let rows: Vec<(String, String)> = db
        .run(|c| {
            let mut s = c.prepare(
                "SELECT ch.name, ca.name FROM chores ch JOIN categories ca ON ca.id = ch.category_id ORDER BY ch.name",
            )?;
            s.query_map([], |r| Ok((r.get(0)?, r.get(1)?)))?.collect()
        })
        .await
        .unwrap();
    let cat = |n: &str| rows.iter().find(|r| r.0 == n).unwrap().1.clone();
    assert_eq!(cat("Bins"), "Other");
    assert_eq!(cat("Mop floor"), "Other");
    assert_eq!(cat("Vacuum"), "Living room");
    assert_eq!(cat("Dust shelves"), "Living room");
    assert_eq!(cat("Dishes"), "Kitchen");
}

#[tokio::test]
async fn history_dates_and_notes_kept() {
    let d = Scratch::new("hist");
    let v1 = v1_file(&d);
    let db = v2_with_users(&d, &["ann", "ben2", "carl2"]).await;
    migrate_v1::run(&db, &v1, opts("1", &ALL, chrono_tz::Europe::Zurich), now())
        .await
        .unwrap();
    let rows: Vec<(String, String, Option<String>, String, String)> = db
        .run(|c| {
            let mut s = c.prepare(
                "SELECT ch.name, co.done_on, co.note, u.username, co.id
                 FROM completions co JOIN chores ch ON ch.id = co.chore_id
                 JOIN users u ON u.id = co.user_id WHERE u.username IS NOT NULL
                 ORDER BY co.done_on",
            )?;
            s.query_map([], |r| {
                Ok((r.get(0)?, r.get(1)?, r.get(2)?, r.get(3)?, r.get(4)?))
            })?
            .collect()
        })
        .await
        .unwrap();
    assert_eq!(rows.len(), 3);
    assert_eq!(
        (
            rows[0].0.as_str(),
            rows[0].1.as_str(),
            rows[0].2.as_deref(),
            rows[0].3.as_str()
        ),
        ("Vacuum", "2026-03-06", Some("thorough"), "ann")
    );
    assert_eq!(
        (rows[1].1.as_str(), rows[1].2.as_deref(), rows[1].3.as_str()),
        ("2026-03-10", None, "ben2")
    );
    assert_eq!(
        (rows[2].1.as_str(), rows[2].2.as_deref(), rows[2].3.as_str()),
        ("2026-03-12", None, "carl2"),
        "empty note becomes none"
    );
    let ids: std::collections::HashSet<_> = rows.iter().map(|r| r.4.clone()).collect();
    assert_eq!(ids.len(), 3);
    assert!(rows.iter().all(|r| r.4.len() == 36));
}

#[tokio::test]
async fn done_at_without_offset_is_utc() {
    let d = Scratch::new("utc");
    let v1 = v1_file(&d);
    let db = v2_with_users(&d, &["ann", "ben2", "carl2"]).await;
    migrate_v1::run(&db, &v1, opts("1", &ALL, Tz::UTC), now())
        .await
        .unwrap();
    let day: String = db
        .run(|c| {
            c.query_row(
                "SELECT done_on FROM completions WHERE note = 'thorough'",
                [],
                |r| r.get(0),
            )
        })
        .await
        .unwrap();
    assert_eq!(day, "2026-03-05");
}

#[tokio::test]
async fn unmapped_names_become_former_members() {
    let d = Scratch::new("former");
    let v1 = v1_file(&d);
    let db = v2_with_users(&d, &["ann"]).await;
    let s = migrate_v1::run(&db, &v1, opts("1", &["anna=ann"], Tz::UTC), now())
        .await
        .unwrap();
    assert_eq!(s.former, ["ben", "carl", "Old Flatmate"]);
    let rows: Vec<(String, bool, Option<String>, i64)> = db
        .run(|c| {
            let mut s = c.prepare(
                "SELECT display_name, former, username,
                    (SELECT count(*) FROM identities i WHERE i.user_id = u.id)
                 FROM users u WHERE former = 1 ORDER BY id",
            )?;
            s.query_map([], |r| Ok((r.get(0)?, r.get(1)?, r.get(2)?, r.get(3)?)))?
                .collect()
        })
        .await
        .unwrap();
    assert_eq!(
        rows.iter().map(|r| r.0.as_str()).collect::<Vec<_>>(),
        ["ben", "carl", "Old Flatmate"]
    );
    assert!(rows.iter().all(|r| r.1 && r.2.is_none() && r.3 == 0));
    assert_eq!(count(&db, "SELECT count(*) FROM memberships").await, 1);
    let ben_done: i64 = db
        .run(|c| {
            c.query_row(
                "SELECT count(*) FROM completions co JOIN users u ON u.id = co.user_id WHERE u.display_name = 'ben'",
                [],
                |r| r.get(0),
            )
        })
        .await
        .unwrap();
    assert_eq!(ben_done, 1);
}

#[tokio::test]
async fn missing_mapped_user_errors_without_writing() {
    let d = Scratch::new("missing");
    let v1 = v1_file(&d);
    let db = v2_with_users(&d, &["ann"]).await;
    let e = migrate_v1::run(
        &db,
        &v1,
        opts("1", &["anna=ann", "ben=nobody", "carl=ghost"], Tz::UTC),
        now(),
    )
    .await
    .unwrap_err()
    .to_string();
    assert!(
        e.contains("nobody") && e.contains("ghost") && !e.contains("ann,"),
        "{e}"
    );
    assert_eq!(count(&db, "SELECT count(*) FROM households").await, 0);
    assert_eq!(count(&db, "SELECT count(*) FROM users").await, 1);
    assert_eq!(count(&db, "SELECT count(*) FROM chores").await, 0);
}

#[tokio::test]
async fn map_for_unknown_old_name_is_refused() {
    let d = Scratch::new("unkold");
    let v1 = v1_file(&d);
    let db = v2_with_users(&d, &["ann"]).await;
    let e = migrate_v1::run(&db, &v1, opts("1", &["anne=ann"], Tz::UTC), now())
        .await
        .unwrap_err()
        .to_string();
    assert!(e.contains("anne") && e.contains("anna"), "{e}");
    assert_eq!(count(&db, "SELECT count(*) FROM households").await, 0);
}

#[tokio::test]
async fn mapping_ignores_case_and_skips_inactive_users() {
    let d = Scratch::new("case");
    let v1 = v1_file(&d);
    let db = v2_with_users(&d, &["Ann", "ben2"]).await;
    db.run(|c| {
        c.execute(
            "UPDATE users SET deleted_at = 'x' WHERE username = 'ben2'",
            [],
        )
    })
    .await
    .unwrap();
    let e = migrate_v1::run(
        &db,
        &v1,
        opts("1", &["ANNA=ann", "ben=ben2"], Tz::UTC),
        now(),
    )
    .await
    .unwrap_err()
    .to_string();
    assert!(e.contains("ben2"), "{e}");
    let s = migrate_v1::run(&db, &v1, opts("1", &["ANNA=ann"], Tz::UTC), now())
        .await
        .unwrap();
    assert_eq!(s.members, ["Ann"]);
}

#[tokio::test]
async fn v1_file_opened_read_only() {
    use std::os::unix::fs::PermissionsExt;
    let d = Scratch::new("ro");
    let v1 = v1_file(&d);
    let before = std::fs::read(&v1).unwrap();
    let mtime = std::fs::metadata(&v1).unwrap().modified().unwrap();
    std::fs::set_permissions(&v1, std::fs::Permissions::from_mode(0o444)).unwrap();
    let db = v2_with_users(&d, &["ann", "ben2", "carl2"]).await;
    migrate_v1::run(&db, &v1, opts("1", &ALL, Tz::UTC), now())
        .await
        .unwrap();
    assert_eq!(std::fs::metadata(&v1).unwrap().modified().unwrap(), mtime);
    assert_eq!(std::fs::read(&v1).unwrap(), before);
    assert!(!Path::new(&format!("{}-wal", v1.display())).exists());
    assert!(!Path::new(&format!("{}-journal", v1.display())).exists());
}

#[tokio::test]
async fn rerun_refused_when_household_exists() {
    let d = Scratch::new("rerun");
    let v1 = v1_file(&d);
    let db = v2_with_users(&d, &["ann", "ben2", "carl2"]).await;
    migrate_v1::run(&db, &v1, opts("1", &ALL, Tz::UTC), now())
        .await
        .unwrap();
    let e = migrate_v1::run(&db, &v1, opts("1", &ALL, Tz::UTC), now())
        .await
        .unwrap_err()
        .to_string();
    assert!(e.contains("--force"), "{e}");
    assert_eq!(count(&db, "SELECT count(*) FROM households").await, 1);
    let mut o = opts("1", &ALL, Tz::UTC);
    o.force = true;
    migrate_v1::run(&db, &v1, o, now()).await.unwrap();
    assert_eq!(count(&db, "SELECT count(*) FROM households").await, 2);
}

#[test]
fn map_syntax() {
    let m = migrate_v1::parse_map(&["a=b,c=d".into(), "e=f".into()]).unwrap();
    assert_eq!(m.len(), 3);
    assert!(migrate_v1::parse_map(&["nope".into()]).is_err());
    assert!(migrate_v1::parse_map(&["=x".into()]).is_err());
    assert!(migrate_v1::parse_map(&["a=".into()]).is_err());
    assert!(migrate_v1::parse_map(&["a=b,A=c".into()]).is_err());
    assert_eq!(
        migrate_v1::parse_map(&["Anna= Ben.Two ".into()]).unwrap(),
        [("Anna".to_string(), "ben.two".to_string())]
    );
}

#[tokio::test]
async fn names_without_usable_history_are_listed_not_created() {
    let d = Scratch::new("unused");
    let v1 = v1_file(&d);
    Connection::open(&v1)
        .unwrap()
        .execute_batch(
            "INSERT INTO Members (Id, Username, HouseholdId, CreatedAt) VALUES (5, 'eve', 1, '2026-01-27 10:00:00');
             INSERT INTO ChoreHistories (Id, ChoreId, MemberId, MemberName, DoneAt, Notes) VALUES (7, 1, NULL, 'Ghost Writer', 'not a date', NULL);",
        )
        .unwrap();
    let db = v2_with_users(&d, &["ann", "ben2", "carl2"]).await;
    let s = migrate_v1::run(&db, &v1, opts("1", &ALL, Tz::UTC), now())
        .await
        .unwrap();
    assert_eq!(s.former, ["Old Flatmate"]);
    assert_eq!(s.not_imported, ["eve", "Ghost Writer"]);
    assert_eq!(s.skipped_bad_date, 1);
    assert!(s.to_string().contains("eve, Ghost Writer"), "{s}");
    assert_eq!(
        count(
            &db,
            "SELECT count(*) FROM users WHERE display_name = 'Ghost Writer'"
        )
        .await,
        0
    );
}
