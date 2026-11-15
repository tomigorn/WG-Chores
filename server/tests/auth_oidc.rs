mod support;
use chrono::Duration;
use serde_json::json;
use support::fake_oidc::{FakeKey, FakeOidc, FakeUser};
use support::*;
use wg_chores::clock::Clock;

const SESSION: &str = "__Host-wgc_session";
const STATE: &str = "__Host-wgc_oidc";
const PW: &str = "correct horse battery";

async fn setup(extra: &[(&str, &str)]) -> (FakeOidc, TestApp) {
    let fake = FakeOidc::start().await;
    let env = fake.env();
    let mut pairs: Vec<(&str, &str)> = env.iter().map(|(k, v)| (*k, v.as_str())).collect();
    pairs.extend_from_slice(extra);
    let app = TestApp::with_env(&pairs);
    fake.set_now(Some(app.clock.now()));
    (fake, app)
}

fn location(r: &Resp) -> String {
    assert_eq!(r.status, 302, "expected redirect, got {}", r.status);
    r.headers["location"].to_str().unwrap().to_string()
}

async fn callback_url(fake: &FakeOidc, c: &mut Client, start: &str) -> String {
    let auth = location(&c.get(start).await);
    let cb = fake.authorize(&auth).await;
    cb.strip_prefix(ORIGIN)
        .expect("callback on the public url")
        .to_string()
}

async fn sso_login(fake: &FakeOidc, c: &mut Client) -> String {
    let cb = callback_url(fake, c, "/auth/oidc/start").await;
    location(&c.get(&cb).await)
}

async fn count(app: &TestApp, sql: &'static str) -> i64 {
    app.db(move |c| c.query_row(sql, [], |r| r.get(0))).await
}

#[tokio::test]
async fn start_redirects_with_pkce_and_sets_state_cookie() {
    let (fake, app) = setup(&[]).await;
    let mut c = app.client();
    let r = c.get("/auth/oidc/start").await;
    let loc = url::Url::parse(&location(&r)).unwrap();
    assert_eq!(
        format!("{}{}", loc.origin().ascii_serialization(), loc.path()),
        format!("{}/authorize", fake.issuer())
    );
    let q: std::collections::HashMap<String, String> = loc.query_pairs().into_owned().collect();
    assert_eq!(q["response_type"], "code");
    assert_eq!(q["client_id"], support::fake_oidc::CLIENT_ID);
    assert_eq!(q["redirect_uri"], format!("{ORIGIN}/auth/oidc/callback"));
    let scopes: Vec<&str> = q["scope"].split(' ').collect();
    for s in ["openid", "profile", "email", "groups"] {
        assert!(scopes.contains(&s), "{s} missing in {scopes:?}");
    }
    assert_eq!(q["code_challenge_method"], "S256");
    assert!(q["code_challenge"].len() >= 43);
    assert!(q["state"].len() >= 16);
    assert!(q["nonce"].len() >= 16);
    let cookies = r.set_cookies();
    assert_eq!(cookies.len(), 1);
    let sc = &cookies[0];
    assert!(sc.starts_with(&format!("{STATE}=")), "{sc}");
    for a in [
        "HttpOnly",
        "Secure",
        "SameSite=Lax",
        "Path=/",
        "Max-Age=600",
    ] {
        assert!(sc.contains(a), "{a} missing in {sc}");
    }
    assert!(!sc.contains("Domain"));
    assert!(!sc.contains(&q["code_challenge"]));
}

#[tokio::test]
async fn sso_login_creates_user_once() {
    let (fake, app) = setup(&[("OIDC_ACCOUNT_URL", "https://sso.example.com/if/user/")]).await;
    let mut c = app.client();
    assert_eq!(sso_login(&fake, &mut c).await, "/");
    assert!(c.jar.contains_key(SESSION));
    assert!(!c.jar.contains_key(STATE), "state cookie must be deleted");
    let me = c.get("/api/v1/me").await;
    assert_eq!(me.status, 200);
    assert_eq!(me.body["kind"], "sso");
    assert_eq!(me.body["display_name"], "Jane Doe");
    assert_eq!(me.body["username"], "jane");
    assert_eq!(me.body["picture_url"], "https://img.example.com/jane.png");
    assert_eq!(me.body["account_url"], "https://sso.example.com/if/user/");
    let id = me.body["id"].as_i64().unwrap();

    let mut u = FakeUser::new("fake-user-1");
    u.name = Some("Jane D.".into());
    u.picture = Some("https://img.example.com/new.png".into());
    fake.set_user(u);
    let mut c2 = app.client();
    assert_eq!(sso_login(&fake, &mut c2).await, "/");
    let me = c2.get("/api/v1/me").await;
    assert_eq!(me.body["id"].as_i64().unwrap(), id);
    assert_eq!(me.body["display_name"], "Jane D.");
    assert_eq!(me.body["picture_url"], "https://img.example.com/new.png");
    assert_eq!(count(&app, "SELECT count(*) FROM users").await, 1);
    let issuer = fake.issuer().to_string();
    let (kind, iss, sub): (String, String, String) = app
        .db(|c| {
            c.query_row("SELECT kind, issuer, subject FROM identities", [], |r| {
                Ok((r.get(0)?, r.get(1)?, r.get(2)?))
            })
        })
        .await;
    assert_eq!((kind.as_str(), sub.as_str()), ("oidc", "fake-user-1"));
    assert_eq!(iss, issuer);
    let kinds: String = app
        .db(|c| {
            c.query_row(
                "SELECT group_concat(DISTINCT kind) FROM sessions",
                [],
                |r| r.get(0),
            )
        })
        .await;
    assert_eq!(kinds, "web");
}

