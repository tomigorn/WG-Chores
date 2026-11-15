use crate::auth::local::Hasher;
use crate::auth::session::ts;
use crate::db::Db;
use chrono::{DateTime, Duration, NaiveDate, Utc};
use std::sync::Arc;

pub const USERNAME: &str = "reviewer";
pub const HOUSEHOLD: &str = "Demo WG";

/// (chore, category, interval, completions as days ago, newest first, note on the newest)
type Sample = (
    &'static str,
    &'static str,
    Option<&'static str>,
    &'static [i64],
    Option<&'static str>,
);

const SAMPLES: &[Sample] = &[
    ("Wipe counters", "Kitchen", Some("3d"), &[1, 4, 7], None),
    (
        "Clean the oven",
        "Kitchen",
        Some("3m"),
        &[60, 150, 240],
        Some("Used the oven spray"),
    ),
    ("Descale the kettle", "Kitchen", Some("1m"), &[27, 58], None),
    (
        "Vacuum the living room",
        "Living room",
        Some("1w"),
        &[9, 16, 23],
        None,
    ),
    (
        "Dust the shelves",
        "Living room",
        Some("2w"),
        &[25, 40],
        None,
    ),
    (
        "Water the plants",
        "Living room",
        Some("1w"),
        &[20, 27, 34],
        None,
    ),
    (
        "Clean the bathroom",
        "Bathroom",
        Some("1w"),
        &[3, 10, 17],
        None,
    ),
    (
        "Wash the shower curtain",
        "Bathroom",
        Some("6m"),
        &[400],
        None,
    ),
    ("Sort the paper recycling", "Hallway", Some("2w"), &[], None),
    (
        "Sweep the entrance",
        "Hallway",
        Some("1w"),
        &[5, 12],
        Some("Muddy again"),
    ),
];

pub async fn reset(
    db: &Db,
    hasher: &Arc<Hasher>,
    password: String, // leak-check: ok
    today: NaiveDate,
    now: DateTime<Utc>,
) -> Result<(), String> {
    let hash = hasher
        .hash(password)
        .await
        .map_err(|_| "cannot hash the password")?;
    let now_s = ts(now);
    db.run(move |c| {
        let tx = c.transaction()?;
        for t in [
            "notification_state",
            "push_subscriptions",
            "invites",
            "completions",
            "chores",
            "categories",
            "memberships",
            "sessions",
            "identities",
            "login_failures",
            "households",
            "users",
        ] {
            tx.execute(&format!("DELETE FROM {t}"), [])?;
        }
        let uid = crate::auth::local::insert_local_user(&tx, USERNAME, "Reviewer", &hash, &now_s)?;
        tx.execute(
            "INSERT INTO households (name, created_at) VALUES (?1, ?2)",
            rusqlite::params![HOUSEHOLD, now_s],
        )?;
        let hid = tx.last_insert_rowid();
        tx.execute(
            "INSERT INTO memberships (household_id, user_id, joined_at) VALUES (?1, ?2, ?3)",
            rusqlite::params![hid, uid, now_s],
        )?;
        tx.execute(
            "UPDATE users SET last_household_id = ?2 WHERE id = ?1",
            rusqlite::params![uid, hid],
        )?;
        let mut cats = std::collections::HashMap::new();
        for (n, (name, cat, interval, ago, note)) in SAMPLES.iter().enumerate() {
            let cat_id = match cats.get(cat) {
                Some(id) => *id,
                None => {
                    tx.execute(
                        "INSERT INTO categories (household_id, name) VALUES (?1, ?2)",
                        rusqlite::params![hid, cat],
                    )?;
                    let id = tx.last_insert_rowid();
                    cats.insert(*cat, id);
                    id
                }
            };
            tx.execute(
                "INSERT INTO chores (household_id, category_id, name, interval, created_at) VALUES (?1, ?2, ?3, ?4, ?5)",
                rusqlite::params![hid, cat_id, name, interval, now_s],
            )?;
            let chore = tx.last_insert_rowid();
            for (i, days) in ago.iter().enumerate() {
                let day = today - Duration::days(*days);
                tx.execute(
                    "INSERT INTO completions (id, chore_id, user_id, done_on, note, created_at) VALUES (?1, ?2, ?3, ?4, ?5, ?6)",
                    rusqlite::params![
                        format!("00000000-0000-4000-8000-{:04}{:08}", n, i),
                        chore,
                        uid,
                        day.to_string(),
                        if i == 0 { *note } else { None },
                        now_s
                    ],
                )?;
            }
        }
        tx.commit()
    })
    .await
    .map_err(|e| e.to_string())
}
