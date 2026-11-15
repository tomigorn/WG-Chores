mod support;
use serde_json::json;
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

#[tokio::test]
async fn create_and_list() {
    let app = TestApp::new();
    let (mut a, _) = user(&app, "anna").await;
    assert_eq!(a.get("/api/v1/households").await.body, json!([]));
    let bad = a.post("/api/v1/households", json!({"name": "  "})).await;
    assert_eq!(bad.status, 400);
    let r = a
        .post("/api/v1/households", json!({"name": "  Zebra  "}))
        .await;
    assert_eq!(r.status, 201);
    assert_eq!(r.body["name"], "Zebra");
    assert_eq!(r.body["member_count"], 1);
    household(&mut a, "apple").await;
    let list = a.get("/api/v1/households").await;
    assert_eq!(list.status, 200);
    let names: Vec<_> = list
        .body
        .as_array()
        .unwrap()
        .iter()
        .map(|h| h["name"].as_str().unwrap())
        .collect();
    assert_eq!(names, ["apple", "Zebra"]);
    let id = r.body["id"].as_i64().unwrap();
    let one = a.get(&format!("/api/v1/households/{id}")).await;
    assert_eq!(one.body["name"], "Zebra");
    let p = a
        .patch(&format!("/api/v1/households/{id}"), json!({"name": "Zoo"}))
        .await;
    assert_eq!(p.status, 200);
    assert_eq!(p.body["name"], "Zoo");
    let p = a
        .patch(&format!("/api/v1/households/{id}"), json!({"name": ""}))
        .await;
    assert_eq!(p.status, 400);
    let mut anon = app.client();
    assert_eq!(anon.get("/api/v1/households").await.status, 401);
}

#[tokio::test]
async fn non_member_gets_404() {
    let app = TestApp::new();
    let (mut a, _) = user(&app, "anna").await;
    let (mut b, bid) = user(&app, "ben").await;
    let h = household(&mut a, "Home").await;
    let base = format!("/api/v1/households/{h}");
    assert_eq!(b.get(&base).await.status, 404);
    assert_eq!(b.patch(&base, json!({"name": "x"})).await.status, 404);
    assert_eq!(b.get(&format!("{base}/members")).await.status, 404);
    assert_eq!(
        b.post(&format!("{base}/members"), json!({"username": "ben"}))
            .await
            .status,
        404
    );
    assert_eq!(
        b.post(&format!("{base}/leave"), json!({})).await.status,
        404
    );
    assert_eq!(
        b.delete(&format!("{base}/members/{bid}"), json!({}))
            .await
            .status,
        404
    );
    assert_eq!(b.get("/api/v1/households/9999").await.status, 404);
    assert_eq!(b.get("/api/v1/households").await.body, json!([]));
}

#[tokio::test]
async fn add_member_by_exact_username() {
    let app = TestApp::new();
    let (mut a, aid) = user(&app, "anna").await;
    let (mut b, bid) = user(&app, "ben").await;
    let h = household(&mut a, "Home").await;
    let m = format!("/api/v1/households/{h}/members");
    assert_eq!(a.post(&m, json!({"username": "be"})).await.status, 404);
    let r = a.post(&m, json!({"username": "BEN"})).await;
    assert_eq!(r.status, 201);
    assert_eq!(r.body["id"], bid);
    assert_eq!(
        a.post(&m, json!({"username": "ben"})).await.body["error"],
        "already_member"
    );
    let nf = a.post(&m, json!({"username": "nobody"})).await;
    assert_eq!(
        (nf.status, nf.body["error"].as_str()),
        (404, Some("user_not_found"))
    );
    let list = b.get(&m).await;
    assert_eq!(list.status, 200);
    let ids: Vec<_> = list
        .body
        .as_array()
        .unwrap()
        .iter()
        .map(|u| u["id"].as_i64().unwrap())
        .collect();
    assert_eq!(ids, [aid, bid]);
    assert_eq!(list.body[0]["display_name"], "Name anna");
    assert_eq!(list.body[0]["username"], "anna");
    assert!(list.body[0]["joined_at"].is_string());
    assert!(list.body[0].get("picture_url").is_some());
    assert_eq!(b.get("/api/v1/households").await.body[0]["member_count"], 2);
    // former users cannot be added
    app.db(move |c| c.execute("UPDATE users SET former = 1 WHERE id = ?1", [bid]))
        .await;
    let (mut c3, _) = user(&app, "cara").await;
    let h2 = household(&mut c3, "Other").await;
    let r = c3
        .post(
            &format!("/api/v1/households/{h2}/members"),
            json!({"username": "ben"}),
        )
        .await;
    assert_eq!(r.status, 404);
    assert_eq!(a.get(&m).await.body.as_array().unwrap().len(), 1);
}

