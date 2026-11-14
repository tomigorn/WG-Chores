use chrono::NaiveTime;
use chrono_tz::Tz;
use std::net::SocketAddr;
use std::path::PathBuf;
use url::Url;

#[derive(Debug, thiserror::Error)]
pub enum ConfigError {
    #[error("{0} is required")]
    Missing(&'static str),
    #[error("{0} is invalid: {1}")]
    Invalid(&'static str, String),
}

#[derive(Clone, PartialEq)]
pub struct Sec(pub String);

impl std::fmt::Debug for Sec {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        f.write_str("Sec(***)")
    }
}

#[derive(Debug, Clone)]
pub struct OidcConfig {
    pub issuer: Url,
    pub client_id: String,
    pub client_secret: Sec,
    pub name: String,
    pub required_group: Option<String>,
    pub account_url: Option<Url>,
}

#[derive(Debug, Clone)]
pub struct Config {
    pub public_url: Url,
    pub tz: Tz,
    pub local_accounts: bool,
    pub open_registration: bool,
    pub oidc: Option<OidcConfig>,
    pub notify_times: Vec<NaiveTime>,
    pub member_directory: bool,
    pub data_dir: PathBuf,
    pub listen: SocketAddr,
}

fn parse_url(key: &'static str, v: &str) -> Result<Url, ConfigError> {
    Url::parse(v).map_err(|e| ConfigError::Invalid(key, e.to_string()))
}

fn parse_http_url(key: &'static str, v: &str) -> Result<Url, ConfigError> {
    let u = parse_url(key, v)?;
    match u.scheme() {
        "http" | "https" => Ok(u),
        _ => Err(ConfigError::Invalid(key, "must be http or https".into())),
    }
}

fn parse_bool(key: &'static str, v: Option<String>, default: bool) -> Result<bool, ConfigError> {
    match v.as_deref().map(str::trim) {
        None | Some("") => Ok(default),
        Some("true" | "1") => Ok(true),
        Some("false" | "0") => Ok(false),
        Some(o) => Err(ConfigError::Invalid(
            key,
            format!("expected true or false, got {o}"),
        )),
    }
}

impl Config {
    pub fn from_env(get: impl Fn(&str) -> Option<String>) -> Result<Config, ConfigError> {
        let get = |k: &str| get(k).filter(|v| !v.trim().is_empty());

        let public_url = parse_url(
            "PUBLIC_URL",
            &get("PUBLIC_URL").ok_or(ConfigError::Missing("PUBLIC_URL"))?,
        )?;
        let local_dev = matches!(public_url.host_str(), Some("localhost" | "127.0.0.1"));
        if public_url.scheme() != "https" && !(public_url.scheme() == "http" && local_dev) {
            return Err(ConfigError::Invalid("PUBLIC_URL", "must be https".into()));
        }

        let tz = match get("TZ") {
            None => chrono_tz::UTC,
            Some(v) => v
                .trim()
                .parse::<Tz>()
                .map_err(|e| ConfigError::Invalid("TZ", e.to_string()))?,
        };

        let notify_times = get("NOTIFY_TIMES")
            .unwrap_or_else(|| "14:00,19:00".into())
            .split(',')
            .map(|s| {
                NaiveTime::parse_from_str(s.trim(), "%H:%M")
                    .map_err(|e| ConfigError::Invalid("NOTIFY_TIMES", format!("{s}: {e}")))
            })
            .collect::<Result<Vec<_>, _>>()?;

        let secret = match get("OIDC_CLIENT_SECRET_FILE") {
            Some(f) => Some(
                std::fs::read_to_string(&f)
                    .map_err(|e| {
                        ConfigError::Invalid("OIDC_CLIENT_SECRET_FILE", format!("{f}: {e}"))
                    })?
                    .trim()
                    .to_string(),
            ),
            None => get("OIDC_CLIENT_SECRET"),
        };
        let (issuer, client_id) = (get("OIDC_ISSUER"), get("OIDC_CLIENT_ID"));
        let oidc = if issuer.is_none() && client_id.is_none() && secret.is_none() {
            None
        } else {
            let issuer = issuer.ok_or(ConfigError::Missing("OIDC_ISSUER"))?;
            let value = secret
                .filter(|v| !v.trim().is_empty())
                .ok_or(ConfigError::Missing("OIDC_CLIENT_SECRET"))?;
            let sv = Sec(value);
            Some(OidcConfig {
                issuer: parse_url("OIDC_ISSUER", &issuer)?,
                client_id: client_id.ok_or(ConfigError::Missing("OIDC_CLIENT_ID"))?,
                client_secret: sv,
                name: get("OIDC_NAME").unwrap_or_else(|| "SSO".into()),
                required_group: get("OIDC_REQUIRED_GROUP"),
                account_url: get("OIDC_ACCOUNT_URL")
                    .map(|u| parse_http_url("OIDC_ACCOUNT_URL", &u))
                    .transpose()?,
            })
        };

        Ok(Config {
            public_url,
            tz,
            local_accounts: parse_bool("LOCAL_ACCOUNTS", get("LOCAL_ACCOUNTS"), true)?,
            open_registration: parse_bool("OPEN_REGISTRATION", get("OPEN_REGISTRATION"), false)?,
            oidc,
            notify_times,
            member_directory: parse_bool("MEMBER_DIRECTORY", get("MEMBER_DIRECTORY"), false)?,
            data_dir: get("DATA_DIR")
                .map(PathBuf::from)
                .unwrap_or_else(|| "/data".into()),
            listen: get("LISTEN")
                .unwrap_or_else(|| "0.0.0.0:8080".into())
                .trim()
                .parse()
                .map_err(|e: std::net::AddrParseError| {
                    ConfigError::Invalid("LISTEN", e.to_string())
                })?,
        })
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::collections::HashMap;

    fn env(pairs: &[(&str, &str)]) -> impl Fn(&str) -> Option<String> {
        let m: HashMap<String, String> = pairs
            .iter()
            .map(|(k, v)| (k.to_string(), v.to_string()))
            .collect();
        move |k| m.get(k).cloned()
    }

    const URL: (&str, &str) = ("PUBLIC_URL", "https://chores.example.com");

    #[test]
    fn defaults_apply() {
        let c = Config::from_env(env(&[URL])).unwrap();
        assert_eq!(c.tz, chrono_tz::UTC);
        assert!(c.local_accounts);
        assert!(!c.open_registration);
        assert!(c.oidc.is_none());
        assert_eq!(
            c.notify_times,
            vec![
                NaiveTime::from_hms_opt(14, 0, 0).unwrap(),
                NaiveTime::from_hms_opt(19, 0, 0).unwrap()
            ]
        );
        assert!(!c.member_directory);
        assert_eq!(c.data_dir, PathBuf::from("/data"));
        assert_eq!(c.listen, "0.0.0.0:8080".parse().unwrap());
    }

    #[test]
    fn listen_parsed_and_validated() {
        let c = Config::from_env(env(&[URL, ("LISTEN", "127.0.0.1:9000")])).unwrap();
        assert_eq!(c.listen, "127.0.0.1:9000".parse().unwrap());
        assert!(Config::from_env(env(&[URL, ("LISTEN", "nope")])).is_err());
    }

    #[test]
    fn invalid_tz_rejected() {
        assert!(Config::from_env(env(&[URL, ("TZ", "Mars/Olympus")])).is_err());
        assert_eq!(
            Config::from_env(env(&[URL, ("TZ", "Europe/Zurich")]))
                .unwrap()
                .tz,
            chrono_tz::Europe::Zurich
        );
    }

    #[test]
    fn invalid_bool_rejected() {
        assert!(Config::from_env(env(&[URL, ("LOCAL_ACCOUNTS", "maybe")])).is_err());
        assert!(Config::from_env(env(&[URL, ("OPEN_REGISTRATION", "yes")])).is_err());
        assert!(
            Config::from_env(env(&[URL, ("MEMBER_DIRECTORY", "1")]))
                .unwrap()
                .member_directory
        );
    }

    #[test]
    fn missing_secret_file_rejected() {
        let r = Config::from_env(env(&[
            URL,
            ("OIDC_ISSUER", "https://sso.example.com"),
            ("OIDC_CLIENT_ID", "id"),
            ("OIDC_CLIENT_SECRET_FILE", "/nonexistent/wgc-secret"),
        ]));
        assert!(r.is_err());
    }

    #[test]
    fn empty_secret_rejected() {
        let base = [
            URL,
            ("OIDC_ISSUER", "https://sso.example.com"),
            ("OIDC_CLIENT_ID", "id"),
        ];
        let path = std::env::temp_dir().join(format!("wgc-empty-{}", std::process::id()));
        std::fs::write(&path, "  \n").unwrap();
        let mut with_file = base.to_vec();
        with_file.push(("OIDC_CLIENT_SECRET_FILE", path.to_str().unwrap()));
        let r = Config::from_env(env(&with_file));
        std::fs::remove_file(&path).ok();
        assert!(r.is_err());
        let mut blank = base.to_vec();
        blank.push(("OIDC_CLIENT_SECRET", " "));
        assert!(Config::from_env(env(&blank)).is_err());
    }

    #[test]
    fn account_url_must_be_http() {
        let base = [
            URL,
            ("OIDC_ISSUER", "https://sso.example.com"),
            ("OIDC_CLIENT_ID", "id"),
            ("OIDC_CLIENT_SECRET", "s"),
        ];
        let with = |u: &'static str| {
            let mut v = base.to_vec();
            v.push(("OIDC_ACCOUNT_URL", u));
            Config::from_env(env(&v))
        };
        assert!(with("javascript:alert(1)").is_err());
        assert!(with("https://sso.example.com/account").is_ok());
    }

