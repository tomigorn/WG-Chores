mod support;
use chrono::Duration;
use serde_json::{Value, json};
use support::*;

const PW: &str = "correct horse battery";

async fn user(app: &TestApp, name: &str) -> (Client, i64) {
    let mut c = app.client();
    let r = c
        .post(
            "/api/v1/auth/register",
            json!({"username": name, "password": PW, "display_name": format!("Name {name}")}),
        )
        .await;
    assert_eq!(r.status, 201);
    let id = r.body["id"].as_i64().unwrap();
    (c, id)
}

async fn household(c: &mut Client, name: &str) -> i64 {
    let r = c.post("/api/v1/households", json!({"name": name})).await;
    assert_eq!(r.status, 201);
    r.body["id"].as_i64().unwrap()
}

fn token_of(body: &Value) -> String {
    let url = body["url"].as_str().unwrap();
    url.strip_prefix(&format!("{ORIGIN}/invite/"))
        .unwrap()
        .to_string()
}

async fn invite(c: &mut Client, hid: i64) -> (i64, String) {
    let r = c
        .post(&format!("/api/v1/households/{hid}/invites"), json!({}))
        .await;
    assert_eq!(r.status, 201, "{:?}", r.body);
    (r.body["id"].as_i64().unwrap(), token_of(&r.body))
}

fn accept(t: &str) -> String {
    format!("/api/v1/invites/{t}/accept")
}

async fn member_count(app: &TestApp, hid: i64) -> i64 {
    app.db(move |c| {
        c.query_row(
            "SELECT count(*) FROM memberships WHERE household_id = ?1",
            [hid],
            |r| r.get(0),
        )
    })
    .await
}

async fn closed_registration_owner(app: &TestApp) -> Client {
    app.seed_local_user("anna", PW).await;
    let mut a = app.client();
    assert_eq!(a.login("anna", PW).await.status, 200);
    a
}

#[tokio::test]
async fn invite_accept_adds_member_once() {
    let app = TestApp::new();
    let (mut a, aid) = user(&app, "anna").await;
    let (mut b, bid) = user(&app, "ben").await;
    let h = household(&mut a, "Home").await;
    let r = a
        .post(&format!("/api/v1/households/{h}/invites"), json!({}))
        .await;
    assert_eq!(r.status, 201);
    assert!(r.body["id"].is_i64());
    assert_eq!(r.body["expires_at"], "2026-01-08T12:00:00.000Z");
    let t = token_of(&r.body);
    assert_eq!(t.len(), 43);
    assert!(
        t.chars()
            .all(|c| c.is_ascii_alphanumeric() || c == '-' || c == '_')
    );

    let p = app.client().get(&format!("/api/v1/invites/{t}")).await;
    assert_eq!(p.status, 200);
    assert_eq!(p.body["household_name"], "Home");
    assert_eq!(p.body["invited_by"], "Name anna");
    assert_eq!(p.body["valid"], true);
    assert_eq!(p.body["reason"], Value::Null);
    assert_eq!(p.body["can_register"], true);
    assert_eq!(p.body["expires_at"], "2026-01-08T12:00:00.000Z");
    assert!(p.body.get("id").is_none() && p.body.get("household_id").is_none());

    assert_eq!(app.client().post(&accept(&t), json!({})).await.status, 401);
    let r = b.post(&accept(&t), json!({})).await;
    assert_eq!(r.status, 200);
    assert_eq!(r.body["household_id"], h);
    assert_eq!(member_count(&app, h).await, 2);
    let last: i64 = app
        .db(move |c| {
            c.query_row(
                "SELECT last_household_id FROM users WHERE id = ?1",
                [bid],
                |r| r.get(0),
            )
        })
        .await;
    assert_eq!(last, h);
    let (used_by, used_at): (i64, String) = app
        .db(|c| {
            c.query_row("SELECT used_by, used_at FROM invites", [], |r| {
                Ok((r.get(0)?, r.get(1)?))
            })
        })
        .await;
    assert_eq!(
        (used_by, used_at.as_str()),
        (bid, "2026-01-01T12:00:00.000Z")
    );
    assert_ne!(aid, bid);
    let p = app.client().get(&format!("/api/v1/invites/{t}")).await;
    assert_eq!(
        (p.body["valid"].clone(), p.body["reason"].clone()),
        (json!(false), json!("used"))
    );
    assert_eq!(p.body["can_register"], false);
    assert_eq!(
        a.get(&format!("/api/v1/households/{h}/invites")).await.body,
        json!([])
    );
}

