pub mod scheduler;
pub mod texts;
pub mod webpush;

use crate::app::AppState;
use openidconnect::reqwest;
use rusqlite::{OptionalExtension, params};
use serde_json::json;
use std::future::Future;
use std::sync::Arc;
use std::sync::atomic::{AtomicU64, AtomicUsize, Ordering};
use std::time::Duration;
use tokio::sync::{Notify, watch};
use tokio::task::{JoinHandle, JoinSet};
use webpush::Vapid;

pub const TTL_SECS: u32 = 12 * 3600;
const MAX_FAILURES: i64 = 5;
const DONE_DELAY: Duration = Duration::from_secs(10);

pub struct Push {
    pub vapid: Vapid,
    http: reqwest::Client,
    inflight: AtomicUsize,
    idle: Notify,
    done_delay_ms: AtomicU64,
    closing: watch::Sender<bool>,
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
            done_delay_ms: AtomicU64::new(DONE_DELAY.as_millis() as u64),
            closing: watch::Sender::new(false),
        }
    }

    /// Wait before a "done" push, so a mark undone in the meantime sends nothing.
    pub fn done_delay(&self) -> Duration {
        Duration::from_millis(self.done_delay_ms.load(Ordering::Relaxed))
    }

    /// Ends pending delays, so `wait_idle` at shutdown sends what is waiting instead of dropping it.
    pub fn shutdown(&self) {
        self.closing.send_replace(true);
    }

    async fn delay_done(&self) {
        let delay = self.done_delay();
        if delay.is_zero() {
            return;
        }
        let mut closing = self.closing.subscribe();
        tokio::select! {
            _ = tokio::time::sleep(delay) => {}
            _ = closing.wait_for(|c| *c) => {}
        }
    }

    pub fn set_done_delay(&self, d: Duration) {
        self.done_delay_ms
            .store(d.as_millis() as u64, Ordering::Relaxed);
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

pub const MAX_PAYLOAD_BYTES: usize = 3500;

/// Serialized push message, cut so the JSON stays within `MAX_PAYLOAD_BYTES` (one encrypted record).
pub fn payload(title: String, body: String, household: i64, tag: String) -> String {
    let url = format!("/households/{household}");
    let fixed = json!({"title": "", "body": "", "url": url, "tag": tag})
        .to_string()
        .len();
    let budget = MAX_PAYLOAD_BYTES.saturating_sub(fixed);
    let title = cut_escaped(&title, budget / 3);
    let body = cut_escaped(&body, budget - texts::json_len(&title));
    json!({"title": title, "body": body, "url": url, "tag": tag}).to_string()
}

fn cut_escaped(s: &str, max: usize) -> String {
    if texts::json_len(s) <= max {
        return s.to_string();
    }
    let mut out = String::new();
    let mut used = "…".len();
    for ch in s.chars() {
        let mut b = [0u8; 4];
        let len = texts::json_len(ch.encode_utf8(&mut b));
        if used + len > max {
            break;
        }
        used += len;
        out.push(ch);
    }
    out.push('…');
    out
}

/// Sends one push; a subscription the push service reports gone (404/410) is deleted.
pub async fn deliver(st: &AppState, sub: &Sub, payload: &str) -> Outcome {
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
        _ if payload.len() > webpush::MAX_PLAINTEXT => {
            eprintln!("push to {host} skipped: message too large");
            return Outcome::Failed;
        }
        (Some(k), Some(a)) => webpush::encrypt(&k, &a, payload.as_bytes()),
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

/// Tells the other members' devices about a new completion, in the background, after `done_delay` unless it was undone.
pub fn completion_created(st: &AppState, completion: String) {
    let st2 = st.clone();
    st.push.spawn(async move {
        st2.push.delay_done().await;
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
                     WHERE co.id = ?1 AND co.deleted_at IS NULL",
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
    use serde_json::Value;

    #[test]
    fn payload_json_stays_within_limit() {
        let nasty = "\"\u{1}\u{7f}\\<".repeat(2000);
        let p = payload(nasty.clone(), nasty.clone(), 7, "due-7".into());
        assert!(p.len() <= MAX_PAYLOAD_BYTES, "{}", p.len());
        let v: Value = serde_json::from_str(&p).unwrap();
        assert_eq!(v["url"], "/households/7");
        assert_eq!(v["tag"], "due-7");
        assert!(v["body"].as_str().unwrap().ends_with('…'));
        let small = payload("T".into(), "B \"q\"".into(), 1, "x".into());
        let v: Value = serde_json::from_str(&small).unwrap();
        assert_eq!(
            (v["title"].as_str(), v["body"].as_str()),
            (Some("T"), Some("B \"q\""))
        );
    }

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
