use crate::config::Config;
use axum::{Router, routing::get};
use std::sync::Arc;

#[derive(Clone)]
pub struct AppState {
    pub cfg: Arc<Config>,
}

pub fn router(state: AppState) -> Router {
    Router::new().route("/healthz", get(|| async { "ok" })).with_state(state)
}
