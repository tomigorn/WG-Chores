mod support;
use axum::{
    body::Bytes,
    extract::Path,
    http::{HeaderMap, StatusCode},
};
use base64::{Engine, engine::general_purpose::URL_SAFE_NO_PAD};
use chrono::{DateTime, NaiveTime, TimeZone, Utc};
use p256::ecdsa::{Signature, VerifyingKey, signature::Verifier};
use serde_json::{Value, json};
use std::collections::HashMap;
use std::sync::{Arc, Mutex};
use support::*;
use wg_chores::notify::{scheduler, webpush};

#[derive(Clone)]
struct Got {
    name: String,
    headers: HeaderMap,
    body: Vec<u8>,
}

#[derive(Clone, Default)]
struct Fake {
    got: Arc<Mutex<Vec<Got>>>,
    status: Arc<Mutex<HashMap<String, u16>>>,
    base: String,
}

impl Fake {
    async fn start() -> Fake {
        let mut fake = Fake::default();
        let f = fake.clone();
        let app = axum::Router::new().route(
            "/push/{name}",
            axum::routing::post(
                move |Path(name): Path<String>, headers: HeaderMap, body: Bytes| {
                    let f = f.clone();
                    async move {
                        let s = f.status.lock().unwrap().get(&name).copied().unwrap_or(201);
                        f.got.lock().unwrap().push(Got {
                            name,
                            headers,
                            body: body.to_vec(),
                        });
                        StatusCode::from_u16(s).unwrap()
                    }
                },
            ),
        );
        let l = tokio::net::TcpListener::bind("127.0.0.1:0").await.unwrap();
        fake.base = format!("http://{}", l.local_addr().unwrap());
        tokio::spawn(async move { axum::serve(l, app).await.unwrap() });
        fake
    }

    fn take(&self) -> Vec<Got> {
        std::mem::take(&mut *self.got.lock().unwrap())
    }

    fn fail(&self, name: &str, status: u16) {
        self.status.lock().unwrap().insert(name.into(), status);
    }
}

struct Device {
    name: String,
    sk: p256::SecretKey,
    auth: [u8; 16],
}

impl Device {
    fn new(name: &str, n: u8) -> Device {
        Device {
            name: name.into(),
            sk: p256::SecretKey::from_slice(&[n; 32]).unwrap(), // leak-check: ok
            auth: [n; 16],
        }
    }

    fn public(&self) -> Vec<u8> {
        self.sk.public_key().to_sec1_bytes().to_vec()
    }

    fn json(&self, fake: &Fake, lang: &str) -> Value {
        json!({
            "endpoint": format!("{}/push/{}", fake.base, self.name),
            "expirationTime": null,
            "keys": {"p256dh": URL_SAFE_NO_PAD.encode(self.public()), "auth": URL_SAFE_NO_PAD.encode(self.auth)},
            "lang": lang,
        })
    }

    fn open(&self, body: &[u8]) -> Value {
        let pk = p256::PublicKey::from_sec1_bytes(&body[21..86]).unwrap();
        let shared = p256::ecdh::diffie_hellman(self.sk.to_nonzero_scalar(), pk.as_affine());
        let plain = webpush::decrypt(
            &shared.raw_secret_bytes()[..],
            &self.public(),
            &self.auth,
            body,
        )
        .expect("decrypts");
        serde_json::from_slice(&plain).unwrap()
    }
}

async fn subscribe(c: &mut Client, fake: &Fake, d: &Device, lang: &str) {
    let r = c
        .post("/api/v1/push/subscriptions", d.json(fake, lang))
        .await;
    assert_eq!(r.status, 204, "{:?}", r.body);
}

fn names(got: &[Got]) -> Vec<String> {
    let mut v: Vec<String> = got.iter().map(|g| g.name.clone()).collect();
    v.sort();
    v
}

fn at(s: &str) -> DateTime<Utc> {
    s.parse().unwrap()
}

struct World {
    app: TestApp,
    fake: Fake,
    a: Client,
    b: Client,
    h: i64,
}

