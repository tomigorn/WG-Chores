mod support;
use chrono::{DateTime, Duration, Utc};
use serde_json::json;
use support::*;

const PW: &str = "correct horse battery";
fn pw_body(cur: Option<&str>, new: Option<&str>) -> serde_json::Value {
    let mut m = serde_json::Map::new();
    if let Some(c) = cur {
        m.insert("current_password".into(), c.into());
    }
    if let Some(n) = new {
        m.insert("new_password".into(), n.into());
    }
    m.into()
}

const COOKIE: &str = "__Host-wgc_session";

fn reg(user: &str, pw: &str) -> serde_json::Value {
    json!({"username": user, "password": pw, "display_name": "Jane Doe"})
}

#[tokio::test]
async fn config_is_public() {
    let app = TestApp::with_env(&[("NOTIFY_TIMES", "08:30,20:15"), ("TZ", "Europe/Zurich")]);
    let r = app.client().get("/api/v1/config").await;
    assert_eq!(r.status, 200);
    assert_eq!(r.body["local_accounts"], true);
    assert_eq!(r.body["open_registration"], true);
    assert_eq!(r.body["oidc"], json!(null));
    assert_eq!(r.body["notify_times"], json!(["08:30", "20:15"]));
    assert_eq!(r.body["tz"], "Europe/Zurich");
    assert_eq!(r.body["version"], env!("CARGO_PKG_VERSION"));
    assert!(r.body["name"].is_string());
    let app = TestApp::with_env(&[
        ("OIDC_ISSUER", "https://sso.example.com"),
        ("OIDC_CLIENT_ID", "id"),
        ("OIDC_CLIENT_SECRET", "topsecret"),
        ("OIDC_NAME", "Holy Grail"),
    ]);
    let r = app.client().get("/api/v1/config").await;
    assert_eq!(
        r.body["oidc"],
        json!({"name": "Holy Grail", "account_url": null})
    );
    assert!(!r.body.to_string().contains("topsecret"));
}

#[tokio::test]
async fn register_logs_in() {
    let app = TestApp::new();
    let mut c = app.client();
    let r = c.post("/api/v1/auth/register", reg("anna", PW)).await;
    assert_eq!(r.status, 201);
    assert_eq!(r.body["username"], "anna");
    assert_eq!(r.body["display_name"], "Jane Doe");
    assert!(c.jar.contains_key(COOKIE));
    let me = c.get("/api/v1/me").await;
    assert_eq!(me.status, 200);
    assert_eq!(me.body["username"], "anna");
    assert_eq!(me.body["kind"], "local");
    assert!(!me.body.to_string().contains("hash"));
}

#[tokio::test]
async fn login_sets_host_cookie() {
    let app = TestApp::new();
    app.seed_local_user("anna", PW).await;
    let mut c = app.client();
    let r = c.login("Anna", PW).await;
    assert_eq!(r.status, 200);
    let cookies = r.set_cookies();
    assert_eq!(cookies.len(), 1);
    let sc = &cookies[0];
    assert!(sc.starts_with("__Host-wgc_session="), "{sc}");
    for a in ["HttpOnly", "Secure", "SameSite=Lax", "Path=/"] {
        assert!(sc.contains(a), "{a} missing in {sc}");
    }
    assert!(!sc.contains("Domain"));
    let raw = c.jar[COOKIE].clone();
    let stored: String = app
        .db(|c| c.query_row("SELECT token_hash FROM sessions", [], |r| r.get(0)))
        .await;
    assert_ne!(stored, raw);
    assert_eq!(stored.len(), 64);
    assert_eq!(c.get("/api/v1/me").await.status, 200);
}

#[tokio::test]
async fn wrong_password_generic_error() {
    let app = TestApp::new();
    app.seed_local_user("anna", PW).await;
    let mut c = app.client();
    let a = c.login("anna", "wrong password here").await;
    let b = c.login("nobody", "wrong password here").await;
    assert_eq!(a.status, 401);
    assert_eq!(a.status, b.status);
    assert_eq!(a.body, b.body);
    assert_eq!(a.body["error"], "invalid_credentials");
    assert!(c.jar.is_empty());
}

