mod support;
use base64::{Engine, engine::general_purpose::URL_SAFE_NO_PAD};
use chrono::Duration;
use serde_json::{Value, json};
use sha2::{Digest, Sha256};
use support::fake_oidc::FakeOidc;
use support::*;
use wg_chores::clock::Clock;

const SESSION: &str = "__Host-wgc_session";
const VERIFIER: &str = "dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk";

fn challenge(verifier: &str) -> String {
    URL_SAFE_NO_PAD.encode(Sha256::digest(verifier.as_bytes()))
}

fn app_client(app: &TestApp, token: &str) -> Client {
    let mut c = app.client();
    c.csrf = false;
    c.origin = None;
    c.bearer = Some(token.into());
    c
}

async fn android_login(app: &TestApp, user: &str, device: Option<&str>) -> Resp {
    let mut c = app.client();
    let mut body = json!({"username": user, "password": PW, "client": "android"});
    if let Some(d) = device {
        body["device_name"] = d.into();
    }
    c.post("/api/v1/auth/login", body).await
}

async fn token_for(app: &TestApp, user: &str) -> String {
    let r = android_login(app, user, Some("Pixel")).await;
    assert_eq!(r.status, 200, "{:?}", r.body);
    r.body["token"].as_str().unwrap().to_string()
}

async fn device_names(app: &TestApp) -> Vec<Option<String>> {
    app.db(|c| {
        c.prepare("SELECT device_name FROM sessions WHERE kind = 'android' ORDER BY rowid")?
            .query_map([], |r| r.get(0))?
            .collect()
    })
    .await
}

#[tokio::test]
async fn android_login_returns_token_without_cookie() {
    let app = TestApp::new();
    let (_, id) = signup(&app, "anna").await;
    let r = android_login(&app, "anna", Some("Pixel 8")).await;
    assert_eq!(r.status, 200);
    assert!(r.set_cookies().is_empty(), "{:?}", r.set_cookies());
    let bearer = r.body["token"].as_str().unwrap();
    assert_eq!(URL_SAFE_NO_PAD.decode(bearer).unwrap().len(), 32);
    assert_eq!(r.body["user"]["id"].as_i64(), Some(id));
    let mut c = app_client(&app, bearer);
    let me = c.get("/api/v1/me").await;
    assert_eq!(me.status, 200);
    assert_eq!(me.body["id"].as_i64(), Some(id));
    assert!(me.set_cookies().is_empty());
    let kind: String = app
        .db(move |c| {
            c.query_row(
                "SELECT kind FROM sessions WHERE user_id = ?1 ORDER BY rowid DESC",
                [id],
                |r| r.get(0),
            )
        })
        .await;
    assert_eq!(kind, "android");
}

#[tokio::test]
async fn device_name_cleaned_cut_and_defaulted() {
    let app = TestApp::new();
    signup(&app, "anna").await;
    android_login(&app, "anna", None).await;
    android_login(&app, "anna", Some("  \u{202E}My\u{0} phone  ")).await;
    android_login(&app, "anna", Some(&"x".repeat(80))).await;
    android_login(&app, "anna", Some("   ")).await;
    assert_eq!(
        device_names(&app).await,
        vec![
            Some("Android".into()),
            Some("My phone".into()),
            Some("x".repeat(60)),
            Some("Android".into()),
        ]
    );
}

#[tokio::test]
async fn unknown_client_rejected() {
    let app = TestApp::new();
    signup(&app, "anna").await;
    let mut c = app.client();
    let r = c
        .post(
            "/api/v1/auth/login",
            json!({"username": "anna", "password": PW, "client": "ios"}),
        )
        .await;
    assert_eq!(
        (r.status, r.body["error"].as_str()),
        (400, Some("invalid_client"))
    );
}

#[tokio::test]
async fn android_register_returns_token() {
    let app = TestApp::new();
    let mut c = app.client();
    let r = c
        .post(
            "/api/v1/auth/register",
            json!({"username": "bert", "password": PW, "display_name": "Bert", "client": "android", "device_name": "Tablet"}),
        )
        .await;
    assert_eq!(r.status, 201, "{:?}", r.body);
    assert!(r.set_cookies().is_empty());
    assert_eq!(r.body["user"]["username"], "bert");
    let mut a = app_client(&app, r.body["token"].as_str().unwrap());
    assert_eq!(a.get("/api/v1/me").await.status, 200);
    assert_eq!(device_names(&app).await, vec![Some("Tablet".into())]);
}