async fn world() -> World {
    let app = TestApp::new();
    let fake = Fake::start().await;
    let (mut a, _) = signup(&app, "anna").await;
    let (b, _) = signup(&app, "ben").await;
    let h = new_household(&mut a, "Home").await;
    add_to_household(&mut a, h, "ben").await;
    World { app, fake, a, b, h }
}

async fn run_due(w: &World, now: &str) -> Vec<Got> {
    scheduler::run_due(&w.app.state, at(now)).await;
    w.fake.take()
}

#[tokio::test]
async fn done_push_goes_to_others_not_self() {
    let mut w = world().await;
    let c = new_chore(&mut w.a, w.h, json!({"name": "Bins", "interval": "1w"})).await;
    let (anna, ben, ben2) = (
        Device::new("anna", 1),
        Device::new("ben", 2),
        Device::new("ben2", 3),
    );
    subscribe(&mut w.a, &w.fake, &anna, "en").await;
    subscribe(&mut w.b, &w.fake, &ben, "en").await;
    subscribe(&mut w.b, &w.fake, &ben2, "de").await;
    let outsider = signup(&w.app, "carl").await.0;
    let mut carl = outsider;
    subscribe(&mut carl, &w.fake, &Device::new("carl", 4), "en").await;

    assert_eq!(done(&mut w.a, c, 1, "2025-12-31").await.status, 201);
    w.app.state.push.wait_idle().await;
    let got = w.fake.take();
    assert_eq!(names(&got), ["ben", "ben2"]);

    let key = w.a.get("/api/v1/push/key").await;
    assert_eq!(key.status, 200);
    let k = key.body["key"].as_str().unwrap().to_string();
    assert_eq!(URL_SAFE_NO_PAD.decode(&k).unwrap().len(), 65);
    for g in &got {
        let d = if g.name == "ben" { &ben } else { &ben2 };
        let p = d.open(&g.body);
        assert_eq!(p["title"], "Name anna");
        assert_eq!(p["body"], "✓ Bins");
        assert_eq!(p["url"], format!("/households/{}", w.h));
        assert_eq!(p["tag"], format!("done-{}", uuid(1)));
        assert_eq!(g.headers["ttl"], "43200");
        assert_eq!(g.headers["urgency"], "normal");
        assert_eq!(g.headers["content-encoding"], "aes128gcm");
        let auth = g.headers["authorization"].to_str().unwrap();
        let (t, kk) = auth
            .strip_prefix("vapid t=")
            .unwrap()
            .split_once(", k=")
            .unwrap();
        assert_eq!(kk, k);
        let (signed, sig) = t.rsplit_once('.').unwrap();
        let vk = VerifyingKey::from_sec1_bytes(&URL_SAFE_NO_PAD.decode(&k).unwrap()).unwrap();
        let sig = Signature::from_slice(&URL_SAFE_NO_PAD.decode(sig).unwrap()).unwrap();
        vk.verify(signed.as_bytes(), &sig).expect("valid ES256");
        let claims: Value = serde_json::from_slice(
            &URL_SAFE_NO_PAD
                .decode(signed.split_once('.').unwrap().1)
                .unwrap(),
        )
        .unwrap();
        assert_eq!(claims["aud"], w.fake.base);
        assert_eq!(claims["sub"], format!("{ORIGIN}/"));
        let exp = claims["exp"].as_i64().unwrap();
        let now = Utc
            .with_ymd_and_hms(2026, 1, 1, 12, 0, 0)
            .unwrap()
            .timestamp();
        assert!(exp > now && exp <= now + 24 * 3600, "{exp}");
    }

    assert_eq!(done(&mut w.a, c, 1, "2025-12-31").await.status, 200);
    assert_eq!(done(&mut w.b, c, 2, "2025-12-31").await.status, 201);
    w.app.state.push.wait_idle().await;
    assert_eq!(names(&w.fake.take()), ["anna"]);
}