#[tokio::test]
async fn local_disabled_refuses_login_and_register() {
    let app = TestApp::with_env(&[("LOCAL_ACCOUNTS", "false")]);
    app.seed_local_user("anna", PW).await;
    let mut c = app.client();
    let r = c.login("anna", PW).await;
    assert_eq!(r.status, 403);
    assert_eq!(r.body["error"], "local_accounts_disabled");
    let r = c.post("/api/v1/auth/register", reg("bob", PW)).await;
    assert_eq!(r.status, 403);
    assert!(c.jar.is_empty());
}

#[tokio::test]
async fn registration_closed_without_invite() {
    let app = TestApp::with_env(&[("OPEN_REGISTRATION", "false")]);
    let mut c = app.client();
    let r = c.post("/api/v1/auth/register", reg("anna", PW)).await;
    assert_eq!(r.status, 403);
    assert_eq!(r.body["error"], "registration_closed");
}

#[tokio::test]
async fn password_min_12() {
    let app = TestApp::new();
    let mut c = app.client();
    let r = c
        .post("/api/v1/auth/register", reg("anna", "elevenchars"))
        .await;
    assert_eq!(r.status, 400);
    assert_eq!(r.body["error"], "password_too_short");
    let r = c
        .post("/api/v1/auth/register", reg("anna", "twelve chars"))
        .await;
    assert_eq!(r.status, 201);
}

#[tokio::test]
async fn username_rules() {
    let app = TestApp::new();
    let mut c = app.client();
    let bad = ["ab", &"a".repeat(33), "a b", "ännä", "anna@x", "", "a/b"];
    for (i, u) in bad.iter().enumerate() {
        let r = c.post("/api/v1/auth/register", reg(u, PW)).await;
        assert_eq!(r.status, 400, "{u} (#{i})");
        assert_eq!(r.body["error"], "invalid_username");
    }
    let r = c.post("/api/v1/auth/register", reg("a.b_c-1", PW)).await;
    assert_eq!(r.status, 201);
    let r = c
        .post("/api/v1/auth/register", reg(&"a".repeat(32), PW))
        .await;
    assert_eq!(r.status, 201);
    let r = c.post("/api/v1/auth/register", reg("A.B_C-1", PW)).await;
    assert_eq!(r.status, 409);
    assert_eq!(r.body["error"], "username_taken");
}

#[tokio::test]
async fn display_name_rules() {
    let app = TestApp::new();
    let mut c = app.client();
    for n in ["", "   ", &"x".repeat(61)] {
        let r = c
            .post(
                "/api/v1/auth/register",
                json!({"username": "anna", "password": PW, "display_name": n}),
            )
            .await;
        assert_eq!(r.status, 400, "{n:?}");
        assert_eq!(r.body["error"], "invalid_display_name");
    }
    let r = c
        .post(
            "/api/v1/auth/register",
            json!({"username": "anna", "password": PW, "display_name": "  Jane  "}),
        )
        .await;
    assert_eq!(r.body["display_name"], "Jane");
}

#[tokio::test]
async fn rate_limit_after_5_failures_per_account() {
    let app = TestApp::new();
    app.seed_local_user("anna", PW).await;
    let mut c = app.client();
    for _ in 0..5 {
        assert_eq!(c.login("anna", "wrong password here").await.status, 401);
    }
    let r = c.login("anna", PW).await;
    assert_eq!(r.status, 429);
    assert_eq!(r.body["error"], "too_many_attempts");
    let retry: i64 = r.headers["retry-after"].to_str().unwrap().parse().unwrap();
    assert!(retry > 0 && retry <= 900, "{retry}");
    let mut other = app.client();
    other.peer = "192.0.2.77:1".parse().unwrap();
    assert_eq!(other.login("anna", PW).await.status, 429);
    assert_eq!(other.login("ANNA", PW).await.status, 429);
    app.clock
        .advance(Duration::minutes(15) + Duration::seconds(1));
    assert_eq!(c.login("anna", PW).await.status, 200);
}

#[tokio::test]
async fn rate_limit_applies_to_unknown_usernames_too() {
    let app = TestApp::new();
    let mut c = app.client();
    for _ in 0..5 {
        assert_eq!(c.login("ghost", "wrong password here").await.status, 401);
    }
    assert_eq!(c.login("ghost", "wrong password here").await.status, 429);
}

