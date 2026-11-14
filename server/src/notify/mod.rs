pub mod scheduler;
pub mod texts;
pub mod webpush;

use crate::app::AppState;
use openidconnect::reqwest;
use rusqlite::{OptionalExtension, params};
use serde_json::{Value, json};
use std::future::Future;
use std::sync::Arc;
use std::sync::atomic::{AtomicUsize, Ordering};
use std::time::Duration;
use tokio::sync::Notify;
use tokio::task::{JoinHandle, JoinSet};
use webpush::Vapid;

pub const TTL_SECS: u32 = 12 * 3600;
const MAX_FAILURES: i64 = 5;

pub struct Push {
    pub vapid: Vapid,
    http: reqwest::Client,
    inflight: AtomicUsize,
    idle: Notify,
}

struct Inflight(Arc<Push>);

impl Drop for Inflight {
    fn drop(&mut self) {
        if self.0.inflight.fetch_sub(1, Ordering::SeqCst) == 1 {
            self.0.idle.notify_waiters();
        }
    }
}

impl Push {
    pub fn new(vapid: Vapid) -> Push {
        let http = reqwest::ClientBuilder::new()
            .redirect(reqwest::redirect::Policy::none())
            .connect_timeout(Duration::from_secs(5))
            .timeout(Duration::from_secs(15))
            .build()
            .expect("http client");
        Push {
            vapid,
            http,
            inflight: AtomicUsize::new(0),
            idle: Notify::new(),
        }
    }

    /// Runs push work in the background; `wait_idle` waits for it.
    pub fn spawn<T: Send + 'static>(
        self: &Arc<Self>,
        f: impl Future<Output = T> + Send + 'static,
    ) -> JoinHandle<T> {
        self.inflight.fetch_add(1, Ordering::SeqCst);
        let guard = Inflight(self.clone());
        tokio::spawn(async move {
            let _guard = guard;
            f.await
        })
    }

    /// Waits until no background push work is left.
    pub async fn wait_idle(&self) {
        loop {
            let idle = self.idle.notified();
            tokio::pin!(idle);
            idle.as_mut().enable();
            if self.inflight.load(Ordering::SeqCst) == 0 {
                return;
            }
            idle.await;
        }
    }
}

#[derive(Debug, Clone)]
pub struct Sub {
    pub id: i64,
    pub endpoint: String,
    pub p256dh: String,
    pub auth: String,
    pub lang: String,
}

impl Sub {
    pub(crate) fn from_row(r: &rusqlite::Row, o: usize) -> rusqlite::Result<Sub> {
        Ok(Sub {
            id: r.get(o)?,
            endpoint: r.get(o + 1)?,
            p256dh: r.get(o + 2)?,
            auth: r.get(o + 3)?,
            lang: r.get(o + 4)?,
        })
    }
}

pub(crate) const SUB_COLS: &str = "s.id, s.endpoint, s.p256dh, s.auth, s.lang";

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum Outcome {
    Sent,
    Gone,
    Failed,
}

/// Results of all tasks; a panicked task is logged and skipped.
pub async fn join_logged<T: 'static>(mut set: JoinSet<T>) -> Vec<T> {
    let mut out = Vec::new();
    while let Some(r) = set.join_next().await {
        match r {
            Ok(v) => out.push(v),
            Err(e) => eprintln!("push task failed: {e}"),
        }
    }
    out
}

async fn set_failures(st: &AppState, id: i64, ok: bool) {
    let r = st
        .db
        .run(move |c| {
            if ok {
                c.execute(
                    "UPDATE push_subscriptions SET failures = 0 WHERE id = ?1 AND failures != 0",
                    [id],
                )?;
            } else {
                c.execute(
                    "UPDATE push_subscriptions SET failures = failures + 1 WHERE id = ?1",
                    [id],
                )?;
                c.execute(
                    "DELETE FROM push_subscriptions WHERE id = ?1 AND failures >= ?2",
                    params![id, MAX_FAILURES],
                )?;
            }
            Ok(())
        })
        .await;
    if let Err(e) = r {
        eprintln!("{e}");
    }
}

pub fn payload(title: String, body: String, household: i64, tag: String) -> Value {
    json!({"title": title, "body": body, "url": format!("/households/{household}"), "tag": tag})
}