#[tokio::test]
async fn due_push_once_per_period() {
    let mut w = world().await;
    let c = new_chore(&mut w.a, w.h, json!({"name": "Bins", "interval": "1w"})).await;
    new_chore(
        &mut w.a,
        w.h,
        json!({"name": "Never done", "interval": "1w"}),
    )
    .await;
    let anna = Device::new("anna", 1);
    subscribe(&mut w.a, &w.fake, &anna, "en").await;
    assert_eq!(done(&mut w.a, c, 1, "2025-12-28").await.status, 201);
    w.app.state.push.wait_idle().await;
    assert!(run_due(&w, "2026-01-01T12:00:00Z").await.is_empty());
    let got = run_due(&w, "2026-01-04T12:00:00Z").await;
    assert_eq!(names(&got), ["anna"]);
    let p = anna.open(&got[0].body);
    assert_eq!(p["title"], "Bins is due");
    assert_eq!(p["body"], "Home");
    assert_eq!(p["tag"], format!("due-{}", w.h));
    assert_eq!(p["url"], format!("/households/{}", w.h));
    assert_eq!(got[0].headers["ttl"], "43200");
    assert!(run_due(&w, "2026-01-04T17:00:00Z").await.is_empty());
    assert!(run_due(&w, "2026-01-20T12:00:00Z").await.is_empty());
}

#[tokio::test]
async fn due_pushes_combined() {
    let mut w = world().await;
    let anna = Device::new("anna", 1);
    let ben = Device::new("ben", 2);
    subscribe(&mut w.a, &w.fake, &anna, "en").await;
    subscribe(&mut w.b, &w.fake, &ben, "de").await;
    for (i, name) in ["Water plants", "clean toilet", "Dust", "Fresh one", "Gone"]
        .iter()
        .enumerate()
    {
        let c = new_chore(&mut w.a, w.h, json!({"name": name, "interval": "1w"})).await;
        let d = if *name == "Fresh one" {
            "2025-12-31"
        } else {
            "2025-12-20"
        };
        assert_eq!(done(&mut w.a, c, i as u64 + 1, d).await.status, 201);
        if *name == "Gone" {
            let r =
                w.a.post(&format!("/api/v1/chores/{c}/archive"), json!({}))
                    .await;
            assert_eq!(r.status, 200);
        }
    }
    w.app.state.push.wait_idle().await;
    w.fake.take();
    let got = run_due(&w, "2026-01-01T12:00:00Z").await;
    assert_eq!(names(&got), ["anna", "ben"]);
    for g in &got {
        let p = if g.name == "anna" {
            anna.open(&g.body)
        } else {
            ben.open(&g.body)
        };
        let title = if g.name == "anna" {
            "3 chores are due"
        } else {
            "3 Aufgaben sind fällig"
        };
        assert_eq!(p["title"], title);
        assert_eq!(p["body"], "clean toilet, Dust, Water plants");
    }
}

#[tokio::test]
async fn done_again_rearms_due() {
    let mut w = world().await;
    let c = new_chore(&mut w.a, w.h, json!({"name": "Bins", "interval": "1w"})).await;
    subscribe(&mut w.a, &w.fake, &Device::new("anna", 1), "en").await;
    assert_eq!(done(&mut w.a, c, 1, "2025-12-20").await.status, 201);
    w.app.state.push.wait_idle().await;
    assert_eq!(run_due(&w, "2026-01-01T12:00:00Z").await.len(), 1);
    assert!(run_due(&w, "2026-01-01T18:00:00Z").await.is_empty());
    assert_eq!(done(&mut w.b, c, 2, "2025-12-24").await.status, 201);
    w.app.state.push.wait_idle().await;
    w.fake.take();
    assert_eq!(run_due(&w, "2026-01-01T19:00:00Z").await.len(), 1);
    assert!(run_due(&w, "2026-01-02T14:00:00Z").await.is_empty());
}

