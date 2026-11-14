pub mod config;
pub mod me;

use crate::app::AppState;
use crate::auth::{csrf, local};
use axum::{
    Router, middleware,
    routing::{get, post},
};

pub fn router(state: AppState) -> Router<AppState> {
    Router::new()
        .route("/api/v1/config", get(config::get))
        .route("/api/v1/auth/register", post(local::register))
        .route("/api/v1/auth/login", post(local::login))
        .route("/api/v1/auth/logout", post(local::logout))
        .route(
            "/api/v1/me",
            get(me::get).patch(me::patch).delete(me::delete),
        )
        .layer(middleware::from_fn_with_state(state, csrf::check))
}