#[tokio::test]
async fn wrong_group_refused() {
    let (fake, app) = setup(&[("OIDC_REQUIRED_GROUP", "WG")]).await;
    let mut u = FakeUser::new("outsider");
    u.groups = Some(vec!["Family".into(), "wg".into()]);
    fake.set_user(u);
    let mut c = app.client();
    assert_eq!(sso_login(&fake, &mut c).await, "/login?error=not_allowed");
    assert!(!c.jar.contains_key(SESSION));
    assert!(!c.jar.contains_key(STATE));
    assert_eq!(count(&app, "SELECT count(*) FROM users").await, 0);
}

#[tokio::test]
async fn missing_groups_claim_refused_when_group_required() {
    let (fake, app) = setup(&[("OIDC_REQUIRED_GROUP", "WG")]).await;
    let mut u = FakeUser::new("nogroups");
    u.groups = None;
    fake.set_user(u);
    let mut c = app.client();
    assert_eq!(sso_login(&fake, &mut c).await, "/login?error=not_allowed");
    assert_eq!(count(&app, "SELECT count(*) FROM users").await, 0);
}

#[tokio::test]
async fn required_group_member_allowed() {
    let (fake, app) = setup(&[("OIDC_REQUIRED_GROUP", "WG")]).await;
    let mut u = FakeUser::new("member");
    u.groups = Some(vec!["Family".into(), "WG".into()]);
    fake.set_user(u);
    let mut c = app.client();
    assert_eq!(sso_login(&fake, &mut c).await, "/");
    assert_eq!(c.get("/api/v1/me").await.status, 200);
}

#[tokio::test]
async fn no_group_config_allows_any() {
    let (fake, app) = setup(&[]).await;
    let mut u = FakeUser::new("anyone");
    u.groups = None;
    fake.set_user(u);
    let mut c = app.client();
    assert_eq!(sso_login(&fake, &mut c).await, "/");
    assert_eq!(c.get("/api/v1/me").await.status, 200);
}

#[tokio::test]
async fn state_mismatch_rejected() {
    let (fake, app) = setup(&[]).await;
    let mut c = app.client();
    let cb = callback_url(&fake, &mut c, "/auth/oidc/start").await;
    let mut u = url::Url::parse(&format!("{ORIGIN}{cb}")).unwrap();
    let pairs: Vec<(String, String)> = u
        .query_pairs()
        .map(|(k, v)| {
            let v = if k == "state" {
                format!("{v}x")
            } else {
                v.into_owned()
            };
            (k.into_owned(), v)
        })
        .collect();
    u.query_pairs_mut().clear().extend_pairs(pairs);
    let r = c.get(&u.as_str()[ORIGIN.len()..]).await;
    assert_eq!(location(&r), "/login?error=sso_failed");
    assert!(!c.jar.contains_key(SESSION));
    assert!(!c.jar.contains_key(STATE));
    assert_eq!(count(&app, "SELECT count(*) FROM users").await, 0);
}

#[tokio::test]
async fn callback_without_state_cookie_rejected() {
    let (fake, app) = setup(&[]).await;
    let mut c = app.client();
    let cb = callback_url(&fake, &mut c, "/auth/oidc/start").await;
    let mut other = app.client();
    assert_eq!(location(&other.get(&cb).await), "/login?error=sso_failed");
    assert!(!other.jar.contains_key(SESSION));
    assert_eq!(location(&c.get(&cb).await), "/");
    assert_eq!(location(&c.get(&cb).await), "/login?error=sso_failed");
}