#[tokio::test]
async fn success_clears_account_failures() {
    let app = TestApp::new();
    app.seed_local_user("anna", PW).await;
    let mut c = app.client();
    for _ in 0..4 {
        c.login("anna", "wrong password here").await;
    }
    assert_eq!(c.login("anna", PW).await.status, 200);
    for _ in 0..4 {
        assert_eq!(c.login("anna", "wrong password here").await.status, 401);
    }
    assert_eq!(c.login("anna", PW).await.status, 200);
}

#[tokio::test]
async fn rate_limit_20_per_ip() {
    let app = TestApp::new();
    app.seed_local_user("anna", PW).await;
    let mut c = app.client();
    for i in 0..20 {
        let r = c.login(&format!("ghost{i}"), "wrong password here").await;
        assert_eq!(r.status, 401, "attempt {i}");
    }
    assert_eq!(c.login("anna", PW).await.status, 429);
    let mut other = app.client();
    other.peer = "192.0.2.99:1".parse().unwrap();
    assert_eq!(other.login("anna", PW).await.status, 200);
    app.clock.advance(Duration::minutes(16));
    assert_eq!(c.login("anna", PW).await.status, 200);
}

#[tokio::test]
async fn forwarded_for_ignored_from_untrusted_peer() {
    let app = TestApp::new();
    app.seed_local_user("anna", PW).await;
    let mut c = app.client();
    for i in 0..20 {
        c.xff = Some(format!("203.0.113.{i}"));
        c.login(&format!("ghost{i}"), "wrong password here").await;
    }
    c.xff = Some("203.0.113.200".into());
    assert_eq!(c.login("anna", PW).await.status, 429);
}

#[tokio::test]
async fn forwarded_for_used_from_trusted_proxy() {
    let app = TestApp::with_env(&[("TRUSTED_PROXIES", "10.0.0.0/8")]);
    app.seed_local_user("anna", PW).await;
    let mut c = app.client();
    c.peer = "10.1.2.3:1".parse().unwrap();
    for i in 0..20 {
        c.xff = Some("198.51.100.1, 203.0.113.5, 10.9.9.9".to_string());
        c.login(&format!("ghost{i}"), "wrong password here").await;
    }
    c.xff = Some("198.51.100.1, 203.0.113.5".into());
    assert_eq!(c.login("anna", PW).await.status, 429);
    c.xff = Some("203.0.113.6".into());
    assert_eq!(c.login("anna", PW).await.status, 200);
}

#[tokio::test]
async fn csrf_header_required() {
    let app = TestApp::new();
    app.seed_local_user("anna", PW).await;
    let mut c = app.client();
    c.csrf = false;
    let r = c.login("anna", PW).await;
    assert_eq!(r.status, 403);
    assert_eq!(r.body["error"], "csrf");
    assert!(c.jar.is_empty());
    assert_eq!(c.get("/api/v1/config").await.status, 200);
    c.csrf = true;
    assert_eq!(c.login("anna", PW).await.status, 200);
}

#[tokio::test]
async fn foreign_origin_rejected() {
    let app = TestApp::new();
    app.seed_local_user("anna", PW).await;
    let mut c = app.client();
    for o in [
        "https://evil.example.org",
        "null",
        "http://chores.example.com",
        "https://chores.example.com.evil.org",
    ] {
        c.origin = Some(o.into());
        let r = c.login("anna", PW).await;
        assert_eq!(r.status, 403, "{o}");
        assert_eq!(r.body["error"], "csrf");
    }
    c.origin = None;
    assert_eq!(c.login("anna", PW).await.status, 200);
    c.origin = Some("https://chores.example.com".into());
    assert_eq!(c.login("anna", PW).await.status, 200);
}

#[tokio::test]
async fn logout_invalidates_session() {
    let app = TestApp::new();
    app.seed_local_user("anna", PW).await;
    let mut c = app.client();
    c.login("anna", PW).await;
    let old = c.jar[COOKIE].clone();
    let r = c.post("/api/v1/auth/logout", json!({})).await;
    assert_eq!(r.status, 204);
    assert!(c.jar.is_empty());
    c.jar.insert(COOKIE.into(), old);
    let me = c.get("/api/v1/me").await;
    assert_eq!(me.status, 401);
    assert_eq!(me.body["error"], "unauthenticated");
    let n: i64 = app
        .db(|c| c.query_row("SELECT count(*) FROM sessions", [], |r| r.get(0)))
        .await;
    assert_eq!(n, 0);
}