#[tokio::test]
async fn bearer_needs_no_csrf_header_but_cookie_still_does() {
    let app = TestApp::new();
    let (mut web, _) = signup(&app, "anna").await;
    let bearer = token_for(&app, "anna").await;
    let mut a = app_client(&app, &bearer);
    let r = a.patch("/api/v1/me", json!({"notify_due": false})).await;
    assert_eq!(r.status, 200, "{:?}", r.body);
    assert!(r.set_cookies().is_empty());
    web.csrf = false;
    let r = web.patch("/api/v1/me", json!({"notify_due": true})).await;
    assert_eq!(r.status, 403);
}

#[tokio::test]
async fn cookie_and_bearer_together_rejected() {
    let app = TestApp::new();
    let (web, _) = signup(&app, "anna").await;
    let bearer = token_for(&app, "anna").await;
    let mut both = app_client(&app, &bearer);
    both.jar = web.jar.clone();
    let r = both.get("/api/v1/me").await;
    assert_eq!(
        (r.status, r.body["error"].as_str()),
        (400, Some("ambiguous_auth"))
    );
}

#[tokio::test]
async fn wrong_kind_or_bad_bearer_is_401() {
    let app = TestApp::new();
    let (web, _) = signup(&app, "anna").await;
    let bearer = token_for(&app, "anna").await;
    let web_sid = web.jar[SESSION].clone();
    for t in [web_sid.as_str(), "nonsense", ""] {
        let mut a = app_client(&app, t);
        assert_eq!(a.get("/api/v1/me").await.status, 401, "{t}");
    }
    let mut c = app.client();
    c.jar.insert(SESSION.into(), bearer);
    assert_eq!(c.get("/api/v1/me").await.status, 401);
}

#[tokio::test]
async fn bearer_logout_ends_only_that_device_without_cookie() {
    let app = TestApp::new();
    let (mut web, _) = signup(&app, "anna").await;
    let bearer = token_for(&app, "anna").await;
    let mut a = app_client(&app, &bearer);
    let r = a
        .send(axum::http::Method::POST, "/api/v1/auth/logout", None)
        .await;
    assert_eq!(r.status, 204);
    assert!(r.set_cookies().is_empty());
    assert_eq!(a.get("/api/v1/me").await.status, 401);
    assert_eq!(web.get("/api/v1/me").await.status, 200);
}

#[tokio::test]
async fn bearer_session_of_sso_user_ends_after_30_days() {
    let app = TestApp::new();
    let id = app.seed_sso_user("Sue").await;
    let bearer =
        wg_chores::auth::session::create_device(&app.state.db, &*app.state.clock, id, "Pixel")
            .await
            .unwrap();
    let mut a = app_client(&app, &bearer);
    for _ in 0..3 {
        app.clock.advance(Duration::days(9));
        assert_eq!(a.get("/api/v1/me").await.status, 200);
    }
    app.clock.advance(Duration::days(4));
    assert_eq!(a.get("/api/v1/me").await.status, 401);
}

#[tokio::test]
async fn devices_listed_and_signed_out() {
    let app = TestApp::new();
    let (mut web, _) = signup(&app, "anna").await;
    let bearer = token_for(&app, "anna").await;
    let (mut other, _) = signup(&app, "bert").await;
    let mut a = app_client(&app, &bearer);
    let r = a.get("/api/v1/me/devices").await;
    assert_eq!(r.status, 200);
    let list = r.body.as_array().unwrap().clone();
    assert_eq!(list.len(), 2, "{list:?}");
    let android = list.iter().find(|d| d["kind"] == "android").unwrap();
    let webd = list.iter().find(|d| d["kind"] == "web").unwrap();
    assert_eq!(android["device_name"], "Pixel");
    assert_eq!(android["current"], true);
    assert_eq!(webd["current"], false);
    assert_eq!(webd["device_name"], Value::Null);
    for k in ["created_at", "last_seen_at"] {
        assert!(android[k].is_string(), "{k}");
    }
    let hashes: Vec<String> = app
        .db(|c| {
            c.prepare("SELECT token_hash FROM sessions")?
                .query_map([], |r| r.get(0))?
                .collect()
        })
        .await;
    let ids: Vec<String> = list
        .iter()
        .map(|d| d["id"].as_str().unwrap().to_string())
        .collect();
    for id in &ids {
        assert!(id.len() >= 12 && id.len() < 64, "{id}");
        assert!(
            hashes.iter().all(|h| !h.starts_with(id.as_str())),
            "id leaks the token hash"
        );
    }
    let android_id = android["id"].as_str().unwrap().to_string();
    let r = other
        .delete(&format!("/api/v1/me/devices/{android_id}"), json!({}))
        .await;
    assert_eq!(r.status, 404);
    let r = web
        .delete("/api/v1/me/devices/doesnotexist", json!({}))
        .await;
    assert_eq!(r.status, 404);
    let r = web
        .delete(&format!("/api/v1/me/devices/{android_id}"), json!({}))
        .await;
    assert_eq!(r.status, 204);
    assert_eq!(a.get("/api/v1/me").await.status, 401);
    let r = web.get("/api/v1/me/devices").await;
    assert_eq!(r.body.as_array().unwrap().len(), 1);
    assert_eq!(r.body[0]["current"], true);
}

