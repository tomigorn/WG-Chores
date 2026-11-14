use crate::auth::AccountKind;
use serde_json::{Value, json};

pub fn user_json(
    id: i64,
    username: Option<&str>,
    display_name: &str,
    kind: AccountKind,
    picture_url: Option<&str>,
    account_url: Option<&str>,
) -> Value {
    json!({
        "id": id,
        "username": username,
        "display_name": display_name,
        "kind": kind,
        "picture_url": picture_url,
        "account_url": account_url,
    })
}
