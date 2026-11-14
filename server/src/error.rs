use axum::{
    Json,
    http::{HeaderValue, StatusCode, header},
    response::{IntoResponse, Response},
};
use serde_json::json;

#[derive(Debug)]
pub struct ApiError {
    pub status: StatusCode,
    pub code: &'static str,
    pub message: &'static str,
    pub retry_after: Option<u64>,
}

impl ApiError {
    pub fn new(status: StatusCode, code: &'static str, message: &'static str) -> ApiError {
        ApiError {
            status,
            code,
            message,
            retry_after: None,
        }
    }

    pub fn bad_request(code: &'static str, message: &'static str) -> ApiError {
        Self::new(StatusCode::BAD_REQUEST, code, message)
    }

    pub fn forbidden(code: &'static str, message: &'static str) -> ApiError {
        Self::new(StatusCode::FORBIDDEN, code, message)
    }

    pub fn unauthenticated() -> ApiError {
        Self::new(
            StatusCode::UNAUTHORIZED,
            "unauthenticated",
            "You are not signed in.",
        )
    }

    pub fn internal() -> ApiError {
        Self::new(
            StatusCode::INTERNAL_SERVER_ERROR,
            "internal",
            "Something went wrong.",
        )
    }
}

impl From<crate::db::DbError> for ApiError {
    fn from(e: crate::db::DbError) -> Self {
        eprintln!("{e}");
        ApiError::internal()
    }
}

impl IntoResponse for ApiError {
    fn into_response(self) -> Response {
        let mut res = (
            self.status,
            Json(json!({"error": self.code, "message": self.message})),
        )
            .into_response();
        if let Some(s) = self.retry_after
            && let Ok(v) = HeaderValue::from_str(&s.to_string())
        {
            res.headers_mut().insert(header::RETRY_AFTER, v);
        }
        res
    }
}