#[tokio::test]
async fn tampered_state_cookie_rejected() {
    let (fake, app) = setup(&[]).await;
    let mut c = app.client();
    let cb = callback_url(&fake, &mut c, "/auth/oidc/start").await;
    let raw = c.jar[STATE].clone();
    let (payload, mac) = raw.split_once('.').expect("payload.mac");
    let flipped: String = mac
        .chars()
        .enumerate()
        .map(|(i, ch)| {
            if i == 0 {
                if ch == 'A' { 'B' } else { 'A' }
            } else {
                ch
            }
        })
        .collect();
    c.jar.insert(STATE.into(), format!("{payload}.{flipped}"));
    assert_eq!(location(&c.get(&cb).await), "/login?error=sso_failed");
    assert!(!c.jar.contains_key(SESSION));
}

#[tokio::test]
async fn expired_state_cookie_rejected() {
    let (fake, app) = setup(&[]).await;
    let mut c = app.client();
    let cb_start = location(&c.get("/auth/oidc/start").await);
    app.clock.advance(Duration::minutes(11));
    fake.set_now(Some(app.clock.now()));
    let cb = fake.authorize(&cb_start).await;
    let r = c.get(cb.strip_prefix(ORIGIN).unwrap()).await;
    assert_eq!(location(&r), "/login?error=sso_failed");
    assert!(!c.jar.contains_key(SESSION));
}

#[tokio::test]
async fn nonce_mismatch_rejected() {
    let (fake, app) = setup(&[]).await;
    fake.set_nonce(Some("not-the-requested-nonce".into()));
    let mut c = app.client();
    assert_eq!(sso_login(&fake, &mut c).await, "/login?error=sso_failed");
    assert_eq!(count(&app, "SELECT count(*) FROM users").await, 0);
}

#[tokio::test]
async fn provider_error_on_callback_rejected() {
    let (_fake, app) = setup(&[]).await;
    let mut c = app.client();
    c.get("/auth/oidc/start").await;
    let r = c
        .get("/auth/oidc/callback?error=access_denied&state=x")
        .await;
    assert_eq!(location(&r), "/login?error=sso_failed");
    assert!(!c.jar.contains_key(STATE));
}

#[tokio::test]
async fn username_change_does_not_split_user() {
    let (fake, app) = setup(&[]).await;
    let mut c = app.client();
    sso_login(&fake, &mut c).await;
    let id = c.get("/api/v1/me").await.body["id"].as_i64().unwrap();
    let mut u = FakeUser::new("fake-user-1");
    u.preferred_username = Some("Jane.D".into());
    fake.set_user(u);
    let mut c2 = app.client();
    sso_login(&fake, &mut c2).await;
    let me = c2.get("/api/v1/me").await;
    assert_eq!(me.body["id"].as_i64().unwrap(), id);
    assert_eq!(me.body["username"], "jane.d");
    fake.set_user(FakeUser::new("someone-else"));
    let mut c3 = app.client();
    sso_login(&fake, &mut c3).await;
    let me = c3.get("/api/v1/me").await;
    assert_ne!(me.body["id"].as_i64().unwrap(), id);
    assert_eq!(me.body["username"], "jane");
    assert_eq!(count(&app, "SELECT count(*) FROM users").await, 2);
}

#[tokio::test]
async fn username_taken_or_invalid_left_empty() {
    let (fake, app) = setup(&[]).await;
    app.seed_local_user("jane", PW).await;
    let mut u = FakeUser::new("sso-jane");
    u.preferred_username = Some("Jane".into());
    fake.set_user(u);
    let mut c = app.client();
    assert_eq!(sso_login(&fake, &mut c).await, "/");
    let me = c.get("/api/v1/me").await;
    assert_eq!(me.body["username"], json!(null));
    assert_eq!(me.body["display_name"], "Jane Doe");
    let mut u = FakeUser::new("sso-other");
    u.preferred_username = Some("j d".into());
    fake.set_user(u);
    let mut c = app.client();
    sso_login(&fake, &mut c).await;
    assert_eq!(c.get("/api/v1/me").await.body["username"], json!(null));
}

#[tokio::test]
async fn display_name_falls_back_to_preferred_username() {
    let (fake, app) = setup(&[]).await;
    let mut u = FakeUser::new("noname");
    u.name = None;
    u.preferred_username = Some("Anna".into());
    fake.set_user(u);
    let mut c = app.client();
    sso_login(&fake, &mut c).await;
    assert_eq!(c.get("/api/v1/me").await.body["display_name"], "Anna");
}