#[tokio::test]
async fn me_requires_session() {
    let app = TestApp::new();
    let mut c = app.client();
    assert_eq!(c.get("/api/v1/me").await.status, 401);
    c.jar.insert(COOKIE.into(), "garbage".into());
    assert_eq!(c.get("/api/v1/me").await.status, 401);
}

#[tokio::test]
async fn session_idle_expiry_and_sliding() {
    let app = TestApp::new();
    app.seed_local_user("anna", PW).await;
    let mut c = app.client();
    c.login("anna", PW).await;
    for _ in 0..3 {
        app.clock.advance(Duration::days(25));
        assert_eq!(c.get("/api/v1/me").await.status, 200);
    }
    app.clock.advance(Duration::days(31));
    assert_eq!(c.get("/api/v1/me").await.status, 401);
}

#[tokio::test]
async fn session_absolute_expiry_90_days() {
    let app = TestApp::new();
    app.seed_local_user("anna", PW).await;
    let mut c = app.client();
    c.login("anna", PW).await;
    for _ in 0..3 {
        app.clock.advance(Duration::days(29));
        assert_eq!(c.get("/api/v1/me").await.status, 200);
    }
    app.clock.advance(Duration::days(4));
    assert_eq!(c.get("/api/v1/me").await.status, 401);
}

#[tokio::test]
async fn last_seen_written_at_most_hourly() {
    let app = TestApp::new();
    app.seed_local_user("anna", PW).await;
    let mut c = app.client();
    c.login("anna", PW).await;
    let seen = |app: &TestApp| {
        let db = app.state.db.clone();
        async move {
            let s: String = db
                .run(|c| c.query_row("SELECT last_seen_at FROM sessions", [], |r| r.get(0)))
                .await
                .unwrap();
            DateTime::parse_from_rfc3339(&s)
                .unwrap()
                .with_timezone(&Utc)
        }
    };
    let t0 = seen(&app).await;
    app.clock.advance(Duration::minutes(59));
    c.get("/api/v1/me").await;
    assert_eq!(seen(&app).await, t0);
    app.clock.advance(Duration::minutes(2));
    c.get("/api/v1/me").await;
    assert_eq!(seen(&app).await, t0 + Duration::minutes(61));
}

#[tokio::test]
async fn patch_display_name() {
    let app = TestApp::new();
    app.seed_local_user("anna", PW).await;
    let mut c = app.client();
    c.login("anna", PW).await;
    let r = c
        .patch("/api/v1/me", json!({"display_name": "  Anna B  "}))
        .await;
    assert_eq!(r.status, 200);
    assert_eq!(r.body["display_name"], "Anna B");
    assert_eq!(
        c.patch("/api/v1/me", json!({"display_name": ""}))
            .await
            .status,
        400
    );
    assert_eq!(c.patch("/api/v1/me", json!({})).await.status, 400);
    assert_eq!(c.get("/api/v1/me").await.body["display_name"], "Anna B");
}

#[tokio::test]
async fn change_password_invalidates_other_sessions() {
    let app = TestApp::new();
    app.seed_local_user("anna", PW).await;
    let mut a = app.client();
    let mut b = app.client();
    a.login("anna", PW).await;
    b.login("anna", PW).await;
    let new = "another long passphrase";
    let r = a.patch("/api/v1/me", pw_body(None, Some(new))).await;
    assert_eq!(
        (r.status, r.body["error"].as_str()),
        (400, Some("current_password_required"))
    );
    let r = a
        .patch("/api/v1/me", pw_body(Some("wrong wrong wrong"), Some(new)))
        .await;
    assert_eq!(
        (r.status, r.body["error"].as_str()),
        (403, Some("wrong_password"))
    );
    let r = a
        .patch("/api/v1/me", pw_body(Some(PW), Some("short")))
        .await;
    assert_eq!(
        (r.status, r.body["error"].as_str()),
        (400, Some("password_too_short"))
    );
    let r = a.patch("/api/v1/me", pw_body(Some(PW), Some(new))).await;
    assert_eq!(r.status, 200);
    assert_eq!(a.get("/api/v1/me").await.status, 200);
    assert_eq!(b.get("/api/v1/me").await.status, 401);
    let mut c = app.client();
    assert_eq!(c.login("anna", PW).await.status, 401);
    assert_eq!(c.login("anna", new).await.status, 200);
}

