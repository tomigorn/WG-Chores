pub const MAX_BODY_BYTES: usize = 3000;
const MORE: &str = ", …";

/// Length once escaped inside a JSON string.
pub fn json_len(s: &str) -> usize {
    serde_json::to_string(s).map_or(s.len() * 6, |j| j.len() - 2)
}

fn german(lang: &str) -> bool {
    lang == "de"
}

/// Title and body of "someone else did a chore".
pub fn done(_lang: &str, who: &str, chore: &str) -> (String, String) {
    (who.to_string(), format!("✓ {chore}"))
}

/// Title and body for the due chores of one household, `names` already sorted; the JSON-escaped body stays within `MAX_BODY_BYTES`.
pub fn due(lang: &str, household: &str, names: &[String]) -> (String, String) {
    match names {
        [one] if german(lang) => (format!("{one} ist fällig"), household.to_string()),
        [one] => (format!("{one} is due"), household.to_string()),
        _ => {
            let title = if german(lang) {
                format!("{} Aufgaben sind fällig", names.len())
            } else {
                format!("{} chores are due", names.len())
            };
            let mut body = String::new();
            let mut used = 0;
            for (i, n) in names.iter().enumerate() {
                let sep = if i == 0 { "" } else { ", " };
                let reserve = if i + 1 < names.len() { MORE.len() } else { 0 };
                let len = json_len(n);
                if used + sep.len() + len + reserve > MAX_BODY_BYTES {
                    body.push_str(if body.is_empty() { "…" } else { MORE });
                    break;
                }
                body.push_str(sep);
                body.push_str(n);
                used += sep.len() + len;
            }
            (title, body)
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn v(n: &[&str]) -> Vec<String> {
        n.iter().map(|s| s.to_string()).collect()
    }

    #[test]
    fn texts_in_both_languages() {
        assert_eq!(
            done("en", "Jane Doe", "Clean toilet"),
            ("Jane Doe".into(), "✓ Clean toilet".into())
        );
        assert_eq!(
            due("en", "Home", &v(&["Clean toilet"])),
            ("Clean toilet is due".into(), "Home".into())
        );
        assert_eq!(
            due("de", "Home", &v(&["Clean toilet"])).0,
            "Clean toilet ist fällig"
        );
        assert_eq!(
            due("en", "Home", &v(&["A", "B"])),
            ("2 chores are due".into(), "A, B".into())
        );
        assert_eq!(
            due("de", "Home", &v(&["A", "B"])).0,
            "2 Aufgaben sind fällig"
        );
    }

    #[test]
    fn long_lists_cut_by_bytes() {
        let short: Vec<String> = (0..12).map(|i| format!("C{i}")).collect();
        let (t, b) = due("en", "Home", &short);
        assert_eq!(t, "12 chores are due");
        assert!(b.ends_with("C10, C11"), "{b}");
        let long: Vec<String> = (0..60).map(|i| format!("{i}{}", "ü".repeat(78))).collect();
        let (t, b) = due("de", "Home", &long);
        assert_eq!(t, "60 Aufgaben sind fällig");
        assert!(b.len() <= MAX_BODY_BYTES, "{}", b.len());
        assert!(b.len() > MAX_BODY_BYTES - 200, "{}", b.len());
        assert!(b.starts_with(&long[0]));
        assert!(b.ends_with(", …"), "{b}");
        let exact: Vec<String> = (0..2).map(|_| "x".repeat(1499)).collect();
        assert_eq!(due("en", "Home", &exact).1.len(), 3000);
    }

    #[test]
    fn due_list_cut_by_escaped_json_length() {
        let nasty: Vec<String> = (0..40)
            .map(|i| format!("{i}{}", "\"\u{1}\\".repeat(26)))
            .collect();
        let (_, b) = due("en", "Home", &nasty);
        let escaped = serde_json::to_string(&b).unwrap().len() - 2;
        assert!(escaped <= MAX_BODY_BYTES, "{escaped}");
        assert!(b.starts_with(&nasty[0]));
        assert!(b.ends_with(", …"), "{b}");
    }
}