#[tokio::test]
async fn picture_must_be_https() {
    let (fake, app) = setup(&[]).await;
    let mut u = FakeUser::new("pic");
    u.picture = Some("http://img.example.com/jane.png".into());
    fake.set_user(u);
    let mut c = app.client();
    sso_login(&fake, &mut c).await;
    assert_eq!(c.get("/api/v1/me").await.body["picture_url"], json!(null));
    let mut u = FakeUser::new("pic");
    u.picture = Some("javascript:alert(1)".into());
    fake.set_user(u);
    let mut c = app.client();
    sso_login(&fake, &mut c).await;
    assert_eq!(c.get("/api/v1/me").await.body["picture_url"], json!(null));
}

#[tokio::test]
async fn config_shows_oidc_name() {
    let app = TestApp::with_env(&[
        ("OIDC_ISSUER", "https://sso.example.com"),
        ("OIDC_CLIENT_ID", "id"),
        ("OIDC_CLIENT_SECRET", "topsecret"),
        ("OIDC_NAME", "Holy Grail"),
        ("OIDC_ACCOUNT_URL", "https://sso.example.com/if/user/"),
    ]);
    let r = app.client().get("/api/v1/config").await;
    assert_eq!(
        r.body["oidc"],
        json!({"name": "Holy Grail", "account_url": "https://sso.example.com/if/user/"})
    );
    assert!(!r.body.to_string().contains("topsecret"));
}

#[tokio::test]
async fn sso_works_with_local_disabled() {
    let (fake, app) = setup(&[("LOCAL_ACCOUNTS", "false")]).await;
    let mut c = app.client();
    assert_eq!(sso_login(&fake, &mut c).await, "/");
    let me = c.get("/api/v1/me").await;
    assert_eq!((me.status, me.body["kind"].as_str()), (200, Some("sso")));
}

#[tokio::test]
async fn deleted_user_not_revived() {
    let (fake, app) = setup(&[]).await;
    let mut c = app.client();
    sso_login(&fake, &mut c).await;
    let old = c.get("/api/v1/me").await.body["id"].as_i64().unwrap();
    let r = c
        .delete("/api/v1/me", json!({"confirm_username": "Jane Doe"}))
        .await;
    assert_eq!(r.status, 204);
    let mut c = app.client();
    assert_eq!(sso_login(&fake, &mut c).await, "/");
    let me = c.get("/api/v1/me").await;
    let new = me.body["id"].as_i64().unwrap();
    assert_ne!(new, old);
    assert_eq!(me.body["username"], "jane");
    let former: i64 = app
        .db(move |c| {
            c.query_row("SELECT former FROM users WHERE id = ?1", [old], |r| {
                r.get(0)
            })
        })
        .await;
    assert_eq!(former, 1);
}

#[tokio::test]
async fn idp_unreachable_redirects_to_login() {
    let app = TestApp::with_env(&[
        ("OIDC_ISSUER", "http://127.0.0.1:9"),
        ("OIDC_CLIENT_ID", "id"),
        ("OIDC_CLIENT_SECRET", "s"),
    ]);
    let mut c = app.client();
    let r = c.get("/auth/oidc/start").await;
    assert_eq!(location(&r), "/login?error=sso_unavailable");
    assert!(!c.jar.contains_key(STATE));
}

#[tokio::test]
async fn invite_token_kept_through_login() {
    let (fake, app) = setup(&[]).await;
    let mut c = app.client();
    let cb = callback_url(&fake, &mut c, "/auth/oidc/start?invite=abc_DEF-123").await;
    assert_eq!(location(&c.get(&cb).await), "/invite/abc_DEF-123");
    let mut c = app.client();
    let cb = callback_url(&fake, &mut c, "/auth/oidc/start?invite=..%2Fevil").await;
    assert_eq!(location(&c.get(&cb).await), "/");
}

#[tokio::test]
async fn sso_login_replaces_incoming_session() {
    let (fake, app) = setup(&[]).await;
    app.seed_local_user("anna", PW).await;
    let mut c = app.client();
    assert_eq!(c.login("anna", PW).await.status, 200);
    let old = c.jar[SESSION].clone();
    assert_eq!(sso_login(&fake, &mut c).await, "/");
    assert_ne!(c.jar[SESSION], old);
    assert_eq!(count(&app, "SELECT count(*) FROM sessions").await, 1);
}

#[tokio::test]
async fn oidc_off_means_not_found() {
    let app = TestApp::new();
    let mut c = app.client();
    assert_eq!(c.get("/auth/oidc/start").await.status, 404);
    assert_eq!(
        c.get("/auth/oidc/callback?code=x&state=y").await.status,
        404
    );
}