#[tokio::test]
async fn wrong_current_password_counts_towards_rate_limit() {
    let app = TestApp::new();
    app.seed_local_user("anna", PW).await;
    let mut c = app.client();
    c.login("anna", PW).await;
    for _ in 0..5 {
        let r = c
            .patch(
                "/api/v1/me",
                pw_body(Some("wrong wrong wrong"), Some("another long passphrase")),
            )
            .await;
        assert_eq!(r.status, 403);
    }
    let r = c
        .patch(
            "/api/v1/me",
            pw_body(Some(PW), Some("another long passphrase")),
        )
        .await;
    assert_eq!(r.status, 429);
}

#[tokio::test]
async fn sso_user_cannot_change_password_or_name() {
    let app = TestApp::new();
    let id = app.seed_sso_user("Sue SSO").await;
    let sid = wg_chores::auth::session::create(
        &app.state.db,
        &*app.state.clock,
        id,
        wg_chores::auth::session::SessionKind::Web,
    )
    .await
    .unwrap();
    let mut c = app.client();
    c.jar.insert(COOKIE.into(), sid);
    let me = c.get("/api/v1/me").await;
    assert_eq!(me.body["kind"], "sso");
    let r = c
        .patch(
            "/api/v1/me",
            pw_body(Some("x"), Some("another long passphrase")),
        )
        .await;
    assert_eq!(
        (r.status, r.body["error"].as_str()),
        (403, Some("sso_managed"))
    );
    let r = c
        .patch("/api/v1/me", json!({"display_name": "Other"}))
        .await;
    assert_eq!(
        (r.status, r.body["error"].as_str()),
        (403, Some("sso_managed"))
    );
}

#[tokio::test]
async fn delete_account_keeps_history_as_former() {
    let app = TestApp::new();
    let id = app.seed_local_user("anna", PW).await;
    app.db(move |c| {
        c.execute_batch(
            "INSERT INTO households (id, name, created_at) VALUES (1, 'h', 'x');
             INSERT INTO memberships VALUES (1, 1, 'x');
             INSERT INTO chores (id, household_id, name, created_at) VALUES (1, 1, 'c', 'x');
             INSERT INTO completions (id, chore_id, user_id, done_on, created_at) VALUES ('u1', 1, 1, '2026-01-01', 'x');
             UPDATE users SET last_household_id = 1;",
        )
    })
    .await;
    let mut c = app.client();
    c.login("anna", PW).await;
    let r = c
        .delete("/api/v1/me", json!({"confirm_username": "anna"}))
        .await;
    assert_eq!(
        (r.status, r.body["error"].as_str()),
        (400, Some("current_password_required"))
    );
    let r = c
        .delete(
            "/api/v1/me",
            json!({"current_password": "wrong password here"}),
        )
        .await;
    assert_eq!(
        (r.status, r.body["error"].as_str()),
        (403, Some("wrong_password"))
    );
    let r = c
        .delete("/api/v1/me", json!({"current_password": PW}))
        .await; // leak-check: ok
    assert_eq!(r.status, 204);
    assert!(c.jar.is_empty());
    let (former, username, name, comps, idents, sessions, members): (
        i64,
        Option<String>,
        String,
        i64,
        i64,
        i64,
        i64,
    ) = app
        .db(move |c| {
            let (f, u, n) = c.query_row(
                "SELECT former, username, display_name FROM users WHERE id = ?1",
                [id],
                |r| Ok((r.get(0)?, r.get(1)?, r.get(2)?)),
            )?;
            let n1 = |sql: &str| c.query_row(sql, [], |r| r.get::<_, i64>(0));
            Ok((
                f,
                u,
                n,
                n1("SELECT count(*) FROM completions WHERE user_id = 1")?,
                n1("SELECT count(*) FROM identities")?,
                n1("SELECT count(*) FROM sessions")?,
                n1("SELECT count(*) FROM memberships")?,
            ))
        })
        .await;
    assert_eq!((former, username, name.as_str()), (1, None, "anna"));
    assert_eq!((comps, idents, sessions, members), (0, 0, 0, 0));
    assert_eq!(c.login("anna", PW).await.status, 401);
    let r = c.post("/api/v1/auth/register", reg("anna", PW)).await;
    assert_eq!(r.status, 201);
}

