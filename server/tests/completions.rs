mod support;
use serde_json::{Value, json};
use support::*;

fn cpath(chore: i64) -> String {
    format!("/api/v1/chores/{chore}/completions")
}

async fn setup() -> (TestApp, Client, i64, i64) {
    let app = TestApp::new();
    let (mut a, _) = signup(&app, "anna").await;
    let h = new_household(&mut a, "Home").await;
    let c = new_chore(&mut a, h, json!({"name": "Bins", "interval": "1w"})).await;
    (app, a, h, c)
}

#[tokio::test]
async fn retry_with_same_uuid_is_idempotent() {
    let (_app, mut a, _, c) = setup().await;
    let body = json!({"id": uuid(1), "done_on": "2025-12-30", "note": "first"});
    let r1 = a.post(&cpath(c), body.clone()).await;
    assert_eq!(r1.status, 201);
    assert_eq!(r1.body["id"], uuid(1));
    assert_eq!(r1.body["note"], "first");
    let r2 = a.post(&cpath(c), body).await;
    assert_eq!(r2.status, 200);
    assert_eq!(r2.body, r1.body);
    let r3 = a
        .post(
            &cpath(c),
            json!({"id": uuid(1), "done_on": "2025-12-01", "note": "other"}),
        )
        .await;
    assert_eq!(r3.status, 200);
    assert_eq!(r3.body, r1.body);
    assert_eq!(
        a.get(&cpath(c)).await.body["items"]
            .as_array()
            .unwrap()
            .len(),
        1
    );
    assert_eq!(done(&mut a, c, 2, "2025-12-30").await.status, 201);
    assert_eq!(done(&mut a, c, 3, "2025-12-30").await.status, 201);
    assert_eq!(
        a.get(&cpath(c)).await.body["items"]
            .as_array()
            .unwrap()
            .len(),
        3
    );
}

#[tokio::test]
async fn replay_of_an_undone_entry_is_gone() {
    let (_app, mut a, _, c) = setup().await;
    let body = json!({"id": uuid(1), "done_on": "2025-12-30"});
    assert_eq!(a.post(&cpath(c), body.clone()).await.status, 201);
    let one = format!("/api/v1/completions/{}", uuid(1));
    assert_eq!(a.delete(&one, json!({})).await.status, 204);
    let r = a.post(&cpath(c), body).await;
    assert_eq!(r.status, 410);
    assert_eq!(r.body["error"], "deleted");
    assert_eq!(
        a.get(&cpath(c)).await.body["items"]
            .as_array()
            .unwrap()
            .len(),
        0
    );
}

#[tokio::test]
async fn completion_id_validation_and_conflict() {
    let (_app, mut a, h, c) = setup().await;
    for id in ["nope", "", "00000000-0000-4000-8000-00000000000g"] {
        let r = a.post(&cpath(c), json!({"id": id})).await;
        assert_eq!((r.status, id), (400, id));
    }
    assert_eq!(a.post(&cpath(c), json!({})).await.status, 400);
    let other = new_chore(&mut a, h, json!({"name": "Dust"})).await;
    assert_eq!(done(&mut a, c, 1, "2025-12-30").await.status, 201);
    let r = a.post(&cpath(other), json!({"id": uuid(1)})).await;
    assert_eq!(r.status, 409);
    let r = a
        .post(&cpath(c), json!({"id": uuid(2), "note": "n".repeat(501)}))
        .await;
    assert_eq!(r.status, 400);
    let r = a
        .post(&cpath(c), json!({"id": uuid(3), "note": "n".repeat(500)}))
        .await;
    assert_eq!(r.status, 201);
}

#[tokio::test]
async fn future_date_rejected() {
    let (_app, mut a, _, c) = setup().await;
    let r = done(&mut a, c, 1, "2026-01-02").await;
    assert_eq!(
        (r.status, r.body["error"].as_str()),
        (400, Some("future_date"))
    );
    assert_eq!(done(&mut a, c, 1, "2026-01-01").await.status, 201);
    assert_eq!(done(&mut a, c, 2, "2020-12-31").await.status, 400);
    assert_eq!(done(&mut a, c, 2, "2021-01-01").await.status, 201);
    assert_eq!(done(&mut a, c, 3, "garbage").await.status, 400);
    assert_eq!(done(&mut a, c, 3, "2025-1-5").await.status, 400);
    let p = a
        .patch(
            &format!("/api/v1/completions/{}", uuid(1)),
            json!({"done_on": "2026-01-02"}),
        )
        .await;
    assert_eq!(
        (p.status, p.body["error"].as_str()),
        (400, Some("future_date"))
    );
}

