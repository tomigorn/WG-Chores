use crate::app::AppState;
use crate::auth::CurrentUser;
use crate::error::ApiError;
use axum::{
    Json,
    extract::{RawQuery, State},
};
use serde_json::{Value, json};

pub async fn search(
    State(st): State<AppState>,
    u: CurrentUser,
    RawQuery(raw): RawQuery,
) -> Result<Json<Value>, ApiError> {
    if !st.cfg.member_directory {
        return Err(ApiError::not_found("not_found", "Not found."));
    }
    let q = url::form_urlencoded::parse(raw.unwrap_or_default().as_bytes())
        .find(|(k, _)| k == "q")
        .map(|(_, v)| v.trim().to_lowercase())
        .unwrap_or_default();
    if q.chars().count() < 2 {
        return Err(ApiError::bad_request(
            "query_too_short",
            "Type at least 2 characters.",
        ));
    }
    let uid = u.id;
    let rows = st
        .db
        .run(move |c| {
            let mut s = c.prepare(
                "SELECT id, display_name, username, picture_url FROM users
                 WHERE former = 0 AND id != ?1
                   AND (instr(lower(display_name), ?2) > 0 OR instr(lower(coalesce(username, '')), ?2) > 0)
                 ORDER BY lower(display_name), id LIMIT 20",
            )?;
            s.query_map(rusqlite::params![uid, q], |r| {
                Ok(json!({
                    "id": r.get::<_, i64>(0)?,
                    "display_name": r.get::<_, String>(1)?,
                    "username": r.get::<_, Option<String>>(2)?,
                    "picture_url": r.get::<_, Option<String>>(3)?,
                }))
            })?
            .collect::<rusqlite::Result<Vec<_>>>()
        })
        .await?;
    Ok(Json(Value::Array(rows)))
}
