use axum::{http::header, response::IntoResponse};

pub const JSON: &str = include_str!("../openapi.json");

pub async fn get() -> impl IntoResponse {
    ([(header::CONTENT_TYPE, "application/json")], JSON)
}
