mod support;
use serde_json::{Value, json};
use support::*;

fn names(v: &Value) -> Vec<String> {
    v.as_array()
        .unwrap()
        .iter()
        .map(|c| c["name"].as_str().unwrap().to_string())
        .collect()
}

#[tokio::test]
async fn list_includes_last_and_due_level() {
    let app = TestApp::new();
    let (mut a, aid) = signup(&app, "anna").await;
    let h = new_household(&mut a, "Home").await;
    let weekly = new_chore(
        &mut a,
        h,
        json!({"name": "Bins", "category": "Kitchen", "interval": "1w"}),
    )
    .await;
    let plain = new_chore(&mut a, h, json!({"name": "Dust"})).await;
    let never = new_chore(&mut a, h, json!({"name": "Windows", "interval": "3m"})).await;
    assert_eq!(done(&mut a, weekly, 1, "2025-12-25").await.status, 201);
    assert_eq!(done(&mut a, plain, 2, "2025-12-31").await.status, 201);
    let list = a.get(&format!("/api/v1/households/{h}/chores")).await;
    assert_eq!(list.status, 200);
    let by_name = |n: &str| {
        list.body
            .as_array()
            .unwrap()
            .iter()
            .find(|c| c["name"] == n)
            .unwrap()
            .clone()
    };
    let b = by_name("Bins");
    assert_eq!(b["id"], weekly);
    assert_eq!(b["category"], "Kitchen");
    assert_eq!(b["interval"], "1w");
    assert_eq!(b["archived"], false);
    assert_eq!(b["days_since"], 7);
    assert_eq!(b["due_level"], "due");
    assert_eq!(b["days_overdue"], 0);
    assert_eq!(b["usual_interval"], Value::Null);
    assert_eq!(b["last"]["done_on"], "2025-12-25");
    assert_eq!(b["last"]["by"]["id"], aid);
    assert_eq!(b["last"]["by"]["display_name"], "Name anna");
    assert_eq!(b["last"]["by"]["former"], false);
    let d = by_name("Dust");
    assert_eq!(d["category"], Value::Null);
    assert_eq!(d["interval"], Value::Null);
    assert_eq!(d["days_since"], 1);
    assert_eq!(d["due_level"], "fresh");
    let w = by_name("Windows");
    assert_eq!(w["id"], never);
    assert_eq!(w["last"], Value::Null);
    assert_eq!(w["days_since"], Value::Null);
    assert_eq!(w["due_level"], "never");
    app.clock.advance(chrono::Duration::days(10));
    let list = a.get(&format!("/api/v1/households/{h}/chores")).await;
    let b = list
        .body
        .as_array()
        .unwrap()
        .iter()
        .find(|c| c["name"] == "Bins")
        .unwrap()
        .clone();
    assert_eq!(b["days_since"], 17);
    assert_eq!(b["days_overdue"], 10);
    assert_eq!(b["due_level"], "long_overdue");
}

#[tokio::test]
async fn usual_interval_hidden_below_3() {
    let app = TestApp::new();
    let (mut a, _) = signup(&app, "anna").await;
    let h = new_household(&mut a, "Home").await;
    let c = new_chore(&mut a, h, json!({"name": "Bins"})).await;
    let get = |body: Value| body[0]["usual_interval"].clone();
    let path = format!("/api/v1/households/{h}/chores");
    done(&mut a, c, 1, "2025-12-01").await;
    done(&mut a, c, 2, "2025-12-08").await;
    assert_eq!(get(a.get(&path).await.body), Value::Null);
    done(&mut a, c, 3, "2025-12-22").await;
    assert_eq!(get(a.get(&path).await.body), json!(11));
}