#[tokio::test]
async fn config_has_min_app_version() {
    let app = TestApp::new();
    let r = app.client().get("/api/v1/config").await;
    assert_eq!(r.body["min_app_version"], "1.0.0");
    let app = TestApp::with_env(&[("MIN_APP_VERSION", "2.3.4")]);
    let r = app.client().get("/api/v1/config").await;
    assert_eq!(r.body["min_app_version"], "2.3.4");
}

async fn sso_setup() -> (FakeOidc, TestApp) {
    let fake = FakeOidc::start().await;
    let env = fake.env();
    let pairs: Vec<(&str, &str)> = env.iter().map(|(k, v)| (*k, v.as_str())).collect();
    let app = TestApp::with_env(&pairs);
    fake.set_now(Some(app.clock.now()));
    (fake, app)
}

fn location(r: &Resp) -> String {
    assert_eq!(
        r.status, 302,
        "expected redirect, got {} {:?}",
        r.status, r.body
    );
    r.headers["location"].to_str().unwrap().to_string()
}

/// Runs the browser part of the app SSO flow and returns the final redirect target.
async fn app_sso(fake: &FakeOidc, c: &mut Client, query: &str) -> String {
    let auth = location(&c.get(&format!("/auth/oidc/start?{query}")).await);
    let cb = fake.authorize(&auth).await;
    let cb = cb.strip_prefix(ORIGIN).unwrap().to_string();
    let r = c.get(&cb).await;
    assert!(
        r.set_cookies().iter().all(|s| !s.starts_with(SESSION)),
        "app flow must not sign the browser in"
    );
    location(&r)
}

fn code_of(target: &str) -> String {
    let u = url::Url::parse(target).unwrap();
    assert_eq!(
        format!("{}://{}", u.scheme(), u.host_str().unwrap()),
        "wgchores://auth"
    );
    u.query_pairs()
        .find(|(k, _)| k == "code")
        .map(|(_, v)| v.into_owned())
        .expect("code")
}

async fn exchange(app: &TestApp, code: &str, verifier: &str) -> Resp {
    let mut c = app.client();
    c.post(
        "/api/v1/auth/app-token",
        json!({"code": code, "verifier": verifier, "device_name": "Pixel"}),
    )
    .await
}

#[tokio::test]
async fn app_sso_code_exchanged_once_for_token() {
    let (fake, app) = sso_setup().await;
    let mut c = app.client();
    let target = app_sso(
        &fake,
        &mut c,
        &format!("client=android&challenge={}", challenge(VERIFIER)),
    )
    .await;
    let code = code_of(&target);
    assert_eq!(URL_SAFE_NO_PAD.decode(&code).unwrap().len(), 32);
    let stored: Vec<String> = app
        .db(|c| {
            c.prepare("SELECT code_hash FROM app_codes")?
                .query_map([], |r| r.get(0))?
                .collect()
        })
        .await;
    assert_eq!(stored.len(), 1);
    assert_ne!(stored[0], code, "code stored hashed");
    let r = exchange(&app, &code, VERIFIER).await;
    assert_eq!(r.status, 200, "{:?}", r.body);
    assert!(r.set_cookies().is_empty());
    let bearer = r.body["token"].as_str().unwrap().to_string();
    let mut a = app_client(&app, &bearer);
    let me = a.get("/api/v1/me").await;
    assert_eq!((me.status, me.body["kind"].as_str()), (200, Some("sso")));
    assert_eq!(device_names(&app).await, vec![Some("Pixel".into())]);
    let r = exchange(&app, &code, VERIFIER).await;
    assert_eq!(
        (r.status, r.body["error"].as_str()),
        (400, Some("invalid_code"))
    );
}

#[tokio::test]
async fn app_sso_code_expires_after_60_seconds() {
    let (fake, app) = sso_setup().await;
    let mut c = app.client();
    let q = format!("client=android&challenge={}", challenge(VERIFIER));
    let code = code_of(&app_sso(&fake, &mut c, &q).await);
    app.clock.advance(Duration::seconds(61));
    let r = exchange(&app, &code, VERIFIER).await;
    assert_eq!(
        (r.status, r.body["error"].as_str()),
        (400, Some("invalid_code"))
    );
    app.clock.advance(Duration::seconds(-61));
    fake.set_now(Some(app.clock.now()));
    let code = code_of(&app_sso(&fake, &mut c, &q).await);
    app.clock.advance(Duration::seconds(59));
    assert_eq!(exchange(&app, &code, VERIFIER).await.status, 200);
}

