use chrono::NaiveDate;
use serde::{Deserialize, Serialize};

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum Interval {
    D3,
    W1,
    W2,
    M1,
    M3,
    M6,
    Y1,
}

impl Interval {
    pub fn days(self) -> i64 {
        match self {
            Interval::D3 => 3,
            Interval::W1 => 7,
            Interval::W2 => 14,
            Interval::M1 => 30,
            Interval::M3 => 91,
            Interval::M6 => 182,
            Interval::Y1 => 365,
        }
    }

    pub fn from_code(code: &str) -> Option<Interval> {
        Some(match code {
            "3d" => Interval::D3,
            "1w" => Interval::W1,
            "2w" => Interval::W2,
            "1m" => Interval::M1,
            "3m" => Interval::M3,
            "6m" => Interval::M6,
            "1y" => Interval::Y1,
            _ => return None,
        })
    }

    pub fn code(self) -> &'static str {
        match self {
            Interval::D3 => "3d",
            Interval::W1 => "1w",
            Interval::W2 => "2w",
            Interval::M1 => "1m",
            Interval::M3 => "3m",
            Interval::M6 => "6m",
            Interval::Y1 => "1y",
        }
    }
}

pub fn interval_days(i: Option<Interval>) -> i64 {
    i.map_or(30, Interval::days)
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "snake_case")]
pub enum DueLevel {
    Fresh,
    Soon,
    Close,
    Due,
    Overdue,
    LongOverdue,
    Never,
}

pub fn due_level(days_since: Option<i64>, interval_days: i64) -> DueLevel {
    let Some(d) = days_since else {
        return DueLevel::Never;
    };
    let iv = if interval_days > 0 { interval_days } else { 30 };
    if d * 2 < iv {
        DueLevel::Fresh
    } else if d * 5 < iv * 4 {
        DueLevel::Soon
    } else if d < iv {
        DueLevel::Close
    } else if d * 2 < iv * 3 {
        DueLevel::Due
    } else if d < iv * 2 {
        DueLevel::Overdue
    } else {
        DueLevel::LongOverdue
    }
}

pub fn days_overdue(days_since: i64, interval_days: i64) -> i64 {
    let iv = if interval_days > 0 { interval_days } else { 30 };
    (days_since - iv).max(0)
}

pub fn usual_interval(done_dates_newest_first: &[NaiveDate]) -> Option<i64> {
    debug_assert!(done_dates_newest_first.windows(2).all(|w| w[0] >= w[1]));
    let dates = &done_dates_newest_first[..done_dates_newest_first.len().min(10)];
    if dates.len() < 3 {
        return None;
    }
    let mut gaps: Vec<i64> = dates.windows(2).map(|w| (w[0] - w[1]).num_days()).collect();
    gaps.sort_unstable();
    let n = gaps.len();
    Some(if n % 2 == 1 {
        gaps[n / 2]
    } else {
        (gaps[n / 2 - 1] + gaps[n / 2] + 1) / 2
    })
}

#[cfg(test)]
mod tests {
    use super::*;

    #[derive(Deserialize)]
    struct DueCase {
        days_since: Option<i64>,
        interval: Option<String>,
        level: DueLevel,
    }

    #[derive(Deserialize)]
    struct UsualCase {
        dates: Vec<NaiveDate>,
        usual: Option<i64>,
    }

    const ALL: [Interval; 7] = [
        Interval::D3,
        Interval::W1,
        Interval::W2,
        Interval::M1,
        Interval::M3,
        Interval::M6,
        Interval::Y1,
    ];

    #[test]
    fn vectors_due_level() {
        let cases: Vec<DueCase> =
            serde_json::from_str(include_str!("../../docs/test-vectors/due-level.json")).unwrap();
        assert!(cases.len() >= 20);
        for c in cases {
            let i = c
                .interval
                .as_deref()
                .map(|s| Interval::from_code(s).unwrap());
            assert_eq!(
                due_level(c.days_since, interval_days(i)),
                c.level,
                "days_since {:?} interval {:?}",
                c.days_since,
                c.interval
            );
        }
    }

    #[test]
    fn vectors_usual_interval() {
        let cases: Vec<UsualCase> =
            serde_json::from_str(include_str!("../../docs/test-vectors/usual-interval.json"))
                .unwrap();
        assert!(cases.len() >= 20);
        for c in cases {
            assert_eq!(usual_interval(&c.dates), c.usual, "dates {:?}", c.dates);
        }
    }

    #[test]
    fn interval_codes_roundtrip() {
        for i in ALL {
            assert_eq!(Interval::from_code(i.code()), Some(i));
        }
        let days: Vec<i64> = ALL.iter().map(|i| i.days()).collect();
        assert_eq!(days, [3, 7, 14, 30, 91, 182, 365]);
        assert_eq!(interval_days(None), 30);
    }

    #[test]
    fn no_daily_interval() {
        assert_eq!(Interval::from_code("1d"), None);
    }

    #[test]
    fn days_overdue_floors_at_zero() {
        assert_eq!(days_overdue(5, 7), 0);
        assert_eq!(days_overdue(10, 7), 3);
    }

    #[test]
    fn negative_days_since_is_fresh() {
        assert_eq!(due_level(Some(-5), 7), DueLevel::Fresh);
        assert_eq!(days_overdue(-5, 7), 0);
    }

    #[test]
    fn non_positive_interval_uses_default() {
        assert_eq!(due_level(Some(30), 0), DueLevel::Due);
        assert_eq!(due_level(Some(10), -7), DueLevel::Fresh);
        assert_eq!(days_overdue(40, 0), 10);
    }

    #[test]
    #[should_panic]
    #[cfg(debug_assertions)]
    fn usual_interval_rejects_oldest_first() {
        let d = |s: &str| s.parse::<NaiveDate>().unwrap();
        usual_interval(&[d("2026-03-01"), d("2026-03-08"), d("2026-03-15")]);
    }

    #[test]
    fn level_serializes_snake_case() {
        assert_eq!(
            serde_json::to_string(&DueLevel::LongOverdue).unwrap(),
            "\"long_overdue\""
        );
    }
}