#[tokio::test]
async fn directory_off_hides_user_search() {
    let app = TestApp::new();
    let (mut a, _) = user(&app, "anna").await;
    user(&app, "ben").await;
    assert_eq!(a.get("/api/v1/users?q=be").await.status, 404);
    let h = household(&mut a, "Home").await;
    let r = a
        .post(
            &format!("/api/v1/households/{h}/members"),
            json!({"user_id": 2}),
        )
        .await;
    assert_eq!(r.status, 400);
}

#[tokio::test]
async fn directory_on_searches_name_and_username() {
    let app = TestApp::with_env(&[("MEMBER_DIRECTORY", "true")]);
    let (mut a, _) = user(&app, "anna").await;
    let (_, bid) = user(&app, "ben").await;
    let (_, fid) = user(&app, "fred").await;
    app.db(move |c| c.execute("UPDATE users SET former = 1 WHERE id = ?1", [fid]))
        .await;
    app.seed_sso_user("Bella Rossi").await;
    assert_eq!(a.get("/api/v1/users?q=b").await.status, 400);
    assert_eq!(a.get("/api/v1/users").await.status, 400);
    let r = a.get("/api/v1/users?q=BE").await;
    assert_eq!(r.status, 200);
    let names: Vec<_> = r
        .body
        .as_array()
        .unwrap()
        .iter()
        .map(|u| u["display_name"].as_str().unwrap())
        .collect();
    assert_eq!(names, ["Bella Rossi", "Name ben"]);
    assert!(r.body[1].get("picture_url").is_some());
    assert_eq!(a.get("/api/v1/users?q=anna").await.body, json!([]));
    assert_eq!(a.get("/api/v1/users?q=fred").await.body, json!([]));
    assert_eq!(
        a.get("/api/v1/users?q=ame%20be")
            .await
            .body
            .as_array()
            .unwrap()
            .len(),
        1
    );
    let h = household(&mut a, "Home").await;
    let m = format!("/api/v1/households/{h}/members");
    let r = a.post(&m, json!({"user_id": bid})).await;
    assert_eq!(r.status, 201);
    assert_eq!(
        a.post(&m, json!({"user_id": bid})).await.body["error"],
        "already_member"
    );
    assert_eq!(a.post(&m, json!({"user_id": fid})).await.status, 404);
    assert_eq!(a.post(&m, json!({"user_id": 999})).await.status, 404);
    let mut anon = app.client();
    assert_eq!(anon.get("/api/v1/users?q=be").await.status, 401);
}