/// Sends one push; a subscription the push service reports gone (404/410) is deleted.
pub async fn deliver(st: &AppState, sub: &Sub, payload: &Value) -> Outcome {
    let host = url::Url::parse(&sub.endpoint)
        .ok()
        .and_then(|u| u.host_str().map(str::to_string))
        .unwrap_or_default();
    let Some(endpoint) = webpush::check_endpoint(
        &sub.endpoint,
        &st.cfg.push_allowed_hosts,
        st.cfg.push_allow_loopback_http,
    ) else {
        eprintln!("push to {host} skipped: endpoint not allowed");
        set_failures(st, sub.id, false).await;
        return Outcome::Failed;
    };
    let body = match (
        webpush::b64_decode(&sub.p256dh),
        webpush::b64_decode(&sub.auth),
    ) {
        (Some(k), Some(a)) => webpush::encrypt(&k, &a, payload.to_string().as_bytes()),
        _ => None,
    };
    let Some(body) = body else {
        eprintln!("push to {host} skipped: subscription keys unusable");
        set_failures(st, sub.id, false).await;
        return Outcome::Failed;
    };
    let auth = st
        .push
        .vapid
        .authorization(&endpoint, st.cfg.public_url.as_str(), st.clock.now());
    let res = st
        .push
        .http
        .post(endpoint)
        .header("TTL", TTL_SECS.to_string())
        .header("Urgency", "normal")
        .header("Content-Encoding", "aes128gcm")
        .header("Content-Type", "application/octet-stream")
        .header("Authorization", auth)
        .body(body)
        .send()
        .await;
    match res.map(|r| r.status().as_u16()) {
        Ok(200..=299) => {
            set_failures(st, sub.id, true).await;
            Outcome::Sent
        }
        Ok(404 | 410) => {
            let id = sub.id;
            if let Err(e) = st
                .db
                .run(move |c| c.execute("DELETE FROM push_subscriptions WHERE id = ?1", [id]))
                .await
            {
                eprintln!("{e}");
            }
            Outcome::Gone
        }
        Ok(s) => {
            eprintln!("push to {host} failed: status {s}");
            set_failures(st, sub.id, false).await;
            Outcome::Failed
        }
        Err(e) => {
            eprintln!("push to {host} failed: {}", e.without_url());
            Outcome::Failed
        }
    }
}

/// Tells the other members' devices about a new completion, in the background.
pub fn completion_created(st: &AppState, completion: String) {
    let st2 = st.clone();
    st.push.spawn(async move {
        if let Err(e) = send_done(&st2, completion).await {
            eprintln!("{e}");
        }
    });
}

async fn send_done(st: &AppState, completion: String) -> crate::db::Result<()> {
    let found = st
        .db
        .run(move |c| {
            let Some((hid, chore, who, uid)) = c
                .query_row(
                    "SELECT ch.household_id, ch.name, u.display_name, co.user_id
                     FROM completions co JOIN chores ch ON ch.id = co.chore_id JOIN users u ON u.id = co.user_id
                     WHERE co.id = ?1",
                    [&completion],
                    |r| Ok((r.get::<_, i64>(0)?, r.get::<_, String>(1)?, r.get::<_, String>(2)?, r.get::<_, i64>(3)?)),
                )
                .optional()?
            else {
                return Ok(None);
            };
            let mut s = c.prepare(&format!(
                "SELECT {SUB_COLS} FROM push_subscriptions s JOIN users u ON u.id = s.user_id
                 JOIN memberships m ON m.user_id = s.user_id AND m.household_id = ?1
                 WHERE s.user_id != ?2 AND u.notify_done = 1 ORDER BY s.id"
            ))?;
            let subs = s
                .query_map(params![hid, uid], |r| Sub::from_row(r, 0))?
                .collect::<rusqlite::Result<Vec<_>>>()?;
            Ok(Some((hid, chore, who, completion, subs)))
        })
        .await?;
    let Some((hid, chore, who, completion, subs)) = found else {
        return Ok(());
    };
    let mut set = JoinSet::new();
    for sub in subs {
        let (title, body) = texts::done(&sub.lang, &who, &chore);
        let p = payload(title, body, hid, format!("done-{completion}"));
        let st = st.clone();
        set.spawn(async move { deliver(&st, &sub, &p).await });
    }
    join_logged(set).await;
    Ok(())
}

#[cfg(test)]
mod tests {
    use super::*;

    #[tokio::test]
    async fn panicking_task_is_logged_not_fatal() {
        let mut set = tokio::task::JoinSet::new();
        set.spawn(async { 1 });
        set.spawn(async { panic!("boom") });
        set.spawn(async { 2 });
        let mut got = join_logged(set).await;
        got.sort();
        assert_eq!(got, [1, 2]);
    }
}