#[tokio::test]
async fn invite_reuse_and_existing_member() {
    let app = TestApp::new();
    let (mut a, _) = user(&app, "anna").await;
    let (mut b, _) = user(&app, "ben").await;
    let (mut c, _) = user(&app, "cara").await;
    let h = household(&mut a, "Home").await;
    let (_, t) = invite(&mut a, h).await;
    assert_eq!(b.post(&accept(&t), json!({})).await.status, 200);
    let r = c.post(&accept(&t), json!({})).await;
    assert_eq!(
        (r.status, r.body["error"].as_str()),
        (410, Some("invite_used"))
    );
    assert_eq!(member_count(&app, h).await, 2);

    let (_, t2) = invite(&mut a, h).await;
    let r = a.post(&accept(&t2), json!({})).await;
    assert_eq!(r.status, 200);
    assert_eq!(r.body, json!({"household_id": h, "already_member": true}));
    assert_eq!(member_count(&app, h).await, 2);
    let p = app.client().get(&format!("/api/v1/invites/{t2}")).await;
    assert_eq!(p.body["valid"], true);
    assert_eq!(c.post(&accept(&t2), json!({})).await.status, 200);
}

#[tokio::test]
async fn expired_after_7_days() {
    let app = TestApp::new();
    let (mut a, _) = user(&app, "anna").await;
    let (mut b, _) = user(&app, "ben").await;
    let h = household(&mut a, "Home").await;
    let (_, t) = invite(&mut a, h).await;
    app.clock.advance(Duration::days(7) - Duration::seconds(1));
    let p = app.client().get(&format!("/api/v1/invites/{t}")).await;
    assert_eq!(p.body["valid"], true);
    assert_eq!(
        a.get(&format!("/api/v1/households/{h}/invites"))
            .await
            .body
            .as_array()
            .unwrap()
            .len(),
        1
    );
    app.clock.advance(Duration::seconds(1));
    let p = app.client().get(&format!("/api/v1/invites/{t}")).await;
    assert_eq!(
        (p.body["valid"].clone(), p.body["reason"].clone()),
        (json!(false), json!("expired"))
    );
    assert_eq!(p.body["can_register"], false);
    assert_eq!(
        a.get(&format!("/api/v1/households/{h}/invites")).await.body,
        json!([])
    );
    let r = b.post(&accept(&t), json!({})).await;
    assert_eq!(
        (r.status, r.body["error"].as_str()),
        (410, Some("invite_expired"))
    );
    assert_eq!(member_count(&app, h).await, 1);
}

#[tokio::test]
async fn cancelled_invite_refused() {
    let app = TestApp::new();
    let (mut a, _) = user(&app, "anna").await;
    let (mut b, _) = user(&app, "ben").await;
    let h = household(&mut a, "Home").await;
    let (id, t) = invite(&mut a, h).await;
    let path = format!("/api/v1/households/{h}/invites/{id}");
    assert_eq!(b.delete(&path, json!({})).await.status, 404);
    assert_eq!(a.delete(&path, json!({})).await.status, 204);
    assert_eq!(a.delete(&path, json!({})).await.status, 204);
    assert_eq!(
        a.delete(&format!("/api/v1/households/{h}/invites/9999"), json!({}))
            .await
            .status,
        404
    );
    let p = app.client().get(&format!("/api/v1/invites/{t}")).await;
    assert_eq!(
        (p.body["valid"].clone(), p.body["reason"].clone()),
        (json!(false), json!("cancelled"))
    );
    let r = b.post(&accept(&t), json!({})).await;
    assert_eq!(
        (r.status, r.body["error"].as_str()),
        (410, Some("invite_cancelled"))
    );
    assert_eq!(
        a.get(&format!("/api/v1/households/{h}/invites")).await.body,
        json!([])
    );
}

