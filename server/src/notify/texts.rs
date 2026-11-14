const MAX_NAMES: usize = 10;

fn german(lang: &str) -> bool {
    lang == "de"
}

/// Title and body of "someone else did a chore".
pub fn done(_lang: &str, who: &str, chore: &str) -> (String, String) {
    (who.to_string(), format!("✓ {chore}"))
}

/// Title and body for the due chores of one household, `names` already sorted.
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
            let mut body = names[..names.len().min(MAX_NAMES)].join(", ");
            if names.len() > MAX_NAMES {
                body.push_str(", …");
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
    fn long_lists_cut() {
        let names: Vec<String> = (0..12).map(|i| format!("C{i}")).collect();
        let (t, b) = due("en", "Home", &names);
        assert_eq!(t, "12 chores are due");
        assert!(b.ends_with("C9, …"), "{b}");
    }
}