#[tokio::test]
async fn done_on_uses_server_tz() {
    let app = TestApp::with_env(&[("TZ", "Europe/Zurich")]);
    let (mut a, _) = signup(&app, "anna").await;
    let h = new_household(&mut a, "Home").await;
    let c = new_chore(&mut a, h, json!({"name": "Bins"})).await;
    // 23:30 UTC on Jan 1 is 00:30 on Jan 2 in Zurich
    app.clock.advance(chrono::Duration::minutes(11 * 60 + 30));
    let r = a.post(&cpath(c), json!({"id": uuid(1)})).await;
    assert_eq!(r.status, 201);
    assert_eq!(r.body["done_on"], "2026-01-02");
    assert_eq!(done(&mut a, c, 2, "2026-01-02").await.status, 201);
    assert_eq!(done(&mut a, c, 3, "2026-01-03").await.status, 400);
    let list = a.get(&format!("/api/v1/households/{h}/chores")).await;
    assert_eq!(list.body[0]["days_since"], 0);
}

#[tokio::test]
async fn history_paging_newest_first() {
    let (_app, mut a, _, c) = setup().await;
    for (n, d) in [
        (1, "2025-12-01"),
        (2, "2025-12-05"),
        (3, "2025-12-05"),
        (4, "2025-12-10"),
        (5, "2025-12-03"),
    ] {
        assert_eq!(done(&mut a, c, n, d).await.status, 201);
    }
    let p1 = a.get(&format!("{}?limit=2", cpath(c))).await;
    assert_eq!(p1.status, 200);
    let ids = |r: &Resp| -> Vec<String> {
        r.body["items"]
            .as_array()
            .unwrap()
            .iter()
            .map(|i| i["id"].as_str().unwrap().to_string())
            .collect()
    };
    assert_eq!(ids(&p1), [uuid(4), uuid(3)]);
    let next = p1.body["next"].as_str().unwrap().to_string();
    assert_eq!(next, format!("2025-12-05,{}", uuid(3)));
    let p2 = a.get(&format!("{}?limit=2&before={next}", cpath(c))).await;
    assert_eq!(ids(&p2), [uuid(2), uuid(5)]);
    let next = p2.body["next"].as_str().unwrap().to_string();
    let p3 = a.get(&format!("{}?limit=2&before={next}", cpath(c))).await;
    assert_eq!(ids(&p3), [uuid(1)]);
    assert_eq!(p3.body["next"], Value::Null);
    let all = a.get(&cpath(c)).await;
    assert_eq!(ids(&all).len(), 5);
    assert_eq!(all.body["next"], Value::Null);
    assert_eq!(
        a.get(&format!("{}?before=bogus", cpath(c))).await.status,
        400
    );
    assert_eq!(a.get(&format!("{}?limit=x", cpath(c))).await.status, 400);
    let big = a.get(&format!("{}?limit=1000", cpath(c))).await;
    assert_eq!(big.status, 200);
}