#[tokio::test]
async fn cancel_is_scoped_to_household() {
    let app = TestApp::new();
    let (mut a, _) = user(&app, "anna").await;
    let (mut b, _) = user(&app, "ben").await;
    let h1 = household(&mut a, "One").await;
    let h2 = household(&mut b, "Two").await;
    let (id, t) = invite(&mut a, h1).await;
    let r = b
        .delete(&format!("/api/v1/households/{h2}/invites/{id}"), json!({}))
        .await;
    assert_eq!(r.status, 404);
    assert_eq!(
        app.client().get(&format!("/api/v1/invites/{t}")).await.body["valid"],
        true
    );
}

#[tokio::test]
async fn register_via_invite_when_registration_closed() {
    let app = TestApp::with_env(&[("OPEN_REGISTRATION", "false")]);
    let mut a = closed_registration_owner(&app).await;
    let h = household(&mut a, "Home").await;
    let (_, t) = invite(&mut a, h).await;
    let body = |name: &str, tok: &str| json!({"username": name, "password": PW, "display_name": "Jane Doe", "invite": tok});
    let mut c = app.client();
    assert_eq!(
        c.post(
            "/api/v1/auth/register",
            json!({"username": "jane", "password": PW, "display_name": "J"})
        )
        .await
        .body["error"],
        "registration_closed"
    );
    let bad = c.post("/api/v1/auth/register", body("jane", "nope")).await;
    assert_eq!(
        (bad.status, bad.body["error"].as_str()),
        (404, Some("invite_not_found"))
    );
    let unknown = "A".repeat(43);
    assert_eq!(
        c.post("/api/v1/auth/register", body("jane", &unknown))
            .await
            .status,
        404
    );
    let p = app.client().get(&format!("/api/v1/invites/{t}")).await;
    assert_eq!(p.body["can_register"], true);

    let r = c.post("/api/v1/auth/register", body("jane", &t)).await;
    assert_eq!(r.status, 201, "{:?}", r.body);
    assert_eq!(c.get("/api/v1/households").await.body[0]["id"], h);
    assert_eq!(member_count(&app, h).await, 2);
    let mut c2 = app.client();
    let r = c2.post("/api/v1/auth/register", body("june", &t)).await;
    assert_eq!(
        (r.status, r.body["error"].as_str()),
        (410, Some("invite_used"))
    );
    let n: i64 = app
        .db(|c| {
            c.query_row(
                "SELECT count(*) FROM users WHERE username = 'june'",
                [],
                |r| r.get(0),
            )
        })
        .await;
    assert_eq!(n, 0);
}

#[tokio::test]
async fn register_with_expired_or_cancelled_invite_creates_no_account() {
    let app = TestApp::with_env(&[("OPEN_REGISTRATION", "false")]);
    let mut a = closed_registration_owner(&app).await;
    let h = household(&mut a, "Home").await;
    let (id, t) = invite(&mut a, h).await;
    let (_, t2) = invite(&mut a, h).await;
    a.delete(&format!("/api/v1/households/{h}/invites/{id}"), json!({}))
        .await;
    app.clock.advance(Duration::days(8));
    let reg = |name: &str, tok: &str| json!({"username": name, "password": PW, "display_name": "J", "invite": tok});
    let r = app
        .client()
        .post("/api/v1/auth/register", reg("jane", &t))
        .await;
    assert_eq!(
        (r.status, r.body["error"].as_str()),
        (410, Some("invite_cancelled"))
    );
    let r = app
        .client()
        .post("/api/v1/auth/register", reg("jane", &t2))
        .await;
    assert_eq!(
        (r.status, r.body["error"].as_str()),
        (410, Some("invite_expired"))
    );
    let n: i64 = app
        .db(|c| {
            c.query_row(
                "SELECT count(*) FROM users WHERE username = 'jane'",
                [],
                |r| r.get(0),
            )
        })
        .await;
    assert_eq!(n, 0);
}

