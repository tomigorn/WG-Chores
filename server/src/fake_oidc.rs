//! Fake OpenID provider for tests and the browser suite (feature `test-util`).
use axum::{
    Form, Json, Router,
    extract::{Query, State},
    http::{HeaderMap, StatusCode, header},
    response::{IntoResponse, Response},
    routing::{get, post},
};
use base64::{
    Engine,
    engine::general_purpose::{STANDARD, URL_SAFE_NO_PAD},
};
use chrono::{DateTime, Utc};
use rsa::pkcs1v15::SigningKey;
use rsa::signature::{SignatureEncoding, Signer};
use rsa::traits::PublicKeyParts;
use rsa::{PrivateKey, sha2::Sha256 as RsaSha256};
use serde::{Deserialize, Serialize};
use serde_json::{Value, json};
use sha2::{Digest, Sha256};
use std::collections::HashMap;
use std::net::SocketAddr;
use std::sync::{Arc, Mutex, OnceLock};
use tower_service::Service;

pub const CLIENT_ID: &str = "wgc-test-client";
pub const CLIENT_SECRET: &str = "wgc-test-client-pass";

/// Two provider keys: rotate by publishing and signing with B, or sign with a key that is not published.
#[derive(Clone, Copy, Debug, PartialEq)]
pub enum FakeKey {
    A,
    B,
}

impl FakeKey {
    fn kid(self) -> &'static str {
        match self {
            FakeKey::A => "test-key-a",
            FakeKey::B => "test-key-b",
        }
    }

    fn private(self) -> &'static PrivateKey {
        static A: OnceLock<PrivateKey> = OnceLock::new();
        static B: OnceLock<PrivateKey> = OnceLock::new();
        let cell = match self {
            FakeKey::A => &A,
            FakeKey::B => &B,
        };
        cell.get_or_init(|| PrivateKey::new(&mut rand::rngs::SysRng, 2048).expect("rsa key"))
    }
}

#[derive(Clone, Debug, Serialize, Deserialize, PartialEq)]
pub struct FakeUser {
    pub sub: String,
    pub name: Option<String>,
    pub preferred_username: Option<String>,
    pub picture: Option<String>,
    pub email: Option<String>,
    pub groups: Option<Vec<String>>,
}

impl FakeUser {
    pub fn new(sub: &str) -> FakeUser {
        FakeUser {
            sub: sub.into(),
            name: Some("Jane Doe".into()),
            preferred_username: Some("jane".into()),
            picture: Some("https://img.example.com/jane.png".into()),
            email: Some("jane@example.com".into()),
            groups: Some(vec!["WG".into()]),
        }
    }
}

struct Grant {
    redirect_uri: String,
    challenge: String,
    nonce: Option<String>,
    user: FakeUser,
}

struct Inner {
    issuer: String,
    keys: Mutex<(FakeKey, FakeKey)>,
    audience: Mutex<Option<String>>,
    issuer_claim: Mutex<Option<String>>,
    discovery_down: Mutex<bool>,
    user: Mutex<FakeUser>,
    grants: Mutex<HashMap<String, Grant>>,
    now: Mutex<Option<DateTime<Utc>>>,
    nonce: Mutex<Option<String>>,
}

type Shared = Arc<Inner>;

fn random() -> String {
    let mut b = [0u8; 24];
    getrandom::fill(&mut b).expect("os randomness");
    URL_SAFE_NO_PAD.encode(b)
}

fn lock<T>(m: &Mutex<T>) -> std::sync::MutexGuard<'_, T> {
    m.lock().unwrap_or_else(|e| e.into_inner())
}

pub struct FakeOidc {
    inner: Shared,
    router: Router,
    pub addr: SocketAddr,
}

impl FakeOidc {
    /// Starts on a free port of 127.0.0.1.
    pub async fn start() -> FakeOidc {
        FakeOidc::bind("127.0.0.1:0".parse().unwrap())
            .await
            .expect("bind fake oidc")
    }

    pub async fn bind(addr: SocketAddr) -> std::io::Result<FakeOidc> {
        let listener = tokio::net::TcpListener::bind(addr).await?;
        let addr = listener.local_addr()?;
        let inner = Arc::new(Inner {
            issuer: format!("http://{addr}"),
            keys: Mutex::new((FakeKey::A, FakeKey::A)),
            audience: Mutex::new(None),
            issuer_claim: Mutex::new(None),
            discovery_down: Mutex::new(false),
            user: Mutex::new(FakeUser::new("fake-user-1")),
            grants: Mutex::new(HashMap::new()),
            now: Mutex::new(None),
            nonce: Mutex::new(None),
        });
        let router = Router::new()
            .route("/.well-known/openid-configuration", get(discovery))
            .route("/jwks", get(jwks))
            .route("/authorize", get(authorize))
            .route("/token", post(token))
            .route("/_test/user", post(set_user))
            .with_state(inner.clone());
        let serve = router.clone();
        tokio::spawn(async move {
            axum::serve(listener, serve).await.ok();
        });
        Ok(FakeOidc {
            inner,
            router,
            addr,
        })
    }