#[tokio::test]
async fn gone_subscription_removed() {
    let mut w = world().await;
    let c = new_chore(&mut w.a, w.h, json!({"name": "Bins", "interval": "1w"})).await;
    for (n, name) in ["gone", "missing", "broken", "fine"].iter().enumerate() {
        subscribe(&mut w.a, &w.fake, &Device::new(name, n as u8 + 1), "en").await;
    }
    w.fake.fail("gone", 410);
    w.fake.fail("missing", 404);
    w.fake.fail("broken", 500);
    assert_eq!(done(&mut w.b, c, 1, "2025-12-20").await.status, 201);
    w.app.state.push.wait_idle().await;
    assert_eq!(names(&w.fake.take()), ["broken", "fine", "gone", "missing"]);
    let left = w
        .app
        .db(|c| {
            let mut s = c.prepare("SELECT endpoint FROM push_subscriptions ORDER BY endpoint")?;
            s.query_map([], |r| r.get::<_, String>(0))?
                .collect::<rusqlite::Result<Vec<_>>>()
        })
        .await;
    let left: Vec<&str> = left.iter().map(|e| e.rsplit('/').next().unwrap()).collect();
    assert_eq!(left, ["broken", "fine"]);
    assert_eq!(
        names(&run_due(&w, "2026-01-01T12:00:00Z").await),
        ["broken", "fine"]
    );
    assert_eq!(
        names(&run_due(&w, "2026-01-01T19:00:00Z").await),
        ["broken"]
    );
}

#[tokio::test]
async fn respects_user_toggles() {
    let mut w = world().await;
    let c = new_chore(&mut w.a, w.h, json!({"name": "Bins", "interval": "1w"})).await;
    subscribe(&mut w.a, &w.fake, &Device::new("anna", 1), "en").await;
    subscribe(&mut w.b, &w.fake, &Device::new("ben", 2), "en").await;
    let me = w.b.get("/api/v1/me").await;
    assert_eq!(me.body["notify_due"], true);
    assert_eq!(me.body["notify_done"], true);
    let r = w.b.patch("/api/v1/me", json!({"notify_done": false})).await;
    assert_eq!(r.status, 200, "{:?}", r.body);
    assert_eq!(r.body["notify_done"], false);
    assert_eq!(r.body["notify_due"], true);
    let r = w.a.patch("/api/v1/me", json!({"notify_due": false})).await;
    assert_eq!(r.status, 200);
    assert_eq!(done(&mut w.a, c, 1, "2025-12-20").await.status, 201);
    w.app.state.push.wait_idle().await;
    assert!(w.fake.take().is_empty());
    assert_eq!(names(&run_due(&w, "2026-01-01T12:00:00Z").await), ["ben"]);
    let r = w.a.patch("/api/v1/me", json!({"notify_due": "yes"})).await;
    assert_eq!(r.status, 400);
}

#[tokio::test]
async fn sso_user_can_set_toggles() {
    let app = TestApp::new();
    let id = app.seed_sso_user("Jane Doe").await;
    let mut c = app.client();
    login_as(&app, &mut c, id).await;
    let r = c.patch("/api/v1/me", json!({"notify_due": false})).await;
    assert_eq!(r.status, 200, "{:?}", r.body);
    assert_eq!(r.body["notify_due"], false);
    let r = c.patch("/api/v1/me", json!({"display_name": "X"})).await;
    assert_eq!(r.status, 403);
}

