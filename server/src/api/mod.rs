pub mod chores;
pub mod completions;
pub mod config;
pub mod households;
pub mod invites;
pub mod me;
pub mod push;
pub mod users;

use crate::app::AppState;
use crate::auth::{csrf, local};
use axum::{
    Router, middleware,
    routing::{delete, get, patch, post, put},
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
        .route(
            "/api/v1/households/{id}/chores",
            get(chores::list).post(chores::create),
        )
        .route(
            "/api/v1/households/{id}/categories",
            get(chores::categories).patch(chores::rename_category),
        )
        .route("/api/v1/chores/{id}", patch(chores::patch))
        .route("/api/v1/chores/{id}/archive", post(chores::archive))
        .route("/api/v1/chores/{id}/restore", post(chores::restore))
        .route(
            "/api/v1/chores/{id}/completions",
            get(completions::history).post(completions::create),
        )
        .route(
            "/api/v1/completions/{id}",
            patch(completions::patch).delete(completions::delete),
        )
        .route("/api/v1/users", get(users::search))
        .route("/api/v1/push/key", get(push::key))
        .route(
            "/api/v1/push/subscriptions",
            post(push::subscribe).delete(push::unsubscribe),
        )
        .layer(middleware::from_fn_with_state(state, csrf::check))
}