#[tokio::test]
async fn wrong_audience_rejected() {
    let (fake, app) = setup(&[]).await;
    fake.set_audience(Some("someone-else".into()));
    let mut c = app.client();
    assert_eq!(sso_login(&fake, &mut c).await, "/login?error=sso_failed");
    assert_eq!(count(&app, "SELECT count(*) FROM users").await, 0);
}

#[tokio::test]
async fn wrong_issuer_claim_rejected() {
    let (fake, app) = setup(&[]).await;
    fake.set_issuer_claim(Some("https://evil.example.com".into()));
    let mut c = app.client();
    assert_eq!(sso_login(&fake, &mut c).await, "/login?error=sso_failed");
    assert_eq!(count(&app, "SELECT count(*) FROM users").await, 0);
}

#[tokio::test]
async fn foreign_signing_key_rejected() {
    let (fake, app) = setup(&[]).await;
    fake.set_keys(FakeKey::A, FakeKey::B);
    let mut c = app.client();
    assert_eq!(sso_login(&fake, &mut c).await, "/login?error=sso_failed");
    assert_eq!(count(&app, "SELECT count(*) FROM users").await, 0);
}

#[tokio::test]
async fn expired_id_token_rejected() {
    let (fake, app) = setup(&[]).await;
    fake.set_now(Some(app.clock.now() - Duration::minutes(10)));
    let mut c = app.client();
    assert_eq!(sso_login(&fake, &mut c).await, "/login?error=sso_failed");
    assert_eq!(count(&app, "SELECT count(*) FROM users").await, 0);
}

#[tokio::test]
async fn rotated_provider_key_is_fetched_again() {
    let (fake, app) = setup(&[]).await;
    let mut c = app.client();
    assert_eq!(sso_login(&fake, &mut c).await, "/");
    fake.set_keys(FakeKey::B, FakeKey::B);
    let mut c = app.client();
    assert_eq!(sso_login(&fake, &mut c).await, "/");
}

#[tokio::test]
async fn discovery_failure_cached_for_30_seconds() {
    let (fake, app) = setup(&[]).await;
    fake.set_discovery_down(true);
    let mut c = app.client();
    assert_eq!(
        location(&c.get("/auth/oidc/start").await),
        "/login?error=sso_unavailable"
    );
    fake.set_discovery_down(false);
    assert_eq!(
        location(&c.get("/auth/oidc/start").await),
        "/login?error=sso_unavailable"
    );
    app.clock.advance(Duration::seconds(29));
    assert_eq!(
        location(&c.get("/auth/oidc/start").await),
        "/login?error=sso_unavailable"
    );
    app.clock.advance(Duration::seconds(2));
    let loc = location(&c.get("/auth/oidc/start").await);
    assert!(loc.starts_with(fake.issuer()), "{loc}");
}

#[tokio::test]
async fn failed_login_from_invite_keeps_invite() {
    let (fake, app) = setup(&[("OIDC_REQUIRED_GROUP", "WG")]).await;
    let mut u = FakeUser::new("outsider");
    u.groups = Some(vec!["Family".into()]);
    fake.set_user(u);
    let mut c = app.client();
    let cb = callback_url(&fake, &mut c, "/auth/oidc/start?invite=abc_DEF-123").await;
    assert_eq!(
        location(&c.get(&cb).await),
        "/login?error=not_allowed&invite=abc_DEF-123"
    );
    let mut c = app.client();
    let cb = callback_url(&fake, &mut c, "/auth/oidc/start?invite=..%2Fevil").await;
    assert_eq!(location(&c.get(&cb).await), "/login?error=not_allowed");
}

#[tokio::test]
async fn unreachable_provider_from_invite_keeps_invite() {
    let app = TestApp::with_env(&[
        ("OIDC_ISSUER", "http://127.0.0.1:9"),
        ("OIDC_CLIENT_ID", "id"),
        ("OIDC_CLIENT_SECRET", "s"),
    ]);
    let mut c = app.client();
    let r = c.get("/auth/oidc/start?invite=abc_DEF-123").await;
    assert_eq!(
        location(&r),
        "/login?error=sso_unavailable&invite=abc_DEF-123"
    );
}

#[tokio::test]
async fn sso_display_name_loses_control_and_bidi_characters() {
    let (fake, app) = setup(&[]).await;
    let mut u = FakeUser::new("bidi");
    u.name = Some("\u{202E}Jane\u{0007} Doe\n".into());
    fake.set_user(u);
    let mut c = app.client();
    sso_login(&fake, &mut c).await;
    assert_eq!(c.get("/api/v1/me").await.body["display_name"], "Jane Doe");
}