#[tokio::test]
async fn any_member_can_edit_or_delete_entry() {
    let (app, mut a, h, c) = setup().await;
    let (mut b, bid) = signup(&app, "ben").await;
    add_to_household(&mut a, h, "ben").await;
    assert_eq!(done(&mut a, c, 1, "2025-12-20").await.status, 201);
    assert_eq!(done(&mut a, c, 2, "2025-12-25").await.status, 201);
    let list = format!("/api/v1/households/{h}/chores");
    let one = |id: u64| format!("/api/v1/completions/{}", uuid(id));
    let p = b
        .patch(&one(2), json!({"done_on": "2025-12-28", "note": "fixed"}))
        .await;
    assert_eq!(p.status, 200);
    assert_eq!(p.body["done_on"], "2025-12-28");
    assert_eq!(p.body["note"], "fixed");
    assert_eq!(p.body["by"]["display_name"], "Name anna");
    assert_ne!(p.body["by"]["id"], bid);
    let p = b.patch(&one(2), json!({"note": null})).await;
    assert_eq!(p.body["note"], Value::Null);
    assert_eq!(p.body["done_on"], "2025-12-28");
    assert_eq!(a.get(&list).await.body[0]["last"]["done_on"], "2025-12-28");
    let d = b.delete(&one(2), json!({})).await;
    assert_eq!(d.status, 204);
    assert_eq!(a.get(&list).await.body[0]["last"]["done_on"], "2025-12-20");
    assert_eq!(
        a.get(&cpath(c)).await.body["items"]
            .as_array()
            .unwrap()
            .len(),
        1
    );
    assert_eq!(b.delete(&one(2), json!({})).await.status, 404);
    assert_eq!(b.patch(&one(2), json!({"note": "x"})).await.status, 404);
    assert_eq!(b.delete(&one(1), json!({})).await.status, 204);
    assert_eq!(a.get(&list).await.body[0]["last"], Value::Null);
    let (mut z, _) = signup(&app, "zed").await;
    assert_eq!(done(&mut a, c, 3, "2025-12-21").await.status, 201);
    assert_eq!(z.patch(&one(3), json!({"note": "x"})).await.status, 404);
    assert_eq!(z.delete(&one(3), json!({})).await.status, 404);
    assert_eq!(z.get(&cpath(c)).await.status, 404);
    assert_eq!(z.post(&cpath(c), json!({"id": uuid(9)})).await.status, 404);
    assert_eq!(
        z.patch("/api/v1/completions/not-a-uuid", json!({"note": "x"}))
            .await
            .status,
        404
    );
}

#[tokio::test]
async fn history_of_removed_member_still_renders() {
    let (app, mut a, h, c) = setup().await;
    let (mut b, bid) = signup(&app, "ben").await;
    add_to_household(&mut a, h, "ben").await;
    assert_eq!(done(&mut b, c, 1, "2025-12-29").await.status, 201);
    let r = a
        .delete(&format!("/api/v1/households/{h}/members/{bid}"), json!({}))
        .await;
    assert!(r.status == 204 || r.status == 200, "{}", r.status);
    let hist = a.get(&cpath(c)).await;
    assert_eq!(hist.body["items"][0]["by"]["id"], bid);
    assert_eq!(hist.body["items"][0]["by"]["display_name"], "Name ben");
    assert_eq!(hist.body["items"][0]["by"]["former"], true);
    let list = a.get(&format!("/api/v1/households/{h}/chores")).await;
    assert_eq!(list.body[0]["last"]["by"]["former"], true);
    assert_eq!(b.get(&cpath(c)).await.status, 404);
}

#[tokio::test]
async fn completion_on_archived_chore_allowed() {
    let (_app, mut a, _, c) = setup().await;
    a.post(&format!("/api/v1/chores/{c}/archive"), json!({}))
        .await;
    assert_eq!(done(&mut a, c, 1, "2025-12-30").await.status, 201);
}

#[tokio::test]
async fn history_cursor_validated_and_normalized() {
    let (_app, mut a, h, c) = setup().await;
    for (n, d) in [(1, "2025-12-01"), (2, "2025-12-05"), (3, "2025-12-09")] {
        assert_eq!(done(&mut a, c, n, d).await.status, 201);
    }
    let unknown = a
        .get(&format!("{}?before=2025-12-05,{}", cpath(c), uuid(99)))
        .await;
    assert_eq!(unknown.status, 400);
    assert_eq!(unknown.body["error"], "bad_cursor");
    let other = new_chore(&mut a, h, json!({"name": "Dishes"})).await;
    assert_eq!(done(&mut a, other, 50, "2025-12-07").await.status, 201);
    let foreign = a
        .get(&format!("{}?before=2025-12-07,{}", cpath(c), uuid(50)))
        .await;
    assert_eq!(foreign.status, 400);
    assert_eq!(foreign.body["error"], "bad_cursor");
    let r = a
        .get(&format!("{}?before=2025-12-5,{}", cpath(c), uuid(2)))
        .await;
    assert_eq!(r.status, 200, "{:?}", r.body);
    let ids: Vec<&str> = r.body["items"]
        .as_array()
        .unwrap()
        .iter()
        .map(|i| i["id"].as_str().unwrap())
        .collect();
    assert_eq!(ids, [uuid(1)]);
}