    #[test]
    fn public_url_required_and_https() {
        assert!(Config::from_env(env(&[])).is_err());
        assert!(Config::from_env(env(&[("PUBLIC_URL", "http://chores.example.com")])).is_err());
        assert!(Config::from_env(env(&[("PUBLIC_URL", "http://localhost:8080")])).is_ok());
    }

    #[test]
    fn oidc_needs_issuer_id_secret() {
        let full = [
            URL,
            ("OIDC_ISSUER", "https://sso.example.com"),
            ("OIDC_CLIENT_ID", "id"),
            ("OIDC_CLIENT_SECRET", "s"),
        ];
        let c = Config::from_env(env(&full)).unwrap();
        let o = c.oidc.unwrap();
        assert_eq!(o.name, "SSO");
        assert_eq!(o.client_id, "id");
        assert!(o.required_group.is_none());
        assert!(Config::from_env(env(&full[..3])).is_err());
        assert!(Config::from_env(env(&[URL, ("OIDC_CLIENT_ID", "id")])).is_err());
    }

    #[test]
    fn secret_file_wins() {
        let dir = std::env::temp_dir().join(format!("wgc-secret-{}", std::process::id()));
        std::fs::write(&dir, "from-file\n").unwrap();
        let path = dir.to_str().unwrap().to_string();
        let c = Config::from_env(env(&[
            URL,
            ("OIDC_ISSUER", "https://sso.example.com"),
            ("OIDC_CLIENT_ID", "id"),
            ("OIDC_CLIENT_SECRET", "plain"),
            ("OIDC_CLIENT_SECRET_FILE", &path),
        ]))
        .unwrap();
        std::fs::remove_file(&dir).ok();
        assert_eq!(c.oidc.unwrap().client_secret.0, "from-file");
    }

    #[test]
    fn bad_notify_times_rejected() {
        assert!(Config::from_env(env(&[URL, ("NOTIFY_TIMES", "25:00")])).is_err());
        assert!(Config::from_env(env(&[URL, ("NOTIFY_TIMES", "noon")])).is_err());
        let c = Config::from_env(env(&[URL, ("NOTIFY_TIMES", "08:30, 20:15")])).unwrap();
        assert_eq!(c.notify_times.len(), 2);
    }
}