#[tokio::test]
async fn register_via_invite_refused_when_local_off() {
    let app = TestApp::with_env(&[("LOCAL_ACCOUNTS", "false")]);
    let sso = app.seed_sso_user("Anna").await;
    let now = "2026-01-01T12:00:00.000Z";
    let t = "B".repeat(43);
    let hash = wg_chores::auth::session::hash_token(&t);
    app.db(move |c| {
        c.execute("INSERT INTO households (name, created_at) VALUES ('Home', ?1)", [now])?;
        let hid = c.last_insert_rowid();
        c.execute(
            "INSERT INTO invites (token_hash, household_id, created_by, created_at, expires_at) VALUES (?1, ?2, ?3, ?4, '2026-01-08T12:00:00.000Z')",
            rusqlite::params![hash, hid, sso, now],
        )
    })
    .await;
    let p = app.client().get(&format!("/api/v1/invites/{t}")).await;
    assert_eq!(
        (p.body["valid"].clone(), p.body["can_register"].clone()),
        (json!(true), json!(false))
    );
    let r = app
        .client()
        .post(
            "/api/v1/auth/register",
            json!({"username": "jane", "password": PW, "display_name": "J", "invite": t}),
        )
        .await;
    assert_eq!(
        (r.status, r.body["error"].as_str()),
        (403, Some("local_accounts_disabled"))
    );
}

#[tokio::test]
async fn token_stored_hashed() {
    let app = TestApp::new();
    let (mut a, _) = user(&app, "anna").await;
    let h = household(&mut a, "Home").await;
    let (_, t) = invite(&mut a, h).await;
    let stored: String = app
        .db(|c| c.query_row("SELECT token_hash FROM invites", [], |r| r.get(0)))
        .await;
    assert_ne!(stored, t);
    assert!(!stored.contains(&t));
    assert_eq!(stored, wg_chores::auth::session::hash_token(&t));
    assert_eq!(stored.len(), 64);
    let l = a.get(&format!("/api/v1/households/{h}/invites")).await;
    assert!(!l.body.to_string().contains(&t));
    assert!(l.body[0].get("url").is_none() && l.body[0].get("token").is_none());
}

#[tokio::test]
async fn malformed_and_unknown_tokens_are_404() {
    let app = TestApp::new();
    let (mut a, _) = user(&app, "anna").await;
    let unknown = "C".repeat(43);
    for t in [
        "short",
        unknown.as_str(),
        &"a".repeat(44),
        "..%2Fevil",
        &format!("{}%25", "a".repeat(42)),
    ] {
        let p = app.client().get(&format!("/api/v1/invites/{t}")).await;
        assert_eq!(
            (p.status, p.body["error"].as_str()),
            (404, Some("invite_not_found")),
            "{t}"
        );
        let r = a.post(&accept(t), json!({})).await;
        assert_eq!(
            (r.status, r.body["error"].as_str()),
            (404, Some("invite_not_found")),
            "{t}"
        );
    }
}

#[tokio::test]
async fn create_list_limits_and_membership() {
    let app = TestApp::new();
    let (mut a, aid) = user(&app, "anna").await;
    let (mut b, bid) = user(&app, "ben").await;
    let h = household(&mut a, "Home").await;
    let base = format!("/api/v1/households/{h}/invites");
    assert_eq!(b.post(&base, json!({})).await.status, 404);
    assert_eq!(b.get(&base).await.status, 404);
    assert_eq!(app.client().post(&base, json!({})).await.status, 401);
    let (id, t) = invite(&mut a, h).await;
    let u = a.get(&base).await;
    assert_eq!(
        u.body,
        json!([{"id": id, "created_by": {"id": aid, "display_name": "Name anna"}, "created_at": "2026-01-01T12:00:00.000Z", "expires_at": "2026-01-08T12:00:00.000Z"}])
    );
    assert_eq!(b.post(&accept(&t), json!({})).await.status, 200);
    let (id2, _) = invite(&mut b, h).await;
    let l = b.get(&base).await;
    assert_eq!(l.body[0]["id"], id2);
    assert_eq!(l.body[0]["created_by"]["id"], bid);
    for _ in 0..19 {
        invite(&mut a, h).await;
    }
    let r = a.post(&base, json!({})).await;
    assert_eq!(
        (r.status, r.body["error"].as_str()),
        (409, Some("too_many_invites"))
    );
    a.delete(&format!("{base}/{id2}"), json!({})).await;
    assert_eq!(a.post(&base, json!({})).await.status, 201);
}

