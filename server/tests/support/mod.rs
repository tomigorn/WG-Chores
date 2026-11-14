#![allow(dead_code)]
use axum::{
    Router,
    body::Body,
    extract::ConnectInfo,
    http::{Method, Request, header},
};
use chrono::{TimeZone, Utc};
use serde_json::Value;
use std::collections::HashMap;
use std::net::SocketAddr;
use std::sync::Arc;
use tower::ServiceExt;
use wg_chores::app::{AppState, router};
use wg_chores::auth::local::Hasher;
use wg_chores::clock::FixedClock;
use wg_chores::config::Config;
use wg_chores::db::Db;

pub const ORIGIN: &str = "https://chores.example.com";

pub struct TestApp {
    pub router: Router,
    pub state: AppState,
    pub clock: Arc<FixedClock>,
}

impl TestApp {
    pub fn new() -> TestApp {
        Self::with_env(&[])
    }

    pub fn with_env(pairs: &[(&str, &str)]) -> TestApp {
        let mut env: HashMap<String, String> = HashMap::from([
            ("PUBLIC_URL".into(), ORIGIN.into()),
            ("OPEN_REGISTRATION".into(), "true".into()),
        ]);
        for (k, v) in pairs {
            env.insert(k.to_string(), v.to_string());
        }
        let cfg = Config::from_env(|k| env.get(k).cloned()).unwrap();
        let clock = Arc::new(FixedClock::new(
            Utc.with_ymd_and_hms(2026, 1, 1, 12, 0, 0).unwrap(),
        ));
        let mut state = AppState::new(cfg, Db::open_memory().unwrap());
        state.clock = clock.clone();
        state.hasher = Hasher::insecure_for_tests();
        TestApp {
            router: router(state.clone()),
            state,
            clock,
        }
    }

    pub fn client(&self) -> Client {
        Client {
            router: self.router.clone(),
            jar: HashMap::new(),
            peer: "192.0.2.10:5000".parse().unwrap(),
            xff: None,
            csrf: true,
            origin: Some(ORIGIN.into()),
        }
    }

    pub async fn db<T: Send + 'static>(
        &self,
        f: impl FnOnce(&mut rusqlite::Connection) -> rusqlite::Result<T> + Send + 'static,
    ) -> T {
        self.state.db.run(f).await.unwrap()
    }

    pub async fn seed_local_user(&self, username: &str, password: &str) -> i64 {
        let hash = self.state.hasher.hash(password.to_string()).await.unwrap();
        let u = username.to_string();
        self.db(move |c| {
            c.execute(
                "INSERT INTO users (username, display_name, created_at) VALUES (?1, ?1, 'x')",
                [&u],
            )?;
            let id = c.last_insert_rowid();
            c.execute(
                "INSERT INTO identities (user_id, kind, issuer, subject, password_hash) VALUES (?1, 'local', '', ?2, ?3)",
                rusqlite::params![id, u, hash],
            )?;
            Ok(id)
        })
        .await
    }

    pub async fn seed_sso_user(&self, name: &str) -> i64 {
        let n = name.to_string();
        self.db(move |c| {
            c.execute(
                "INSERT INTO users (display_name, created_at) VALUES (?1, 'x')",
                [&n],
            )?;
            let id = c.last_insert_rowid();
            c.execute(
                "INSERT INTO identities (user_id, kind, issuer, subject) VALUES (?1, 'oidc', 'https://sso.example.com', ?2)",
                rusqlite::params![id, format!("sub-{id}")],
            )?;
            Ok(id)
        })
        .await
    }
}

pub struct Client {
    router: Router,
    pub jar: HashMap<String, String>,
    pub peer: SocketAddr,
    pub xff: Option<String>,
    pub csrf: bool,
    pub origin: Option<String>,
}

pub struct Resp {
    pub status: u16,
    pub headers: axum::http::HeaderMap,
    pub body: Value,
}

impl Resp {
    pub fn set_cookies(&self) -> Vec<String> {
        self.headers
            .get_all(header::SET_COOKIE)
            .iter()
            .map(|v| v.to_str().unwrap().to_string())
            .collect()
    }
}

impl Client {
    pub async fn send(&mut self, method: Method, path: &str, body: Option<Value>) -> Resp {
        let mut req = Request::builder().method(method.clone()).uri(path);
        if !self.jar.is_empty() {
            let c: Vec<String> = self.jar.iter().map(|(k, v)| format!("{k}={v}")).collect();
            req = req.header(header::COOKIE, c.join("; "));
        }
        if method != Method::GET {
            if self.csrf {
                req = req.header("x-wgc", "1");
            }
            if let Some(o) = &self.origin {
                req = req.header(header::ORIGIN, o);
            }
        }
        if let Some(x) = &self.xff {
            req = req.header("x-forwarded-for", x);
        }
        let body = match body {
            Some(b) => {
                req = req.header(header::CONTENT_TYPE, "application/json");
                Body::from(b.to_string())
            }
            None => Body::empty(),
        };
        let mut req = req.body(body).unwrap();
        req.extensions_mut().insert(ConnectInfo(self.peer));
        let res = self.router.clone().oneshot(req).await.unwrap();
        let (parts, body) = res.into_parts();
        let bytes = axum::body::to_bytes(body, 1 << 20).await.unwrap();
        let resp = Resp {
            status: parts.status.as_u16(),
            headers: parts.headers,
            body: serde_json::from_slice(&bytes).unwrap_or(Value::Null),
        };
        for sc in resp.set_cookies() {
            let first = sc.split(';').next().unwrap();
            let (k, v) = first.split_once('=').unwrap();
            if v.is_empty() || sc.contains("Max-Age=0") {
                self.jar.remove(k);
            } else {
                self.jar.insert(k.into(), v.into());
            }
        }
        resp
    }

    pub async fn get(&mut self, path: &str) -> Resp {
        self.send(Method::GET, path, None).await
    }

    pub async fn post(&mut self, path: &str, body: Value) -> Resp {
        self.send(Method::POST, path, Some(body)).await
    }

    pub async fn patch(&mut self, path: &str, body: Value) -> Resp {
        self.send(Method::PATCH, path, Some(body)).await
    }

    pub async fn delete(&mut self, path: &str, body: Value) -> Resp {
        self.send(Method::DELETE, path, Some(body)).await
    }

    pub async fn login(&mut self, user: &str, pw: &str) -> Resp {
        self.post(
            "/api/v1/auth/login",
            serde_json::json!({"username": user, "password": pw}),
        )
        .await
    }
}