#[tokio::test]
async fn category_created_by_typing_and_disappears_when_empty() {
    let app = TestApp::new();
    let (mut a, _) = signup(&app, "anna").await;
    let h = new_household(&mut a, "Home").await;
    let cats = format!("/api/v1/households/{h}/categories");
    assert_eq!(a.get(&cats).await.body, json!([]));
    let c1 = new_chore(&mut a, h, json!({"name": "Bins", "category": "  Küche "})).await;
    let c2 = new_chore(&mut a, h, json!({"name": "Oven", "category": "KÜCHE"})).await;
    let r = a.get(&cats).await;
    assert_eq!(r.body, json!([{"name": "Küche", "chores": 2}]));
    let chore = a
        .get(&format!("/api/v1/households/{h}/chores"))
        .await
        .body
        .as_array()
        .unwrap()
        .iter()
        .find(|c| c["id"] == c2)
        .unwrap()
        .clone();
    assert_eq!(chore["category"], "Küche");
    let p = a
        .patch(&format!("/api/v1/chores/{c1}"), json!({"category": "Bath"}))
        .await;
    assert_eq!(p.status, 200);
    assert_eq!(p.body["category"], "Bath");
    assert_eq!(
        a.get(&cats).await.body,
        json!([{"name": "Bath", "chores": 1}, {"name": "Küche", "chores": 1}])
    );
    let p = a
        .patch(&format!("/api/v1/chores/{c2}"), json!({"category": null}))
        .await;
    assert_eq!(p.body["category"], Value::Null);
    assert_eq!(
        a.get(&cats).await.body,
        json!([{"name": "Bath", "chores": 1}])
    );
    let bad = a
        .post(
            &format!("/api/v1/households/{h}/chores"),
            json!({"name": "X", "category": "y".repeat(41)}),
        )
        .await;
    assert_eq!(bad.status, 400);
}

#[tokio::test]
async fn rename_category_moves_chores() {
    let app = TestApp::new();
    let (mut a, _) = signup(&app, "anna").await;
    let h = new_household(&mut a, "Home").await;
    let cats = format!("/api/v1/households/{h}/categories");
    new_chore(&mut a, h, json!({"name": "Bins", "category": "Kitchen"})).await;
    new_chore(&mut a, h, json!({"name": "Oven", "category": "Kitchen"})).await;
    new_chore(&mut a, h, json!({"name": "Tub", "category": "Bath"})).await;
    let r = a
        .patch(&cats, json!({"from": "kitchen", "to": "Cooking"}))
        .await;
    assert_eq!(r.status, 200);
    let list = a.get(&format!("/api/v1/households/{h}/chores")).await;
    let cat = |n: &str| {
        list.body
            .as_array()
            .unwrap()
            .iter()
            .find(|c| c["name"] == n)
            .unwrap()["category"]
            .clone()
    };
    assert_eq!(cat("Bins"), "Cooking");
    assert_eq!(cat("Oven"), "Cooking");
    assert_eq!(cat("Tub"), "Bath");
    let r = a.patch(&cats, json!({"from": "Nope", "to": "X"})).await;
    assert_eq!(r.status, 404);
    let r = a.patch(&cats, json!({"from": "Cooking", "to": " "})).await;
    assert_eq!(r.status, 400);
    let r = a
        .patch(&cats, json!({"from": "Cooking", "to": "bath"}))
        .await;
    assert_eq!(r.status, 200);
    assert_eq!(
        a.get(&cats).await.body,
        json!([{"name": "Bath", "chores": 3}])
    );
    let mut b_app = signup(&app, "ben").await.0;
    assert_eq!(
        b_app
            .patch(&cats, json!({"from": "Bath", "to": "X"}))
            .await
            .status,
        404
    );
}