#[tokio::test]
async fn leave_last_member_needs_confirm() {
    let app = TestApp::new();
    let (mut a, _) = user(&app, "anna").await;
    let (mut b, _) = user(&app, "ben").await;
    let h = household(&mut a, "Home").await;
    let base = format!("/api/v1/households/{h}");
    a.post(&format!("{base}/members"), json!({"username": "ben"}))
        .await;
    let r = a.post(&format!("{base}/leave"), json!({})).await;
    assert_eq!(r.status, 204);
    assert_eq!(a.get(&base).await.status, 404);
    let r = b.post(&format!("{base}/leave"), json!({})).await;
    assert_eq!(
        (r.status, r.body["error"].as_str()),
        (409, Some("last_member_confirm"))
    );
    let r = b
        .post(&format!("{base}/leave"), json!({"confirm_delete": false}))
        .await;
    assert_eq!(r.status, 409);
    app.db(move |c| {
        c.execute("INSERT INTO chores (id, household_id, name, created_at) VALUES (1, ?1, 'Dust', 'x')", [h])?;
        c.execute("INSERT INTO categories (household_id, name) VALUES (?1, 'Kitchen')", [h])?;
        c.execute("INSERT INTO completions (id, chore_id, user_id, done_on, created_at) VALUES ('c1', 1, 2, '2026-01-01', 'x')", [])
    })
    .await;
    assert_eq!(b.get(&base).await.status, 200);
    let r = b
        .post(&format!("{base}/leave"), json!({"confirm_delete": true}))
        .await;
    assert_eq!(r.status, 204);
    let left = app
        .db(|c| c.query_row("SELECT (SELECT COUNT(*) FROM households) + (SELECT COUNT(*) FROM chores) + (SELECT COUNT(*) FROM categories) + (SELECT COUNT(*) FROM completions)", [], |r| r.get::<_, i64>(0)))
        .await;
    assert_eq!(left, 0);
    assert_eq!(b.get("/api/v1/households").await.body, json!([]));
}

#[tokio::test]
async fn remove_member_keeps_their_history() {
    let app = TestApp::new();
    let (mut a, aid) = user(&app, "anna").await;
    let (mut b, bid) = user(&app, "ben").await;
    let h = household(&mut a, "Home").await;
    let base = format!("/api/v1/households/{h}");
    a.post(&format!("{base}/members"), json!({"username": "ben"}))
        .await;
    app.db(move |c| {
        c.execute("INSERT INTO chores (id, household_id, name, created_at) VALUES (1, ?1, 'Dust', 'x')", [h])?;
        c.execute("INSERT INTO completions (id, chore_id, user_id, done_on, created_at) VALUES ('c1', 1, ?1, '2026-01-01', 'x')", [bid])
    })
    .await;
    let r = a.delete(&format!("{base}/members/{bid}"), json!({})).await;
    assert_eq!(r.status, 204);
    assert_eq!(b.get(&base).await.status, 404);
    let members = a.get(&format!("{base}/members")).await;
    assert_eq!(members.body.as_array().unwrap().len(), 1);
    let (n, former) = app
        .db(move |c| {
            Ok((
                c.query_row(
                    "SELECT COUNT(*) FROM completions WHERE user_id = ?1",
                    [bid],
                    |r| r.get::<_, i64>(0),
                )?,
                c.query_row("SELECT former FROM users WHERE id = ?1", [bid], |r| {
                    r.get::<_, i64>(0)
                })?,
            ))
        })
        .await;
    assert_eq!((n, former), (1, 0));
    assert_eq!(
        a.delete(&format!("{base}/members/{bid}"), json!({}))
            .await
            .status,
        404
    );
    // removing yourself counts as leaving, and the last member needs the confirmation
    let r = a.delete(&format!("{base}/members/{aid}"), json!({})).await;
    assert_eq!(r.status, 409);
    assert_eq!(
        a.delete(
            &format!("{base}/members/{aid}"),
            json!({"confirm_delete": true})
        )
        .await
        .status,
        204
    );
}