#[tokio::test]
async fn parallel_accepts_only_one_wins() {
    let app = TestApp::new();
    let (mut a, _) = user(&app, "anna").await;
    let h = household(&mut a, "Home").await;
    let (_, t) = invite(&mut a, h).await;
    let mut tasks = Vec::new();
    for i in 0..8 {
        let (mut c, _) = user(&app, &format!("user{i}")).await;
        let t = t.clone();
        tasks.push(tokio::spawn(async move {
            c.post(&accept(&t), json!({})).await.status
        }));
    }
    let mut ok = 0;
    for task in tasks {
        match task.await.unwrap() {
            200 => ok += 1,
            410 => {}
            s => panic!("unexpected {s}"),
        }
    }
    assert_eq!(ok, 1);
    assert_eq!(member_count(&app, h).await, 2);
}

#[tokio::test]
async fn deleting_household_removes_invites() {
    let app = TestApp::new();
    let (mut a, _) = user(&app, "anna").await;
    let h = household(&mut a, "Home").await;
    let (_, t) = invite(&mut a, h).await;
    let r = a
        .post(
            &format!("/api/v1/households/{h}/leave"),
            json!({"confirm_delete": true}),
        )
        .await;
    assert_eq!(r.status, 204);
    assert_eq!(
        app.client()
            .get(&format!("/api/v1/invites/{t}"))
            .await
            .status,
        404
    );
}

async fn assert_cancelled(app: &TestApp, t: &str) {
    let p = app.client().get(&format!("/api/v1/invites/{t}")).await;
    assert_eq!(
        (p.body["valid"].clone(), p.body["reason"].clone()),
        (json!(false), json!("cancelled"))
    );
    let (mut c, _) = user(
        app,
        &format!("late{}", t[..6].to_lowercase().replace(['-', '_'], "x")),
    )
    .await;
    let r = c.post(&accept(t), json!({})).await;
    assert_eq!(
        (r.status, r.body["error"].as_str()),
        (410, Some("invite_cancelled"))
    );
}

#[tokio::test]
async fn removed_member_invites_cancelled() {
    let app = TestApp::new();
    let (mut a, _) = user(&app, "anna").await;
    let (mut b, bid) = user(&app, "ben").await;
    let h = household(&mut a, "Home").await;
    let (_, tb) = invite(&mut a, h).await;
    assert_eq!(b.post(&accept(&tb), json!({})).await.status, 200);
    let (_, t_ben) = invite(&mut b, h).await;
    let (_, t_anna) = invite(&mut a, h).await;
    let r = a
        .delete(&format!("/api/v1/households/{h}/members/{bid}"), json!({}))
        .await;
    assert_eq!(r.status, 204);
    assert_cancelled(&app, &t_ben).await;
    assert_eq!(
        app.client()
            .get(&format!("/api/v1/invites/{t_anna}"))
            .await
            .body["valid"],
        true
    );
}

#[tokio::test]
async fn leaving_member_invites_cancelled() {
    let app = TestApp::new();
    let (mut a, _) = user(&app, "anna").await;
    let (mut b, _) = user(&app, "ben").await;
    let h = household(&mut a, "Home").await;
    let (_, tb) = invite(&mut a, h).await;
    assert_eq!(b.post(&accept(&tb), json!({})).await.status, 200);
    let (_, t_ben) = invite(&mut b, h).await;
    assert_eq!(
        b.post(&format!("/api/v1/households/{h}/leave"), json!({}))
            .await
            .status,
        204
    );
    assert_cancelled(&app, &t_ben).await;
}

#[tokio::test]
async fn deleted_account_invites_cancelled() {
    let app = TestApp::new();
    let (mut a, _) = user(&app, "anna").await;
    let (mut b, _) = user(&app, "ben").await;
    let h = household(&mut a, "Home").await;
    let (_, tb) = invite(&mut a, h).await;
    assert_eq!(b.post(&accept(&tb), json!({})).await.status, 200);
    let (_, t_ben) = invite(&mut b, h).await;
    let (_, t_anna) = invite(&mut a, h).await;
    let r = b
        .delete("/api/v1/me", json!({"confirm_username": "ben"}))
        .await;
    assert_eq!(r.status, 204);
    assert_cancelled(&app, &t_ben).await;
    assert_eq!(
        app.client()
            .get(&format!("/api/v1/invites/{t_anna}"))
            .await
            .body["valid"],
        true
    );
}