#[tokio::test]
async fn wrong_verifier_burns_the_code() {
    let (fake, app) = sso_setup().await;
    let mut c = app.client();
    let q = format!("client=android&challenge={}", challenge(VERIFIER));
    let code = code_of(&app_sso(&fake, &mut c, &q).await);
    let other = "a".repeat(43);
    let r = exchange(&app, &code, &other).await;
    assert_eq!(
        (r.status, r.body["error"].as_str()),
        (400, Some("invalid_code"))
    );
    let r = exchange(&app, &code, VERIFIER).await;
    assert_eq!(
        (r.status, r.body["error"].as_str()),
        (400, Some("invalid_code"))
    );
    let code = code_of(&app_sso(&fake, &mut c, &q).await);
    for bad in ["short", &"a".repeat(129), &format!("{}!", "a".repeat(43))] {
        let r = exchange(&app, &code, bad).await;
        assert_eq!(r.status, 400, "{bad}");
    }
    assert_eq!(exchange(&app, &code, VERIFIER).await.status, 400);
}

#[tokio::test]
async fn app_sso_start_needs_valid_challenge() {
    let (_fake, app) = sso_setup().await;
    let mut c = app.client();
    for q in [
        "client=android".to_string(),
        "client=android&challenge=abc".to_string(),
        format!("client=android&challenge={}!", &challenge(VERIFIER)[..42]),
        format!("client=ios&challenge={}", challenge(VERIFIER)),
    ] {
        let r = c.get(&format!("/auth/oidc/start?{q}")).await;
        assert_eq!(r.status, 400, "{q}");
        assert!(r.set_cookies().is_empty());
    }
}

#[tokio::test]
async fn app_sso_keeps_invite_and_reports_errors_to_the_app() {
    let (fake, app) = sso_setup().await;
    let mut c = app.client();
    let q = format!(
        "client=android&challenge={}&invite=abc_DEF-123",
        challenge(VERIFIER)
    );
    let target = app_sso(&fake, &mut c, &q).await;
    let u = url::Url::parse(&target).unwrap();
    let pairs: std::collections::HashMap<String, String> = u.query_pairs().into_owned().collect();
    assert_eq!(pairs["invite"], "abc_DEF-123");
    assert!(pairs.contains_key("code"));

    let mut c = app.client();
    let auth = location(
        &c.get(&format!(
            "/auth/oidc/start?client=android&challenge={}",
            challenge(VERIFIER)
        ))
        .await,
    );
    let cb = fake.authorize(&auth).await;
    let cb = cb
        .strip_prefix(ORIGIN)
        .unwrap()
        .replace("state=", "state=x");
    assert_eq!(
        location(&c.get(&cb).await),
        "wgchores://auth?error=sso_failed"
    );

    fake.set_discovery_down(true);
    let app2 = TestApp::with_env(
        &fake
            .env()
            .iter()
            .map(|(k, v)| (*k, v.as_str()))
            .collect::<Vec<_>>(),
    );
    let mut c = app2.client();
    let r = c
        .get(&format!(
            "/auth/oidc/start?client=android&challenge={}",
            challenge(VERIFIER)
        ))
        .await;
    assert_eq!(location(&r), "wgchores://auth?error=sso_unavailable");
}

#[tokio::test]
async fn app_token_rate_limited_per_ip() {
    let app = TestApp::new();
    for _ in 0..20 {
        let r = exchange(&app, "nope", VERIFIER).await;
        assert_eq!(r.status, 400);
    }
    let r = exchange(&app, "nope", VERIFIER).await;
    assert_eq!(r.status, 429);
    assert!(r.headers.get("retry-after").is_some());
}

#[tokio::test]
async fn basic_auth_from_a_proxy_is_ignored() {
    let app = TestApp::new();
    let (web, _) = signup(&app, "anna").await;
    let mut req = axum::http::Request::builder().uri("/api/v1/me");
    let cookie: Vec<String> = web.jar.iter().map(|(k, v)| format!("{k}={v}")).collect();
    req = req
        .header("cookie", cookie.join("; "))
        .header("authorization", "Basic dXNlcjpwYXNz");
    let mut req = req.body(axum::body::Body::empty()).unwrap();
    req.extensions_mut()
        .insert(axum::extract::ConnectInfo(web.peer));
    let res = tower::ServiceExt::oneshot(app.router.clone(), req)
        .await
        .unwrap();
    assert_eq!(res.status(), 200);
}