#[tokio::test]
async fn archive_hides_and_restore_returns_with_history() {
    let app = TestApp::new();
    let (mut a, _) = signup(&app, "anna").await;
    let h = new_household(&mut a, "Home").await;
    let list = format!("/api/v1/households/{h}/chores");
    let c = new_chore(&mut a, h, json!({"name": "Bins", "category": "Kitchen"})).await;
    new_chore(&mut a, h, json!({"name": "Dust"})).await;
    done(&mut a, c, 1, "2025-12-30").await;
    let r = a
        .post(&format!("/api/v1/chores/{c}/archive"), json!({}))
        .await;
    assert_eq!(r.status, 200);
    assert_eq!(r.body["archived"], true);
    assert_eq!(names(&a.get(&list).await.body), ["Dust"]);
    let arch = a.get(&format!("{list}?archived=true")).await;
    assert_eq!(names(&arch.body), ["Bins"]);
    assert_eq!(arch.body[0]["last"]["done_on"], "2025-12-30");
    assert_eq!(
        a.get(&format!("/api/v1/households/{h}/categories"))
            .await
            .body,
        json!([{"name": "Kitchen", "chores": 0}])
    );
    let r = a
        .post(&format!("/api/v1/chores/{c}/restore"), json!({}))
        .await;
    assert_eq!(r.status, 200);
    assert_eq!(r.body["archived"], false);
    let l = a.get(&list).await;
    assert_eq!(names(&l.body), ["Bins", "Dust"]);
    let bins = l
        .body
        .as_array()
        .unwrap()
        .iter()
        .find(|c| c["name"] == "Bins")
        .unwrap();
    assert_eq!(bins["last"]["done_on"], "2025-12-30");
}

#[tokio::test]
async fn chore_validation_and_edit() {
    let app = TestApp::new();
    let (mut a, _) = signup(&app, "anna").await;
    let h = new_household(&mut a, "Home").await;
    let path = format!("/api/v1/households/{h}/chores");
    for body in [
        json!({"name": "  "}),
        json!({"name": "x".repeat(81)}),
        json!({"name": "Ok", "interval": "1d"}),
        json!({"name": "Ok", "extra": 1}),
    ] {
        assert_eq!(a.post(&path, body).await.status, 400);
    }
    let c = new_chore(&mut a, h, json!({"name": "  Bins  ", "interval": "1w"})).await;
    new_chore(&mut a, h, json!({"name": "Bins"})).await;
    let p = a
        .patch(
            &format!("/api/v1/chores/{c}"),
            json!({"name": "Trash", "interval": null}),
        )
        .await;
    assert_eq!(p.status, 200);
    assert_eq!(p.body["name"], "Trash");
    assert_eq!(p.body["interval"], Value::Null);
    let p = a
        .patch(&format!("/api/v1/chores/{c}"), json!({"interval": "2w"}))
        .await;
    assert_eq!(p.body["name"], "Trash");
    assert_eq!(p.body["interval"], "2w");
    assert_eq!(
        a.patch(&format!("/api/v1/chores/{c}"), json!({"name": ""}))
            .await
            .status,
        400
    );
    let mut b = signup(&app, "ben").await.0;
    assert_eq!(b.get(&path).await.status, 404);
    assert_eq!(b.post(&path, json!({"name": "X"})).await.status, 404);
    assert_eq!(
        b.patch(&format!("/api/v1/chores/{c}"), json!({"name": "X"}))
            .await
            .status,
        404
    );
    assert_eq!(
        b.post(&format!("/api/v1/chores/{c}/archive"), json!({}))
            .await
            .status,
        404
    );
    assert_eq!(
        b.post(&format!("/api/v1/chores/{c}/restore"), json!({}))
            .await
            .status,
        404
    );
    assert_eq!(
        b.get(&format!("/api/v1/households/{h}/categories"))
            .await
            .status,
        404
    );
    assert_eq!(
        a.patch("/api/v1/chores/9999", json!({"name": "X"}))
            .await
            .status,
        404
    );
}

#[tokio::test]
async fn membership_db_error_is_500_not_404() {
    let app = TestApp::new();
    let (mut a, _) = signup(&app, "anna").await;
    let h = new_household(&mut a, "Home").await;
    let c = new_chore(&mut a, h, json!({"name": "Bins"})).await;
    app.db(|c| c.execute_batch("ALTER TABLE memberships RENAME TO memberships_gone"))
        .await;
    let r = a
        .patch(&format!("/api/v1/chores/{c}"), json!({"name": "X"}))
        .await;
    assert_eq!(r.status, 500);
}
