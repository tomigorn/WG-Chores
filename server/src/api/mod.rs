pub mod config;
pub mod households;
pub mod invites;
pub mod me;
pub mod users;

use crate::app::AppState;
use crate::auth::{csrf, local};
use axum::{
    Router, middleware,
    routing::{delete, get, post, put},
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
        .route("/api/v1/me/last-household", put(me::set_last_household))
        .route(
            "/api/v1/households",
            get(households::list).post(households::create),
        )
        .route(
            "/api/v1/households/{id}",
            get(households::get).patch(households::patch),
        )
        .route("/api/v1/households/{id}/leave", post(households::leave))
        .route(
            "/api/v1/households/{id}/members",
            get(households::members).post(households::add_member),
        )
        .route(
            "/api/v1/households/{id}/members/{user_id}",
            delete(households::remove_member),
        )
        .route(
            "/api/v1/households/{id}/invites",
            get(invites::list).post(invites::create),
        )
        .route(
            "/api/v1/households/{id}/invites/{invite_id}",
            delete(invites::cancel),
        )
        .route("/api/v1/invites/{token}", get(invites::preview))
        .route("/api/v1/invites/{token}/accept", post(invites::accept))
        .route("/api/v1/users", get(users::search))
        .layer(middleware::from_fn_with_state(state, csrf::check))
}