#[tokio::test]
async fn delete_sso_account_confirms_with_display_name() {
    let app = TestApp::new();
    let id = app.seed_sso_user("Sue SSO").await;
    let sid = wg_chores::auth::session::create(
        &app.state.db,
        &*app.state.clock,
        id,
        wg_chores::auth::session::SessionKind::Web,
    )
    .await
    .unwrap();
    let mut c = app.client();
    c.jar.insert(COOKIE.into(), sid);
    let r = c
        .delete("/api/v1/me", json!({"confirm_username": "sue sso"}))
        .await;
    assert_eq!(r.status, 400);
    let r = c
        .delete("/api/v1/me", json!({"confirm_username": "Sue SSO"}))
        .await;
    assert_eq!(r.status, 204);
}

#[tokio::test(flavor = "multi_thread", worker_threads = 4)]
async fn concurrent_wrong_logins_respect_account_limit() {
    let app = TestApp::new();
    app.seed_local_user("anna", PW).await;
    let mut set = tokio::task::JoinSet::new();
    for _ in 0..12 {
        let mut c = app.client();
        set.spawn(async move { c.login("anna", "wrong password here").await.status });
    }
    let mut codes = Vec::new();
    while let Some(r) = set.join_next().await {
        codes.push(r.unwrap());
    }
    assert_eq!(codes.iter().filter(|c| **c == 401).count(), 5, "{codes:?}");
    assert_eq!(codes.iter().filter(|c| **c == 429).count(), 7, "{codes:?}");
}

#[tokio::test(flavor = "multi_thread", worker_threads = 4)]
async fn concurrent_wrong_logins_respect_ip_limit() {
    let app = TestApp::new();
    let mut set = tokio::task::JoinSet::new();
    for i in 0..30 {
        let mut c = app.client();
        set.spawn(async move {
            c.login(&format!("ghost{i}"), "wrong password here")
                .await
                .status
        });
    }
    let mut codes = Vec::new();
    while let Some(r) = set.join_next().await {
        codes.push(r.unwrap());
    }
    assert_eq!(codes.iter().filter(|c| **c == 401).count(), 20, "{codes:?}");
    assert_eq!(codes.iter().filter(|c| **c == 429).count(), 10, "{codes:?}");
}

#[tokio::test]
async fn register_rate_limited_per_ip() {
    let app = TestApp::new();
    let mut c = app.client();
    for i in 0..20 {
        let r = c
            .post("/api/v1/auth/register", reg(&format!("user{i:02}"), PW))
            .await;
        assert_eq!(r.status, 201, "attempt {i}");
    }
    let r = c.post("/api/v1/auth/register", reg("user99", PW)).await;
    assert_eq!(r.status, 429);
    let mut other = app.client();
    other.peer = "192.0.2.55:1".parse().unwrap();
    assert_eq!(
        other
            .post("/api/v1/auth/register", reg("user99", PW))
            .await
            .status,
        201
    );
    app.clock.advance(Duration::minutes(16));
    assert_eq!(
        c.post("/api/v1/auth/register", reg("user98", PW))
            .await
            .status,
        201
    );
}

#[tokio::test]
async fn local_disabled_stops_existing_local_sessions() {
    let app = TestApp::with_env(&[("LOCAL_ACCOUNTS", "false")]);
    let local = app.seed_local_user("anna", PW).await;
    let both = app.seed_local_user("bob", PW).await;
    app.db(move |c| {
        c.execute(
            "INSERT INTO identities (user_id, kind, issuer, subject) VALUES (?1, 'oidc', 'https://sso.example.com', 'sub-bob')",
            [both],
        )
    })
    .await;
    let mk = |id| {
        let st = app.state.clone();
        async move {
            wg_chores::auth::session::create(
                &st.db,
                &*st.clock,
                id,
                wg_chores::auth::session::SessionKind::Web,
            )
            .await
            .unwrap()
        }
    };
    let mut a = app.client();
    a.jar.insert(COOKIE.into(), mk(local).await);
    assert_eq!(a.get("/api/v1/me").await.status, 401);
    let mut b = app.client();
    b.jar.insert(COOKIE.into(), mk(both).await);
    assert_eq!(b.get("/api/v1/me").await.status, 200);
    let r = b
        .patch(
            "/api/v1/me",
            pw_body(Some(PW), Some("another long passphrase")),
        )
        .await;
    assert_eq!(
        (r.status, r.body["error"].as_str()),
        (403, Some("local_accounts_disabled"))
    );
}