    pub fn issuer(&self) -> &str {
        &self.inner.issuer
    }

    /// OIDC_ISSUER, OIDC_CLIENT_ID and OIDC_CLIENT_SECRET for the server.
    pub fn env(&self) -> Vec<(&'static str, String)> {
        vec![
            ("OIDC_ISSUER", self.inner.issuer.clone()),
            ("OIDC_CLIENT_ID", CLIENT_ID.into()),
            ("OIDC_CLIENT_SECRET", CLIENT_SECRET.into()),
        ]
    }

    pub fn set_user(&self, u: FakeUser) {
        *lock(&self.inner.user) = u;
    }

    /// Time used for `iat` / `exp`; None = system time.
    pub fn set_now(&self, t: Option<DateTime<Utc>>) {
        *lock(&self.inner.now) = t;
    }

    /// Key published in the JWKS and key used to sign ID tokens.
    pub fn set_keys(&self, published: FakeKey, signing: FakeKey) {
        *lock(&self.inner.keys) = (published, signing);
    }

    /// Forces this `aud` into the next ID tokens.
    pub fn set_audience(&self, aud: Option<String>) {
        *lock(&self.inner.audience) = aud;
    }

    /// Forces this `iss` into the next ID tokens.
    pub fn set_issuer_claim(&self, iss: Option<String>) {
        *lock(&self.inner.issuer_claim) = iss;
    }

    /// Discovery answers 503 while down.
    pub fn set_discovery_down(&self, down: bool) {
        *lock(&self.inner.discovery_down) = down;
    }

    /// Forces this nonce into the next ID tokens instead of the requested one.
    pub fn set_nonce(&self, n: Option<String>) {
        *lock(&self.inner.nonce) = n;
    }

    /// Runs the auto-approving authorize step in-process and returns the redirect target.
    pub async fn authorize(&self, url: &str) -> String {
        let u = url::Url::parse(url).expect("authorize url");
        let path = match u.query() {
            Some(q) => format!("{}?{q}", u.path()),
            None => u.path().to_string(),
        };
        let req = axum::http::Request::get(path)
            .body(axum::body::Body::empty())
            .unwrap();
        let res = self.router.clone().call(req).await.unwrap();
        assert_eq!(res.status(), StatusCode::FOUND, "authorize refused");
        res.headers()[header::LOCATION]
            .to_str()
            .unwrap()
            .to_string()
    }
}

async fn discovery(State(s): State<Shared>) -> Response {
    if *lock(&s.discovery_down) {
        return StatusCode::SERVICE_UNAVAILABLE.into_response();
    }
    let i = &s.issuer;
    Json(json!({
        "issuer": i,
        "authorization_endpoint": format!("{i}/authorize"),
        "token_endpoint": format!("{i}/token"), // leak-check: ok
        "jwks_uri": format!("{i}/jwks"),
        "response_types_supported": ["code"],
        "subject_types_supported": ["public"],
        "id_token_signing_alg_values_supported": ["RS256"],
        "scopes_supported": ["openid", "profile", "email", "groups"],
        "token_endpoint_auth_methods_supported": ["client_secret_basic", "client_secret_post"],
        "code_challenge_methods_supported": ["S256"],
    }))
    .into_response()
}

async fn jwks(State(s): State<Shared>) -> Json<Value> {
    let published = lock(&s.keys).0;
    let pk = published.private().to_public_key();
    Json(json!({"keys": [{
        "kty": "RSA",
        "use": "sig",
        "alg": "RS256",
        "kid": published.kid(),
        "n": URL_SAFE_NO_PAD.encode(pk.n().to_be_bytes_trimmed_vartime()),
        "e": URL_SAFE_NO_PAD.encode(pk.e().to_be_bytes_trimmed_vartime()),
    }]}))
}

fn bad(code: &str) -> Response {
    (StatusCode::BAD_REQUEST, Json(json!({"error": code}))).into_response()
}

