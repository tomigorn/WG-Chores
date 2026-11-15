use chrono::NaiveTime;
use chrono_tz::Tz;
use std::net::{IpAddr, SocketAddr};
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

#[derive(Debug, Clone, Copy, PartialEq)]
pub struct IpNet {
    addr: IpAddr,
    prefix: u8,
}

impl IpNet {
    pub fn parse(s: &str) -> Option<IpNet> {
        let (a, p) = match s.split_once('/') {
            Some((a, p)) => (a, Some(p)),
            None => (s, None),
        };
        let addr: IpAddr = a.trim().parse().ok()?;
        let max = if addr.is_ipv4() { 32 } else { 128 };
        let prefix = match p {
            Some(p) => p.trim().parse::<u8>().ok().filter(|p| *p <= max)?,
            None => max,
        };
        match (addr, addr.to_canonical()) {
            (IpAddr::V6(_), v4 @ IpAddr::V4(_)) => Some(IpNet {
                addr: v4,
                prefix: prefix.checked_sub(96)?,
            }),
            _ => Some(IpNet { addr, prefix }),
        }
    }

    pub fn contains(&self, ip: IpAddr) -> bool {
        fn bits(ip: IpAddr) -> (u128, u8) {
            match ip {
                IpAddr::V4(v) => (u32::from(v) as u128, 32),
                IpAddr::V6(v) => (u128::from(v), 128),
            }
        }
        let (a, la) = bits(self.addr);
        let (b, lb) = bits(ip.to_canonical());
        if la != lb {
            return false;
        }
        let shift = u32::from(la - self.prefix);
        shift >= 128 || a >> shift == b >> shift
    }
}

#[derive(Debug, Clone)]
pub struct OidcConfig {
    pub issuer: Url,
    /// The issuer exactly as configured; OIDC compares it as a string.
    pub issuer_id: String,
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
    /// Oldest app version the server still works with, e.g. "1.0.0".
    pub min_app_version: String,
    pub data_dir: PathBuf,
    pub listen: SocketAddr,
    pub trusted_proxies: Vec<IpNet>,
    /// Lets push endpoints use plain http to loopback hosts; never set from the environment.
    pub push_allow_loopback_http: bool,
    /// Extra push service host suffixes beyond the built-in browser services.
    pub push_allowed_hosts: Vec<String>,
}

