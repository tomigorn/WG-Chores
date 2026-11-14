use crate::auth::local::Hasher;
use crate::clock::{Clock, SystemClock};
use crate::config::Config;
use crate::db::Db;
use axum::{Router, routing::get};
use std::sync::Arc;

#[derive(Clone)]
pub struct AppState {
    pub cfg: Arc<Config>,
    pub db: Db,
    pub clock: Arc<dyn Clock>,
    pub hasher: Arc<Hasher>,
}

impl AppState {
    pub fn new(cfg: Config, db: Db) -> AppState {
        AppState {
            cfg: Arc::new(cfg),
            db,
            clock: Arc::new(SystemClock),
            hasher: Hasher::new(),
        }
    }
}

pub fn router(state: AppState) -> Router {
    Router::new()
        .route("/healthz", get(|| async { "ok" }))
        .merge(crate::api::router(state.clone()))
        .with_state(state)
}