async fn authorize(State(s): State<Shared>, Query(q): Query<HashMap<String, String>>) -> Response {
    let get = |k: &str| q.get(k).cloned();
    if get("response_type").as_deref() != Some("code")
        || get("client_id").as_deref() != Some(CLIENT_ID)
        || get("code_challenge_method").as_deref() != Some("S256")
    {
        return bad("invalid_request");
    }
    let (Some(redirect_uri), Some(challenge)) = (get("redirect_uri"), get("code_challenge")) else {
        return bad("invalid_request");
    };
    let Ok(mut target) = url::Url::parse(&redirect_uri) else {
        return bad("invalid_request");
    };
    let code = random();
    let user = lock(&s.user).clone();
    lock(&s.grants).insert(
        code.clone(),
        Grant {
            redirect_uri,
            challenge,
            nonce: get("nonce"),
            user,
        },
    );
    {
        let mut qp = target.query_pairs_mut();
        qp.append_pair("code", &code);
        if let Some(st) = get("state") {
            qp.append_pair("state", &st);
        }
    }
    (StatusCode::FOUND, [(header::LOCATION, target.to_string())]).into_response()
}

fn client_ok(headers: &HeaderMap, form: &HashMap<String, String>) -> bool {
    let basic = headers
        .get(header::AUTHORIZATION)
        .and_then(|v| v.to_str().ok())
        .and_then(|v| v.strip_prefix("Basic "))
        .and_then(|b| STANDARD.decode(b).ok())
        .and_then(|b| String::from_utf8(b).ok());
    match basic {
        Some(b) => b == format!("{CLIENT_ID}:{CLIENT_SECRET}"),
        None => {
            form.get("client_id").map(String::as_str) == Some(CLIENT_ID)
                && form.get("client_secret").map(String::as_str) == Some(CLIENT_SECRET)
        }
    }
}

async fn token(
    State(s): State<Shared>,
    headers: HeaderMap,
    Form(f): Form<HashMap<String, String>>,
) -> Response {
    if !client_ok(&headers, &f) {
        return (
            StatusCode::UNAUTHORIZED,
            Json(json!({"error": "invalid_client"})),
        )
            .into_response();
    }
    if f.get("grant_type").map(String::as_str) != Some("authorization_code") {
        return bad("unsupported_grant_type");
    }
    let Some(g) = f.get("code").and_then(|c| lock(&s.grants).remove(c)) else {
        return bad("invalid_grant");
    };
    let verifier_ok = f
        .get("code_verifier")
        .is_some_and(|v| URL_SAFE_NO_PAD.encode(Sha256::digest(v.as_bytes())) == g.challenge);
    if !verifier_ok || f.get("redirect_uri") != Some(&g.redirect_uri) {
        return bad("invalid_grant");
    }
    let now = lock(&s.now).unwrap_or_else(Utc::now).timestamp();
    let nonce = lock(&s.nonce).clone().or(g.nonce);
    let u = g.user;
    let mut claims = json!({
        "iss": lock(&s.issuer_claim).clone().unwrap_or_else(|| s.issuer.clone()),
        "sub": u.sub,
        "aud": lock(&s.audience).clone().unwrap_or_else(|| CLIENT_ID.into()),
        "iat": now,
        "exp": now + 300,
    });
    let m = claims.as_object_mut().unwrap();
    let opt = [
        ("nonce", nonce),
        ("name", u.name),
        ("preferred_username", u.preferred_username),
        ("picture", u.picture),
        ("email", u.email),
    ];
    for (k, v) in opt {
        if let Some(v) = v {
            m.insert(k.into(), v.into());
        }
    }
    if let Some(g) = u.groups {
        m.insert("groups".into(), g.into());
    }
    let id_token = sign(lock(&s.keys).1, &claims); // leak-check: ok
    Json(json!({
        "access_token": random(), // leak-check: ok
        "token_type": "Bearer", // leak-check: ok
        "expires_in": 300,
        "id_token": id_token, // leak-check: ok
    }))
    .into_response()
}

fn sign(key: FakeKey, claims: &Value) -> String {
    let header = json!({"alg": "RS256", "typ": "JWT", "kid": key.kid()});
    let msg = format!(
        "{}.{}",
        URL_SAFE_NO_PAD.encode(header.to_string()),
        URL_SAFE_NO_PAD.encode(claims.to_string())
    );
    let sig = SigningKey::<RsaSha256>::new(key.private().clone()).sign(msg.as_bytes());
    format!("{msg}.{}", URL_SAFE_NO_PAD.encode(sig.to_bytes()))
}

async fn set_user(State(s): State<Shared>, Json(u): Json<FakeUser>) -> StatusCode {
    *lock(&s.user) = u;
    StatusCode::NO_CONTENT
}
