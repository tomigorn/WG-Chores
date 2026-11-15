mod support;
use chrono::Duration;
use serde_json::{Value, json};
use support::*;

const POLL: &str = "/api/v1/notifications/poll";

struct World {
    app: TestApp,
    anna: Client,
    bert: Client,
    phone: Client,
    hid: i64,
    clean: i64,
    dishes: i64,
}

async fn device(app: &TestApp, user: &str) -> Client {
    let mut c = app.client();
    let r = c
        .post(
            "/api/v1/auth/login",
            json!({"username": user, "password": PW, "client": "android", "device_name": "Phone"}),
        )
        .await;
    assert_eq!(r.status, 200, "{:?}", r.body);
    let mut a = app.client();
    a.csrf = false;
    a.origin = None;
    a.bearer = Some(r.body["token"].as_str().unwrap().into());
    a
}

/// anna + bert share "Flat"; "Clean" (weekly) was last done 12 days ago, so it is due.
async fn world() -> World {
    let app = TestApp::new();
    let (mut anna, _) = signup(&app, "anna").await;
    let (mut bert, _) = signup(&app, "bert").await;
    let hid = new_household(&mut anna, "Flat").await;
    add_to_household(&mut anna, hid, "bert").await;
    let clean = new_chore(&mut anna, hid, json!({"name": "Clean", "interval": "1w"})).await;
    let dishes = new_chore(&mut anna, hid, json!({"name": "Dishes", "interval": "1w"})).await;
    assert_eq!(done(&mut bert, clean, 1, "2025-12-20").await.status, 201);
    let phone = device(&app, "anna").await;
    World {
        app,
        anna,
        bert,
        phone,
        hid,
        clean,
        dishes,
    }
}

async fn poll(c: &mut Client, cursor: Option<&str>) -> Value {
    let path = match cursor {
        Some(cur) => format!("{POLL}?since={cur}"),
        None => POLL.to_string(),
    };
    let r = c.get(&path).await;
    assert_eq!(r.status, 200, "{:?}", r.body);
    r.body
}

/// Completions younger than the 10 s undo window are held back.
fn settle(app: &TestApp) {
    app.clock.advance(Duration::seconds(11));
}

fn cursor(v: &Value) -> String {
    v["cursor"].as_str().unwrap().to_string()
}

#[tokio::test]
async fn first_poll_has_due_but_no_done_backlog() {
    let mut w = world().await;
    let v = poll(&mut w.phone, None).await;
    assert_eq!(v["done"], json!([]));
    assert_eq!(v["more"], false);
    assert_eq!(
        v["due"],
        json!([{"household": {"id": w.hid, "name": "Flat"}, "chores": [{"id": w.clean, "name": "Clean"}]}])
    );
    assert!(!cursor(&v).is_empty());
}

#[tokio::test]
async fn due_once_per_device_and_period() {
    let mut w = world().await;
    let v = poll(&mut w.phone, None).await;
    assert_eq!(v["due"].as_array().unwrap().len(), 1);
    let v = poll(&mut w.phone, Some(&cursor(&v))).await;
    assert_eq!(v["due"], json!([]));
    let mut tablet = device(&w.app, "anna").await;
    let t = poll(&mut tablet, None).await;
    assert_eq!(
        t["due"].as_array().unwrap().len(),
        1,
        "another device gets it too"
    );

    assert_eq!(
        done(&mut w.anna, w.clean, 2, "2026-01-01").await.status,
        201
    );
    let v = poll(&mut w.phone, Some(&cursor(&v))).await;
    assert_eq!(v["due"], json!([]));
    w.app.clock.advance(Duration::days(8));
    let v = poll(&mut w.phone, Some(&cursor(&v))).await;
    assert_eq!(
        v["due"][0]["chores"],
        json!([{"id": w.clean, "name": "Clean"}]),
        "a new due period announces again"
    );
}

