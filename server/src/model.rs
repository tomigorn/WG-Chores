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

/// Names without control characters and bidi overrides/isolates, trimmed.
pub fn clean_text(raw: &str) -> String {
    raw.chars()
        .filter(|c| {
            !c.is_control() && !matches!(c, '\u{202A}'..='\u{202E}' | '\u{2066}'..='\u{2069}')
        })
        .collect::<String>()
        .trim()
        .to_string()
}

#[cfg(test)]
mod tests {
    use super::clean_text;

    #[test]
    fn control_and_bidi_characters_removed() {
        assert_eq!(
            clean_text("\u{202E}A\u{0}b\u{7f}c\u{2066}\u{2069}\n"),
            "Abc"
        );
        assert_eq!(clean_text("Küche 🧽"), "Küche 🧽");
        assert_eq!(
            clean_text("a\u{202A}\u{202B}\u{202C}\u{202D}\u{2067}\u{2068}b"),
            "ab"
        );
    }
}
