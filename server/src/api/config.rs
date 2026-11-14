use crate::app::AppState;
use axum::{Json, extract::State};
use serde_json::{Value, json};

pub async fn get(State(st): State<AppState>) -> Json<Value> {
    let c = &st.cfg;
    Json(json!({
        "name": "WG Chores",
        "local_accounts": c.local_accounts,
        "open_registration": c.open_registration,
        "oidc": c.oidc.as_ref().map(|o| json!({"name": o.name})),
        "notify_times": c.notify_times.iter().map(|t| t.format("%H:%M").to_string()).collect::<Vec<_>>(),
        "tz": c.tz.name(),
        "version": env!("CARGO_PKG_VERSION"),
    }))
}
