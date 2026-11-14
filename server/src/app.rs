use crate::auth::local::Hasher;
use crate::auth::oidc::{self, Provider};
use crate::clock::{Clock, SystemClock};
use crate::config::Config;
use crate::db::Db;
use crate::notify::{Push, webpush::Vapid};
use axum::{Router, handler::Handler, middleware, routing::get};
use std::sync::Arc;
use tower_http::compression::{CompressionLayer, DefaultPredicate, predicate::Predicate};

#[derive(Clone)]
pub struct AppState {
    pub cfg: Arc<Config>,
    pub db: Db,
    pub clock: Arc<dyn Clock>,
    pub hasher: Arc<Hasher>,
    pub oidc: Option<Arc<Provider>>,
    pub secret: Arc<[u8; 32]>,
    pub push: Arc<Push>,
}

impl AppState {
    /// Uses a random cookie secret and VAPID key; `main` replaces them with the persistent ones.
    pub fn new(cfg: Config, db: Db) -> AppState {
        let mut secret = [0u8; 32];
        getrandom::fill(&mut secret).expect("os randomness");
        AppState {
            oidc: cfg.oidc.is_some().then(|| Arc::new(Provider::new())),
            secret: Arc::new(secret), // leak-check: ok
            cfg: Arc::new(cfg),
            db,
            clock: Arc::new(SystemClock),
            hasher: Hasher::new(),
            push: Arc::new(Push::new(Vapid::generate())),
        }
    }
}

pub fn router(state: AppState) -> Router {
    Router::new()
        .route("/healthz", get(|| async { "ok" }))
        .route("/auth/oidc/start", get(oidc::start))
        .route("/auth/oidc/callback", get(oidc::callback))
        .route("/api/v1/openapi.json", get(crate::openapi::get))
        .merge(crate::api::router(state.clone()))
        .fallback(
            crate::web::serve.layer(
                CompressionLayer::new()
                    .compress_when(DefaultPredicate::new().and(crate::web::text_only())),
            ),
        )
        .layer(middleware::from_fn(crate::headers::apply))
        .with_state(state)
}