#[tokio::test]
async fn login_and_register_replace_incoming_session() {
    let app = TestApp::new();
    app.seed_local_user("anna", PW).await;
    let mut c = app.client();
    c.login("anna", PW).await;
    let old = c.jar[COOKIE].clone();
    c.login("anna", PW).await;
    assert_ne!(c.jar[COOKIE], old);
    let n: i64 = app
        .db(|c| c.query_row("SELECT count(*) FROM sessions", [], |r| r.get(0)))
        .await;
    assert_eq!(n, 1);
    let old = c.jar[COOKIE].clone();
    let r = c.post("/api/v1/auth/register", reg("bob", PW)).await;
    assert_eq!(r.status, 201);
    let n: i64 = app
        .db(|c| c.query_row("SELECT count(*) FROM sessions", [], |r| r.get(0)))
        .await;
    assert_eq!(n, 1);
    let mut stale = app.client();
    stale.jar.insert(COOKIE.into(), old);
    assert_eq!(stale.get("/api/v1/me").await.status, 401);
}

#[tokio::test]
async fn csrf_header_required_on_patch_and_delete() {
    let app = TestApp::new();
    app.seed_local_user("anna", PW).await;
    let mut c = app.client();
    c.login("anna", PW).await;
    c.csrf = false;
    let r = c.patch("/api/v1/me", json!({"display_name": "X"})).await;
    assert_eq!((r.status, r.body["error"].as_str()), (403, Some("csrf")));
    let r = c
        .delete("/api/v1/me", json!({"current_password": PW}))
        .await;
    assert_eq!((r.status, r.body["error"].as_str()), (403, Some("csrf")));
    c.csrf = true;
    assert_eq!(c.get("/api/v1/me").await.body["display_name"], "anna");
}

#[tokio::test]
async fn ipv6_clients_share_one_bucket_per_64() {
    let app = TestApp::new();
    app.seed_local_user("anna", PW).await;
    let mut c = app.client();
    for i in 0..20 {
        c.peer = format!("[2001:db8:1:2::{:x}]:5000", i + 1).parse().unwrap();
        let r = c.login(&format!("ghost{i}"), "wrong password here").await;
        assert_eq!(r.status, 401, "attempt {i}");
    }
    c.peer = "[2001:db8:1:2:ffff:ffff:ffff:ffff]:5000".parse().unwrap();
    assert_eq!(c.login("anna", PW).await.status, 429);
    c.peer = "[2001:db8:1:3::1]:5000".parse().unwrap();
    assert_eq!(c.login("anna", PW).await.status, 200);
}

#[tokio::test]
async fn ipv6_registrations_share_one_bucket_per_64() {
    let app = TestApp::new();
    let mut c = app.client();
    for i in 0..20 {
        c.peer = format!("[2001:db8::{:x}]:5000", i + 1).parse().unwrap();
        let r = c
            .post("/api/v1/auth/register", reg(&format!("user{i:02}"), PW))
            .await;
        assert_eq!(r.status, 201, "attempt {i}");
    }
    c.peer = "[2001:db8::abcd:1]:5000".parse().unwrap();
    let r = c.post("/api/v1/auth/register", reg("user99", PW)).await;
    assert_eq!(r.status, 429);
}

#[tokio::test]
async fn registrations_do_not_fill_the_login_bucket() {
    let app = TestApp::new();
    app.seed_local_user("anna", PW).await;
    let mut c = app.client();
    for i in 0..20 {
        let r = c
            .post("/api/v1/auth/register", reg(&format!("user{i:02}"), PW))
            .await;
        assert_eq!(r.status, 201, "attempt {i}");
    }
    assert_eq!(c.login("anna", PW).await.status, 200);
}

#[tokio::test]
async fn forwarded_for_walk_stops_at_unparseable_entry() {
    let app = TestApp::with_env(&[("TRUSTED_PROXIES", "10.0.0.0/8")]);
    app.seed_local_user("anna", PW).await;
    let mut c = app.client();
    c.peer = "10.1.2.3:1".parse().unwrap();
    for i in 0..20 {
        c.xff = Some(format!("203.0.113.{i}, 198.51.100.1:443"));
        c.login(&format!("ghost{i}"), "wrong password here").await;
    }
    c.xff = Some("198.51.100.77, unknown".into());
    assert_eq!(c.login("anna", PW).await.status, 429);
    c.xff = Some("203.0.113.6".into());
    assert_eq!(c.login("anna", PW).await.status, 200);
}