#[tokio::test]
async fn done_by_others_since_cursor() {
    let mut w = world().await;
    let c0 = cursor(&poll(&mut w.phone, None).await);
    assert_eq!(
        done(&mut w.bert, w.dishes, 10, "2026-01-01").await.status,
        201
    );
    assert_eq!(
        done(&mut w.anna, w.dishes, 11, "2026-01-01").await.status,
        201
    );
    assert_eq!(
        done(&mut w.bert, w.clean, 12, "2025-12-31").await.status,
        201
    );
    let undone = w
        .bert
        .delete(&format!("/api/v1/completions/{}", uuid(12)), json!({}))
        .await;
    assert_eq!(undone.status, 204, "{:?}", undone.body);
    settle(&w.app);
    let v = poll(&mut w.phone, Some(&c0)).await;
    assert_eq!(
        v["done"],
        json!([{"household": {"id": w.hid, "name": "Flat"}, "chore": {"id": w.dishes, "name": "Dishes"}, "by": "Name bert", "done_on": "2026-01-01"}])
    );
    let v2 = poll(&mut w.phone, Some(&cursor(&v))).await;
    assert_eq!(v2["done"], json!([]));
    let again = poll(&mut w.phone, Some(&c0)).await;
    assert_eq!(
        again["done"].as_array().unwrap().len(),
        1,
        "an old cursor replays"
    );
}

#[tokio::test]
async fn done_limited_to_50_oldest_first() {
    let mut w = world().await;
    let c0 = cursor(&poll(&mut w.phone, None).await);
    for n in 0..55u64 {
        let day = format!("2025-11-{:02}", 1 + n % 28);
        assert_eq!(done(&mut w.bert, w.dishes, 100 + n, &day).await.status, 201);
    }
    settle(&w.app);
    let v = poll(&mut w.phone, Some(&c0)).await;
    let list = v["done"].as_array().unwrap();
    assert_eq!(list.len(), 50);
    assert_eq!(v["more"], true);
    assert_eq!(list[0]["done_on"], "2025-11-01");
    assert_eq!(list[49]["done_on"], "2025-11-22");
    let v = poll(&mut w.phone, Some(&cursor(&v))).await;
    assert_eq!(v["done"].as_array().unwrap().len(), 5);
    assert_eq!(v["more"], false);
}

#[tokio::test]
async fn toggles_respected() {
    let mut w = world().await;
    let c0 = cursor(&poll(&mut w.phone, None).await);
    let r = w
        .anna
        .patch(
            "/api/v1/me",
            json!({"notify_done": false, "notify_due": false}),
        )
        .await;
    assert_eq!(r.status, 200);
    assert_eq!(
        done(&mut w.bert, w.dishes, 10, "2026-01-01").await.status,
        201
    );
    w.app.clock.advance(Duration::days(8));
    let v = poll(&mut w.phone, Some(&c0)).await;
    assert_eq!(
        (v["done"].clone(), v["due"].clone()),
        (json!([]), json!([]))
    );
    assert_ne!(cursor(&v), c0, "the cursor moves on");
}

#[tokio::test]
async fn bearer_only_and_cursor_checked() {
    let mut w = world().await;
    let r = w.anna.get(POLL).await;
    assert_eq!(
        (r.status, r.body["error"].as_str()),
        (403, Some("app_only"))
    );
    assert_eq!(w.app.client().get(POLL).await.status, 401);
    for bad in ["!!", "abc", "LTE"] {
        let r = w.phone.get(&format!("{POLL}?since={bad}")).await;
        assert_eq!(
            (r.status, r.body["error"].as_str()),
            (400, Some("invalid_cursor")),
            "{bad}"
        );
    }
}

#[tokio::test]
async fn due_state_removed_with_the_device() {
    let mut w = world().await;
    poll(&mut w.phone, None).await;
    let n: i64 = w
        .app
        .db(|c| {
            c.query_row("SELECT count(*) FROM device_notification_state", [], |r| {
                r.get(0)
            })
        })
        .await;
    assert_eq!(n, 1);
    let r = w
        .phone
        .send(axum::http::Method::POST, "/api/v1/auth/logout", None)
        .await;
    assert_eq!(r.status, 204);
    let n: i64 = w
        .app
        .db(|c| {
            c.query_row("SELECT count(*) FROM device_notification_state", [], |r| {
                r.get(0)
            })
        })
        .await;
    assert_eq!(n, 0);
}