fn parse_version(v: Option<String>) -> Result<String, ConfigError> {
    let Some(v) = v.map(|v| v.trim().to_string()).filter(|v| !v.is_empty()) else {
        return Ok("1.0.0".into());
    };
    let parts: Vec<&str> = v.split('.').collect();
    let ok = (1..=3).contains(&parts.len())
        && parts
            .iter()
            .all(|p| (1..=6).contains(&p.len()) && p.bytes().all(|b| b.is_ascii_digit()));
    if ok {
        Ok(v)
    } else {
        Err(ConfigError::Invalid(
            "MIN_APP_VERSION",
            format!("{v}: expected a version like 1.2.3"),
        ))
    }
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

/// https, or http on localhost / 127.0.0.1 for development.
fn parse_https_url(key: &'static str, v: &str) -> Result<Url, ConfigError> {
    let u = parse_url(key, v)?;
    let local = matches!(u.host_str(), Some("localhost" | "127.0.0.1"));
    match u.scheme() {
        "https" => Ok(u),
        "http" if local => Ok(u),
        _ => Err(ConfigError::Invalid(key, "must be https".into())),
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
                issuer: parse_https_url("OIDC_ISSUER", &issuer)?,
                issuer_id: issuer.trim().to_string(),
                client_id: client_id.ok_or(ConfigError::Missing("OIDC_CLIENT_ID"))?,
                client_secret: sv,
                name: get("OIDC_NAME").unwrap_or_else(|| "SSO".into()),
                required_group: get("OIDC_REQUIRED_GROUP"),
                account_url: get("OIDC_ACCOUNT_URL")
                    .map(|u| parse_http_url("OIDC_ACCOUNT_URL", &u))
                    .transpose()?,
            })
        };

        let trusted_proxies = match get("TRUSTED_PROXIES") {
            None => Vec::new(),
            Some(v) => v
                .split(',')
                .map(|s| {
                    IpNet::parse(s.trim()).ok_or_else(|| {
                        ConfigError::Invalid("TRUSTED_PROXIES", format!("{s}: not an IP or CIDR"))
                    })
                })
                .collect::<Result<Vec<_>, _>>()?,
        };

        let push_allowed_hosts = match get("PUSH_ALLOWED_HOSTS") {
            None => Vec::new(),
            Some(v) => v
                .split(',')
                .map(|s| {
                    let h = s.trim().trim_start_matches('.').to_ascii_lowercase();
                    let valid = h.contains('.')
                        && !h.contains("..")
                        && !h.ends_with('.')
                        && h.chars()
                            .all(|c| c.is_ascii_alphanumeric() || c == '.' || c == '-')
                        && !h
                            .rsplit('.')
                            .next()
                            .is_some_and(|l| l.chars().all(|c| c.is_ascii_digit()));
                    if valid {
                        Ok(h)
                    } else {
                        Err(ConfigError::Invalid(
                            "PUSH_ALLOWED_HOSTS",
                            format!("{s}: not a host name"),
                        ))
                    }
                })
                .collect::<Result<Vec<_>, _>>()?,
        };

        Ok(Config {
            public_url,
            tz,
            local_accounts: parse_bool("LOCAL_ACCOUNTS", get("LOCAL_ACCOUNTS"), true)?,
            open_registration: parse_bool("OPEN_REGISTRATION", get("OPEN_REGISTRATION"), false)?,
            oidc,
            notify_times,
            member_directory: parse_bool("MEMBER_DIRECTORY", get("MEMBER_DIRECTORY"), false)?,
            min_app_version: parse_version(get("MIN_APP_VERSION"))?,
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
            trusted_proxies,
            push_allow_loopback_http: false,
            push_allowed_hosts,
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
    fn issuer_must_be_https_except_localhost() {
        let with = |iss: &'static str| {
            Config::from_env(env(&[
                URL,
                ("OIDC_ISSUER", iss),
                ("OIDC_CLIENT_ID", "id"),
                ("OIDC_CLIENT_SECRET", "s"),
            ]))
        };
        assert!(with("http://sso.example.com").is_err());
        assert!(with("ftp://sso.example.com").is_err());
        assert!(with("https://sso.example.com").is_ok());
        assert!(with("http://localhost:8081").is_ok());
        assert!(with("http://127.0.0.1:8081").is_ok());
    }

    #[test]
    fn issuer_id_kept_verbatim() {
        let with = |iss: &'static str| {
            Config::from_env(env(&[
                URL,
                ("OIDC_ISSUER", iss),
                ("OIDC_CLIENT_ID", "id"),
                ("OIDC_CLIENT_SECRET", "s"),
            ]))
            .unwrap()
            .oidc
            .unwrap()
            .issuer_id
        };
        assert_eq!(with("https://sso.example.com"), "https://sso.example.com");
        assert_eq!(
            with(" https://sso.example.com/application/o/wg/ "),
            "https://sso.example.com/application/o/wg/"
        );
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
    fn trusted_proxies_parsed() {
        let c = Config::from_env(env(&[URL])).unwrap();
        assert!(c.trusted_proxies.is_empty());
        let c = Config::from_env(env(&[
            URL,
            ("TRUSTED_PROXIES", "10.0.0.1, 172.16.0.0/12, ::1"),
        ]))
        .unwrap();
        let t = &c.trusted_proxies;
        assert!(t.iter().any(|n| n.contains("10.0.0.1".parse().unwrap())));
        assert!(!t.iter().any(|n| n.contains("10.0.0.2".parse().unwrap())));
        assert!(
            t.iter()
                .any(|n| n.contains("172.31.255.1".parse().unwrap()))
        );
        assert!(!t.iter().any(|n| n.contains("172.32.0.1".parse().unwrap())));
        assert!(t.iter().any(|n| n.contains("::1".parse().unwrap())));
        assert!(Config::from_env(env(&[URL, ("TRUSTED_PROXIES", "nope")])).is_err());
        assert!(Config::from_env(env(&[URL, ("TRUSTED_PROXIES", "10.0.0.0/33")])).is_err());
    }

    #[test]
    fn mapped_ipv4_proxy_matches_plain_ipv4() {
        let c = Config::from_env(env(&[
            URL,
            ("TRUSTED_PROXIES", "::ffff:10.0.0.1, ::ffff:172.16.0.0/108"),
        ]))
        .unwrap();
        let t = &c.trusted_proxies;
        assert!(t.iter().any(|n| n.contains("10.0.0.1".parse().unwrap())));
        assert!(t.iter().any(|n| n.contains("172.16.5.5".parse().unwrap())));
        assert!(!t.iter().any(|n| n.contains("172.32.5.5".parse().unwrap())));
    }

    #[test]
    fn push_allowed_hosts_parsed() {
        assert!(
            Config::from_env(env(&[URL]))
                .unwrap()
                .push_allowed_hosts
                .is_empty()
        );
        let c = Config::from_env(env(&[
            URL,
            ("PUSH_ALLOWED_HOSTS", " Push.Example.org, .other.example "),
        ]))
        .unwrap();
        assert_eq!(c.push_allowed_hosts, ["push.example.org", "other.example"]);
        for bad in [
            "a/b",
            "*.example.org",
            "push.example.org:443",
            "x,,y",
            "localhost",
            "10.0.0.1",
        ] {
            assert!(
                Config::from_env(env(&[URL, ("PUSH_ALLOWED_HOSTS", bad)])).is_err(),
                "{bad}"
            );
        }
    }

    #[test]
    fn min_app_version_checked() {
        assert_eq!(
            Config::from_env(env(&[URL])).unwrap().min_app_version,
            "1.0.0"
        );
        let c = Config::from_env(env(&[URL, ("MIN_APP_VERSION", " 1.4 ")])).unwrap();
        assert_eq!(c.min_app_version, "1.4");
        for bad in ["v1.0", "1..2", "1.2.3.4", "x"] {
            assert!(
                Config::from_env(env(&[URL, ("MIN_APP_VERSION", bad)])).is_err(),
                "{bad}"
            );
        }
    }

    #[test]
    fn bad_notify_times_rejected() {
        assert!(Config::from_env(env(&[URL, ("NOTIFY_TIMES", "25:00")])).is_err());
        assert!(Config::from_env(env(&[URL, ("NOTIFY_TIMES", "noon")])).is_err());
        let c = Config::from_env(env(&[URL, ("NOTIFY_TIMES", "08:30, 20:15")])).unwrap();
        assert_eq!(c.notify_times.len(), 2);
    }
}