#[tokio::test]
async fn scheduler_uses_tz() {
    let zurich = chrono_tz::Europe::Zurich;
    let t = |s: &str| NaiveTime::parse_from_str(s, "%H:%M").unwrap();
    let times = [t("19:00"), t("14:00")];
    let next = |now: &str, times: &[NaiveTime]| scheduler::next_run(at(now), zurich, times);
    assert_eq!(
        next("2026-01-01T12:00:00Z", &times),
        at("2026-01-01T13:00:00Z")
    );
    assert_eq!(
        next("2026-01-01T13:00:00Z", &times),
        at("2026-01-01T18:00:00Z")
    );
    assert_eq!(
        next("2026-01-01T18:30:00Z", &times),
        at("2026-01-02T13:00:00Z")
    );
    assert_eq!(
        next("2026-07-01T12:30:00Z", &times),
        at("2026-07-01T17:00:00Z")
    );
    let night = [t("02:30")];
    assert_eq!(
        next("2026-03-28T12:00:00Z", &night),
        at("2026-03-29T01:00:00Z")
    );
    assert_eq!(
        next("2026-10-24T12:00:00Z", &night),
        at("2026-10-25T00:30:00Z")
    );

    for (tz, pushes) in [("Europe/Zurich", 1), ("UTC", 0)] {
        let app = TestApp::with_env(&[("TZ", tz)]);
        let fake = Fake::start().await;
        let (mut a, _) = signup(&app, "anna").await;
        let h = new_household(&mut a, "Home").await;
        let c = new_chore(&mut a, h, json!({"name": "Bins", "interval": "1w"})).await;
        subscribe(&mut a, &fake, &Device::new("anna", 1), "en").await;
        assert_eq!(done(&mut a, c, 1, "2025-12-26").await.status, 201);
        app.state.push.wait_idle().await;
        scheduler::run_due(&app.state, at("2026-01-01T23:30:00Z")).await;
        assert_eq!(fake.take().len(), pushes, "{tz}");
    }
}

async fn owner_and_lang(app: &TestApp) -> (i64, String) {
    app.db(|c| {
        c.query_row("SELECT user_id, lang FROM push_subscriptions", [], |r| {
            Ok((r.get(0)?, r.get(1)?))
        })
    })
    .await
}

#[tokio::test]
async fn subscription_validation_and_rebinding() {
    let mut w = world().await;
    let fake = w.fake.clone();
    let d = Device::new("dev", 1);
    let path = "/api/v1/push/subscriptions";
    let with = |f: &dyn Fn(&mut Value)| {
        let mut v = d.json(&fake, "en");
        f(&mut v);
        v
    };
    let bad = [
        with(&|v| v["endpoint"] = json!("http://push.example.com/x")),
        with(&|v| v["endpoint"] = json!("ftp://127.0.0.1/x")),
        with(&|v| v["endpoint"] = json!(format!("https://push.example.com/{}", "a".repeat(2048)))),
        with(&|v| v["endpoint"] = json!("https://10.0.0.1/x")),
        with(&|v| v["keys"]["p256dh"] = json!(URL_SAFE_NO_PAD.encode([4u8; 64]))),
        with(&|v| v["keys"]["p256dh"] = json!(URL_SAFE_NO_PAD.encode([4u8; 65]))),
        with(&|v| v["keys"]["auth"] = json!(URL_SAFE_NO_PAD.encode([1u8; 15]))),
        with(&|v| v["keys"]["auth"] = json!("!!!")),
        with(&|v| v["lang"] = json!("fr")),
    ];
    for b in bad {
        let r = w.a.post(path, b.clone()).await;
        assert_eq!(r.status, 400, "{b}");
    }
    let mut no_lang = d.json(&fake, "en");
    no_lang.as_object_mut().unwrap().remove("lang");
    assert_eq!(w.a.post(path, no_lang).await.status, 204);
    let (anna_id, l) = owner_and_lang(&w.app).await;
    assert_eq!(l, "en");
    subscribe(&mut w.b, &fake, &d, "de").await;
    let (owner, l) = owner_and_lang(&w.app).await;
    assert_ne!(owner, anna_id);
    assert_eq!(l, "de");
    let gone = json!({"endpoint": format!("{}/push/dev", fake.base)});
    assert_eq!(w.a.delete(path, gone.clone()).await.status, 404);
    assert_eq!(w.b.delete(path, gone.clone()).await.status, 204);
    assert_eq!(w.b.delete(path, gone).await.status, 404);
    let mut anon = w.app.client();
    assert_eq!(anon.post(path, d.json(&fake, "en")).await.status, 401);
    assert_eq!(anon.get("/api/v1/push/key").await.status, 200);
}