#[tokio::test]
async fn lost_response_resends_due_once_and_ack_ends_it() {
    let mut w = world().await;
    let lost = poll(&mut w.phone, None).await;
    assert_eq!(lost["due"].as_array().unwrap().len(), 1);
    let again = poll(&mut w.phone, None).await;
    assert_eq!(
        again["due"].as_array().unwrap().len(),
        1,
        "not acked: sent again"
    );
    let acked = poll(&mut w.phone, Some(&cursor(&again))).await;
    assert_eq!(acked["due"], json!([]));
    let stale = poll(&mut w.phone, Some(&cursor(&lost))).await;
    assert_eq!(stale["due"], json!([]), "acked ones never come back");
    let v = poll(&mut w.phone, Some(&cursor(&stale))).await;
    assert_eq!(v["due"], json!([]));
    assert_eq!(
        done(&mut w.anna, w.clean, 2, "2026-01-01").await.status,
        201
    );
    w.app.clock.advance(Duration::days(8));
    let v = poll(&mut w.phone, Some(&cursor(&v))).await;
    assert_eq!(
        v["due"][0]["chores"],
        json!([{"id": w.clean, "name": "Clean"}])
    );
}

#[tokio::test]
async fn young_completions_wait_for_the_undo_window() {
    let mut w = world().await;
    let c0 = cursor(&poll(&mut w.phone, None).await);
    assert_eq!(
        done(&mut w.bert, w.dishes, 10, "2025-12-30").await.status,
        201
    );
    settle(&w.app);
    assert_eq!(
        done(&mut w.bert, w.dishes, 11, "2025-12-31").await.status,
        201
    );
    let v = poll(&mut w.phone, Some(&c0)).await;
    let days: Vec<&str> = v["done"]
        .as_array()
        .unwrap()
        .iter()
        .map(|d| d["done_on"].as_str().unwrap())
        .collect();
    assert_eq!(days, ["2025-12-30"]);
    let v2 = poll(&mut w.phone, Some(&cursor(&v))).await;
    assert_eq!(v2["done"], json!([]), "still young");
    settle(&w.app);
    let v3 = poll(&mut w.phone, Some(&cursor(&v2))).await;
    let days: Vec<&str> = v3["done"]
        .as_array()
        .unwrap()
        .iter()
        .map(|d| d["done_on"].as_str().unwrap())
        .collect();
    assert_eq!(days, ["2025-12-31"]);
}

#[tokio::test]
async fn cursor_survives_hard_deletes_and_vacuum() {
    let mut w = world().await;
    let c0 = cursor(&poll(&mut w.phone, None).await);
    let tmp = new_household(&mut w.bert, "Tmp").await;
    let solo = new_chore(&mut w.bert, tmp, json!({"name": "Solo", "interval": "1w"})).await;
    assert_eq!(done(&mut w.bert, solo, 20, "2026-01-01").await.status, 201);
    settle(&w.app);
    let v = poll(&mut w.phone, Some(&c0)).await;
    assert_eq!(v["done"], json!([]));
    let c1 = cursor(&v);
    let r = w
        .bert
        .post(
            &format!("/api/v1/households/{tmp}/leave"),
            json!({"confirm_delete": true}),
        )
        .await;
    assert_eq!(r.status, 204);
    assert_eq!(
        done(&mut w.bert, w.dishes, 21, "2026-01-01").await.status,
        201
    );
    settle(&w.app);
    let before = poll(&mut w.phone, Some(&c1)).await;
    assert_eq!(before["done"].as_array().unwrap().len(), 1, "{before:?}");
    w.app.db(|c| c.execute_batch("VACUUM")).await;
    let after = poll(&mut w.phone, Some(&c1)).await;
    assert_eq!(after["done"], before["done"]);
    assert_eq!(after["more"], before["more"]);
}