#[tokio::test]
async fn sso_session_absolute_expiry_30_days() {
    let app = TestApp::new();
    let id = app.seed_sso_user("Sue SSO").await;
    let mut c = app.client();
    login_as(&app, &mut c, id).await;
    app.clock.advance(Duration::days(20));
    assert_eq!(c.get("/api/v1/me").await.status, 200);
    app.clock.advance(Duration::days(9));
    assert_eq!(c.get("/api/v1/me").await.status, 200);
    app.clock.advance(Duration::days(2));
    assert_eq!(c.get("/api/v1/me").await.status, 401);
}

#[tokio::test]
async fn delete_account_keeps_shared_history_and_deletes_households_left_empty() {
    let app = TestApp::new();
    let id = app.seed_local_user("anna", PW).await;
    let ben = app.seed_local_user("ben", PW).await;
    app.db(move |c| {
        c.execute_batch(&format!(
            "INSERT INTO households (id, name, created_at) VALUES (1, 'alone', 'x'), (2, 'shared', 'x');
             INSERT INTO memberships VALUES (1, {id}, 'x'), (2, {id}, 'x'), (2, {ben}, 'x');
             INSERT INTO chores (id, household_id, name, created_at) VALUES (1, 1, 'c1', 'x'), (2, 2, 'c2', 'x');
             INSERT INTO categories (household_id, name) VALUES (1, 'Kitchen');
             INSERT INTO completions (id, chore_id, user_id, done_on, created_at) VALUES
               ('u1', 1, {id}, '2026-01-01', 'x'), ('u2', 2, {id}, '2026-01-01', 'x');
             INSERT INTO invites (household_id, token_hash, created_by, created_at, expires_at) VALUES
               (1, 'h1', {id}, 'x', '2099-01-01T00:00:00.000Z');"
        ))
    })
    .await;
    let mut c = app.client();
    c.login("anna", PW).await;
    let r = c
        .delete("/api/v1/me", json!({"current_password": PW}))
        .await; // leak-check: ok
    assert_eq!(r.status, 204);
    let counts: Vec<i64> = app
        .db(|c| {
            [
                "SELECT count(*) FROM households WHERE id = 1",
                "SELECT count(*) FROM households WHERE id = 2",
                "SELECT count(*) FROM chores",
                "SELECT count(*) FROM categories",
                "SELECT count(*) FROM completions",
                "SELECT count(*) FROM invites",
            ]
            .iter()
            .map(|q| c.query_row(q, [], |r| r.get(0)))
            .collect()
        })
        .await;
    assert_eq!(counts, [0, 1, 1, 0, 1, 0]);
}

#[tokio::test]
async fn logout_drops_own_push_subscription_only() {
    let app = TestApp::new();
    let anna = app.seed_local_user("anna", PW).await;
    let ben = app.seed_local_user("ben", PW).await;
    app.db(move |c| {
        c.execute_batch(&format!(
            "INSERT INTO push_subscriptions (user_id, endpoint, p256dh, auth, created_at) VALUES
               ({anna}, 'https://push.example.com/a1', 'k', 'a', 'x'),
               ({anna}, 'https://push.example.com/a2', 'k', 'a', 'x'),
               ({ben}, 'https://push.example.com/b1', 'k', 'a', 'x');"
        ))
    })
    .await;
    let mut c = app.client();
    c.login("anna", PW).await;
    let r = c
        .post(
            "/api/v1/auth/logout",
            json!({"push_endpoint": "https://push.example.com/b1"}),
        )
        .await;
    assert_eq!(r.status, 204);
    c.login("anna", PW).await;
    let r = c
        .post(
            "/api/v1/auth/logout",
            json!({"push_endpoint": "https://push.example.com/a1"}),
        )
        .await;
    assert_eq!(r.status, 204);
    c.login("anna", PW).await;
    let r = c
        .send(axum::http::Method::POST, "/api/v1/auth/logout", None)
        .await;
    assert_eq!(r.status, 204);
    let left: Vec<String> = app
        .db(|c| {
            let mut s = c.prepare("SELECT endpoint FROM push_subscriptions ORDER BY 1")?;
            s.query_map([], |r| r.get(0))?.collect()
        })
        .await;
    assert_eq!(
        left,
        ["https://push.example.com/a2", "https://push.example.com/b1"]
    );
}