#[tokio::test]
async fn last_household_remembered() {
    let app = TestApp::new();
    let (mut a, _) = user(&app, "anna").await;
    let (mut b, _) = user(&app, "ben").await;
    assert_eq!(
        a.get("/api/v1/me").await.body["last_household_id"],
        json!(null)
    );
    let h1 = household(&mut a, "One").await;
    assert_eq!(a.get("/api/v1/me").await.body["last_household_id"], h1);
    let h2 = household(&mut a, "Two").await;
    assert_eq!(a.get("/api/v1/me").await.body["last_household_id"], h2);
    let r = a
        .send(
            axum::http::Method::PUT,
            "/api/v1/me/last-household",
            Some(json!({"id": h1})),
        )
        .await;
    assert_eq!(r.status, 204);
    assert_eq!(a.get("/api/v1/me").await.body["last_household_id"], h1);
    let r = b
        .send(
            axum::http::Method::PUT,
            "/api/v1/me/last-household",
            Some(json!({"id": h1})),
        )
        .await;
    assert_eq!(r.status, 404);
    assert_eq!(
        b.get("/api/v1/me").await.body["last_household_id"],
        json!(null)
    );
    a.post(
        &format!("/api/v1/households/{h1}/members"),
        json!({"username": "ben"}),
    )
    .await;
    b.send(
        axum::http::Method::PUT,
        "/api/v1/me/last-household",
        Some(json!({"id": h1})),
    )
    .await;
    assert_eq!(b.get("/api/v1/me").await.body["last_household_id"], h1);
    // removed from it: cleared
    let bid = b.get("/api/v1/me").await.body["id"].as_i64().unwrap();
    a.delete(&format!("/api/v1/households/{h1}/members/{bid}"), json!({}))
        .await;
    assert_eq!(
        b.get("/api/v1/me").await.body["last_household_id"],
        json!(null)
    );
    // leaving clears it too
    let r = a
        .post(
            &format!("/api/v1/households/{h1}/leave"),
            json!({"confirm_delete": true}),
        )
        .await;
    assert_eq!(r.status, 204);
    assert_eq!(
        a.get("/api/v1/me").await.body["last_household_id"],
        json!(null)
    );
}

#[tokio::test]
async fn unicode_search_and_sorting() {
    let app = TestApp::with_env(&[("MEMBER_DIRECTORY", "true")]);
    let (mut a, _) = user(&app, "anna").await;
    app.seed_sso_user("Ärger").await;
    for q in ["%C3%84R", "%C3%A4r", "%C3%A4R"] {
        let r = a.get(&format!("/api/v1/users?q={q}")).await;
        assert_eq!(r.body.as_array().map(|v| v.len()), Some(1), "q={q}");
    }
    for n in ["Zürich", "älpli", "Bern"] {
        household(&mut a, n).await;
    }
    let list = a.get("/api/v1/households").await;
    let names: Vec<_> = list
        .body
        .as_array()
        .unwrap()
        .iter()
        .map(|h| h["name"].as_str().unwrap())
        .collect();
    assert_eq!(names, ["Bern", "Zürich", "älpli"]);
}

#[tokio::test]
async fn member_edge_cases() {
    let app = TestApp::with_env(&[("MEMBER_DIRECTORY", "true")]);
    let (mut a, _) = user(&app, "anna").await;
    let (_, bid) = user(&app, "ben").await;
    let h = household(&mut a, "Home").await;
    let base = format!("/api/v1/households/{h}");
    let r = a.delete(&format!("{base}/members/{bid}"), json!({})).await;
    assert_eq!(r.status, 404);
    let r = a
        .post(
            &format!("{base}/members"),
            json!({"user_id": bid, "username": "ben"}),
        )
        .await;
    assert_eq!(r.status, 400);
    let r = a.patch(&base, json!({"name": "  Flat  "})).await;
    assert_eq!(r.body["name"], "Flat");
}

#[tokio::test]
async fn add_member_is_rate_limited_per_acting_user() {
    let app = TestApp::new();
    let (mut a, _) = signup(&app, "anna").await;
    let (mut b, _) = signup(&app, "ben").await;
    let ha = new_household(&mut a, "A").await;
    let hb = new_household(&mut b, "B").await;
    for i in 0..20 {
        let r = a
            .post(
                &format!("/api/v1/households/{ha}/members"),
                json!({"username": format!("ghost{i}")}),
            )
            .await;
        assert_eq!(r.status, 404, "attempt {i}");
    }
    let r = a
        .post(
            &format!("/api/v1/households/{ha}/members"),
            json!({"username": "ben"}),
        )
        .await;
    assert_eq!(r.status, 429);
    assert!(r.headers.get("retry-after").is_some());
    add_to_household(&mut b, hb, "anna").await;
    app.clock.advance(chrono::Duration::minutes(61));
    add_to_household(&mut a, ha, "ben").await;
}
