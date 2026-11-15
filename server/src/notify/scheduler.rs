use super::{Outcome, SUB_COLS, Sub, deliver, due_payload, join_logged};
use crate::app::AppState;
use chrono::{DateTime, Duration, LocalResult, NaiveDateTime, NaiveTime, TimeZone, Utc};
use chrono_tz::Tz;
use rusqlite::params;
use std::collections::BTreeMap;

fn to_utc(local: NaiveDateTime, tz: Tz) -> Option<DateTime<Utc>> {
    (0..=180).find_map(
        |m| match tz.from_local_datetime(&(local + Duration::minutes(m))) {
            LocalResult::Single(t) | LocalResult::Ambiguous(t, _) => Some(t.with_timezone(&Utc)),
            LocalResult::None => None,
        },
    )
}

/// The next check strictly after `now`; a local time skipped by DST moves to the next valid minute.
pub fn next_run(now: DateTime<Utc>, tz: Tz, times: &[NaiveTime]) -> DateTime<Utc> {
    let today = now.with_timezone(&tz).date_naive();
    (-1..=2)
        .filter_map(|d| today.checked_add_signed(Duration::days(d)))
        .flat_map(|day| {
            times
                .iter()
                .filter_map(move |t| to_utc(day.and_time(*t), tz))
        })
        .filter(|t| *t > now)
        .min()
        .unwrap_or(now + Duration::days(1))
}

/// Starts one check as tracked push work, isolated from the scheduler loop.
pub fn tick(st: &AppState, at: DateTime<Utc>) -> tokio::task::JoinHandle<()> {
    let st2 = st.clone();
    st.push.spawn(async move { run_due(&st2, at).await })
}

/// Wakes at every `NOTIFY_TIMES` in `TZ` and sends due notifications.
pub fn spawn(st: AppState) {
    tokio::spawn(async move {
        let mut floor = st.clock.now();
        loop {
            let now = st.clock.now().max(floor);
            let next = next_run(now, st.cfg.tz, &st.cfg.notify_times);
            let wait = (next - st.clock.now()).to_std().unwrap_or_default();
            tokio::time::sleep(wait).await;
            if let Err(e) = tick(&st, st.clock.now().max(next)).await {
                eprintln!("due check failed: {e}");
            }
            floor = next;
        }
    });
}

struct Group {
    sub: Sub,
    household: String,
    chores: Vec<(i64, String, String)>,
}

/// One check: per subscription and household, one push for the chores newly due in this period.
pub async fn run_due(st: &AppState, now: DateTime<Utc>) {
    let today = now.with_timezone(&st.cfg.tz).date_naive();
    let rows = st
        .db
        .run(move |c| {
            let mut s = c.prepare(&format!(
                "SELECT {SUB_COLS}, ch.household_id, h.name, ch.id, ch.name, ch.interval,
                   (SELECT MAX(co.done_on) FROM completions co WHERE co.chore_id = ch.id AND co.deleted_at IS NULL),
                   ns.last_due_notified_on
                 FROM push_subscriptions s JOIN users u ON u.id = s.user_id AND u.notify_due = 1
                 JOIN memberships m ON m.user_id = s.user_id
                 JOIN households h ON h.id = m.household_id
                 JOIN chores ch ON ch.household_id = m.household_id AND ch.archived_at IS NULL
                 LEFT JOIN notification_state ns ON ns.subscription_id = s.id AND ns.chore_id = ch.id
                 ORDER BY s.id, ch.household_id, ulower(ch.name), ch.id"
            ))?;
            s.query_map([], |r| {
                Ok((
                    Sub::from_row(r, 0)?,
                    r.get::<_, i64>(5)?,
                    r.get::<_, String>(6)?,
                    r.get::<_, i64>(7)?,
                    r.get::<_, String>(8)?,
                    r.get::<_, Option<String>>(9)?,
                    r.get::<_, Option<String>>(10)?,
                    r.get::<_, Option<String>>(11)?,
                ))
            })?
            .collect::<rusqlite::Result<Vec<_>>>()
        })
        .await;
    let rows = match rows {
        Ok(r) => r,
        Err(e) => {
            eprintln!("{e}");
            return;
        }
    };
    let mut groups: BTreeMap<(i64, i64), Group> = BTreeMap::new();
    for (sub, hid, hname, chore, name, interval, last, notified) in rows {
        let Some(period) = super::newly_due(
            today,
            interval.as_deref(),
            last.as_deref(),
            notified.as_deref(),
        ) else {
            continue;
        };
        groups
            .entry((sub.id, hid))
            .or_insert_with(|| Group {
                sub,
                household: hname,
                chores: Vec::new(),
            })
            .chores
            .push((chore, name, period));
    }
    let mut set = tokio::task::JoinSet::new();
    for ((_, hid), g) in groups {
        let names: Vec<String> = g.chores.iter().map(|(_, n, _)| n.clone()).collect();
        let p = due_payload(&g.sub.lang, &g.household, hid, &names);
        let st = st.clone();
        set.spawn(async move { (deliver(&st, &g.sub, &p).await, g) });
    }
    let mut sent = Vec::new();
    for (outcome, g) in join_logged(set).await {
        if outcome == Outcome::Sent {
            sent.extend(g.chores.into_iter().map(|(c, _, p)| (g.sub.id, c, p)));
        }
    }
    if sent.is_empty() {
        return;
    }
    let saved = st
        .db
        .run(move |c| {
            let tx = c.transaction()?;
            for (sub, chore, period) in sent {
                tx.execute(
                    "INSERT INTO notification_state (subscription_id, chore_id, last_due_notified_on)
                     SELECT ?1, ?2, ?3 WHERE EXISTS (SELECT 1 FROM push_subscriptions WHERE id = ?1)
                       AND EXISTS (SELECT 1 FROM chores WHERE id = ?2)
                     ON CONFLICT (subscription_id, chore_id) DO UPDATE SET last_due_notified_on = excluded.last_due_notified_on",
                    params![sub, chore, period],
                )?;
            }
            tx.commit()
        })
        .await;
    if let Err(e) = saved {
        eprintln!("{e}");
    }
}
