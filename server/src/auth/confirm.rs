//! One-tap confirmation before an SSO sign-in hands a code to the Android app, so an app that starts the flow on its own cannot get a code silently.

use super::session::{hash_token, parse_ts, ts};
use crate::app::AppState;
use axum::{
    body::Bytes,
    extract::State,
    http::{HeaderMap, HeaderValue, StatusCode, header},
    response::{IntoResponse, Response},
};
use base64::{Engine, engine::general_purpose::URL_SAFE_NO_PAD};
use chrono::Duration;
use hmac::{Hmac, KeyInit, Mac};
use rusqlite::OptionalExtension;
use sha2::Sha256;

pub const COOKIE: &str = "__Host-wgc_app_confirm";
const TTL: Duration = Duration::minutes(10);
const MAC_CONTEXT: &[u8] = b"wgc-app-confirm-v1.";

#[derive(Clone, Copy)]
enum Lang {
    En,
    De,
}

/// The first of `en`/`de` in Accept-Language; English otherwise.
fn lang(headers: &HeaderMap) -> Lang {
    let raw = headers
        .get(header::ACCEPT_LANGUAGE)
        .and_then(|v| v.to_str().ok())
        .unwrap_or("");
    for tag in raw.split(',') {
        let t = tag
            .split(';')
            .next()
            .unwrap_or("")
            .trim()
            .to_ascii_lowercase();
        if t == "de" || t.starts_with("de-") {
            return Lang::De;
        }
        if t == "en" || t.starts_with("en-") {
            return Lang::En;
        }
    }
    Lang::En
}

struct Texts {
    ask_title: &'static str,
    ask_body: &'static str,
    confirm: &'static str,
    cancel: &'static str,
    cancelled_title: &'static str,
    cancelled_body: &'static str,
    invalid_title: &'static str,
    invalid_body: &'static str,
}

const EN: Texts = Texts {
    ask_title: "Sign in to the WG Chores app?",
    ask_body: "Signed in as {name}. Continue only if you just started the sign-in in the WG Chores app on this device.",
    confirm: "Sign in to the app",
    cancel: "Cancel",
    cancelled_title: "Sign-in cancelled",
    cancelled_body: "You can close this tab.",
    invalid_title: "This sign-in is no longer valid",
    invalid_body: "Start the sign-in again in the WG Chores app.",
};

const DE: Texts = Texts {
    ask_title: "In der WG Chores App anmelden?",
    ask_body: "Angemeldet als {name}. Fahre nur fort, wenn du die Anmeldung gerade in der WG Chores App auf diesem Gerät gestartet hast.",
    confirm: "In der App anmelden",
    cancel: "Abbrechen",
    cancelled_title: "Anmeldung abgebrochen",
    cancelled_body: "Du kannst diesen Tab schließen.",
    invalid_title: "Diese Anmeldung ist nicht mehr gültig",
    invalid_body: "Starte die Anmeldung in der WG Chores App noch einmal.",
};

fn texts(l: Lang) -> &'static Texts {
    match l {
        Lang::En => &EN,
        Lang::De => &DE,
    }
}

fn escape(s: &str) -> String {
    let mut out = String::with_capacity(s.len());
    for c in s.chars() {
        match c {
            '&' => out.push_str("&amp;"),
            '<' => out.push_str("&lt;"),
            '>' => out.push_str("&gt;"),
            '"' => out.push_str("&quot;"),
            '\'' => out.push_str("&#39;"),
            c => out.push(c),
        }
    }
    out
}

fn html(l: Lang, title: &str, main: &str) -> String {
    let paths = crate::web::embedded_paths();
    let css = paths
        .iter()
        .find(|p| p.starts_with("assets/") && p.ends_with(".css"))
        .map(|p| format!("<link rel=\"stylesheet\" href=\"/{p}\">\n"))
        .unwrap_or_default();
    let theme = if paths.contains(&"theme-init.js") {
        "<script src=\"/theme-init.js\"></script>\n"
    } else {
        ""
    };
    let code = match l {
        Lang::En => "en",
        Lang::De => "de",
    };
    format!(
        "<!doctype html>\n<html lang=\"{code}\">\n<head>\n<meta charset=\"utf-8\">\n<meta name=\"viewport\" content=\"width=device-width, initial-scale=1, viewport-fit=cover\">\n<meta name=\"color-scheme\" content=\"light dark\">\n<title>{t}</title>\n<link rel=\"icon\" type=\"image/png\" sizes=\"192x192\" href=\"/icons/icon-192.png\">\n{css}{theme}</head>\n<body>\n<main class=\"screen legal\">\n<h1>{t}</h1>\n{main}</main>\n</body>\n</html>\n",
        t = escape(title),
    )
}

fn respond(status: StatusCode, body: String, cookie: Option<String>) -> Response {
    let mut res = (status, body).into_response();
    let h = res.headers_mut();
    h.insert(
        header::CONTENT_TYPE,
        HeaderValue::from_static("text/html; charset=utf-8"),
    );
    h.insert(header::CACHE_CONTROL, HeaderValue::from_static("no-store"));
    let csp = crate::headers::CSP.replace("form-action 'self'", "form-action 'self' wgchores:");
    if let Ok(v) = HeaderValue::from_str(&csp) {
        h.insert(header::CONTENT_SECURITY_POLICY, v);
    }
    if let Some(c) = cookie.and_then(|c| HeaderValue::from_str(&c).ok()) {
        h.append(header::SET_COOKIE, c);
    }
    res
}

fn cookie(value: &str, max_age: i64) -> String {
    format!("{COOKIE}={value}; HttpOnly; Secure; SameSite=Lax; Path=/; Max-Age={max_age}")
}

fn mac(key: &[u8; 32], id: &str) -> Hmac<Sha256> {
    let mut m = <Hmac<Sha256> as KeyInit>::new_from_slice(key).expect("hmac key");
    m.update(MAC_CONTEXT);
    m.update(id.as_bytes());
    m
}

/// Stores the pending sign-in and shows the question; `cleared` is the expired OIDC state cookie.
pub async fn ask(
    st: &AppState,
    headers: &HeaderMap,
    user_id: i64,
    challenge: String,
    invite: Option<String>,
    cleared: String,
) -> crate::db::Result<Response> {
    let mut bytes = [0u8; 32];
    getrandom::fill(&mut bytes).expect("os randomness");
    let id = URL_SAFE_NO_PAD.encode(bytes);
    let hash = hash_token(&id);
    let now = st.clock.now();
    let (now_s, exp_s) = (ts(now), ts(now + TTL));
    let name: String = st
        .db
        .run(move |c| {
            c.execute("DELETE FROM app_confirms WHERE expires_at < ?1", [&now_s])?;
            c.execute(
                "INSERT INTO app_confirms (id_hash, user_id, challenge, invite, expires_at) VALUES (?1, ?2, ?3, ?4, ?5)",
                rusqlite::params![hash, user_id, challenge, invite, exp_s],
            )?;
            c.query_row("SELECT display_name FROM users WHERE id = ?1", [user_id], |r| r.get(0))
        })
        .await?;
    let proof = URL_SAFE_NO_PAD.encode(mac(&st.secret, &id).finalize().into_bytes());
    let l = lang(headers);
    let t = texts(l);
    let main = format!(
        "<p>{body}</p>\n<form method=\"post\" action=\"/auth/app/confirm\">\n<input type=\"hidden\" name=\"token\" value=\"{proof}\">\n<p><button class=\"btn btn-primary btn-block\" type=\"submit\" name=\"action\" value=\"confirm\">{yes}</button></p>\n<p><button class=\"btn btn-link btn-block\" type=\"submit\" name=\"action\" value=\"cancel\">{no}</button></p>\n</form>\n",
        body = escape(t.ask_body).replace("{name}", &format!("<strong>{}</strong>", escape(&name))),
        yes = escape(t.confirm),
        no = escape(t.cancel),
    );
    let mut res = respond(
        StatusCode::OK,
        html(l, t.ask_title, &main),
        Some(cookie(&id, TTL.num_seconds())),
    );
    if let Ok(v) = HeaderValue::from_str(&cleared) {
        res.headers_mut().append(header::SET_COOKIE, v);
    }
    Ok(res)
}

/// A refused request leaves the cookie, so a stray post cannot break the real sign-in.
fn message(l: Lang, status: StatusCode, title: &str, body: &str) -> Response {
    let main = format!("<p>{}</p>\n", escape(body));
    let clear = (status != StatusCode::FORBIDDEN).then(|| cookie("", 0));
    respond(status, html(l, title, &main), clear)
}

fn form_field(body: &[u8], name: &str) -> Option<String> {
    url::form_urlencoded::parse(body)
        .find(|(k, _)| k == name)
        .map(|(_, v)| v.into_owned())
}

/// `POST /auth/app/confirm`: same-origin form with the token bound to this browser's pending sign-in; used once.
pub async fn confirm(State(st): State<AppState>, headers: HeaderMap, body: Bytes) -> Response {
    let l = lang(&headers);
    let t = texts(l);
    let own = st.cfg.public_url.origin().ascii_serialization();
    let same_origin = headers
        .get(header::ORIGIN)
        .and_then(|v| v.to_str().ok())
        .is_some_and(|o| o == own);
    let id = super::cookie(&headers, COOKIE);
    let token_ok = match (&id, form_field(&body, "token")) {
        (Some(id), Some(tok)) => URL_SAFE_NO_PAD
            .decode(tok)
            .is_ok_and(|raw| mac(&st.secret, id).verify_slice(&raw).is_ok()),
        _ => false,
    };
    let (Some(id), true, true) = (id, same_origin, token_ok) else {
        return message(l, StatusCode::FORBIDDEN, t.invalid_title, t.invalid_body);
    };
    let hash = hash_token(&id);
    let row = st
        .db
        .run(move |c| {
            let tx = c.transaction()?;
            let row: Option<(i64, String, Option<String>, String)> = tx
                .query_row(
                    "SELECT a.user_id, a.challenge, a.invite, a.expires_at FROM app_confirms a
                     JOIN users u ON u.id = a.user_id AND u.former = 0 WHERE a.id_hash = ?1",
                    [&hash],
                    |r| Ok((r.get(0)?, r.get(1)?, r.get(2)?, r.get(3)?)),
                )
                .optional()?;
            tx.execute("DELETE FROM app_confirms WHERE id_hash = ?1", [&hash])?;
            tx.commit()?;
            Ok(row)
        })
        .await;
    let row = match row {
        Ok(r) => r,
        Err(e) => {
            eprintln!("app sign-in confirmation failed: {e}");
            return message(
                l,
                StatusCode::INTERNAL_SERVER_ERROR,
                t.invalid_title,
                t.invalid_body,
            );
        }
    };
    if form_field(&body, "action").as_deref() == Some("cancel") {
        return message(l, StatusCode::OK, t.cancelled_title, t.cancelled_body);
    }
    let now = st.clock.now();
    let Some((user_id, challenge, invite, _)) =
        row.filter(|(_, _, _, exp)| parse_ts(exp).is_some_and(|e| now < e))
    else {
        return message(l, StatusCode::BAD_REQUEST, t.invalid_title, t.invalid_body);
    };
    let code = match super::app::issue_code(&st, user_id, challenge).await {
        Ok(c) => c,
        Err(e) => {
            eprintln!("app sign-in: storing the code failed: {e}");
            return message(
                l,
                StatusCode::INTERNAL_SERVER_ERROR,
                t.invalid_title,
                t.invalid_body,
            );
        }
    };
    let mut target = format!("{}?code={code}", super::app::REDIRECT);
    if let Some(i) = invite {
        target.push_str(&format!("&invite={i}"));
    }
    let mut res = (StatusCode::SEE_OTHER, [(header::LOCATION, target)]).into_response();
    let h = res.headers_mut();
    h.insert(header::CACHE_CONTROL, HeaderValue::from_static("no-store"));
    if let Ok(v) = HeaderValue::from_str(&cookie("", 0)) {
        h.append(header::SET_COOKIE, v);
    }
    res
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn language_from_accept_language() {
        let l = |v: &str| {
            let mut h = HeaderMap::new();
            h.insert(header::ACCEPT_LANGUAGE, HeaderValue::from_str(v).unwrap());
            matches!(lang(&h), Lang::De)
        };
        assert!(l("de-CH,de;q=0.9,en;q=0.8"));
        assert!(l("fr-CH, de;q=0.8"));
        assert!(!l("en-GB,de;q=0.5"));
        assert!(!l("fr"));
        assert!(!matches!(lang(&HeaderMap::new()), Lang::De));
    }

    #[test]
    fn names_are_escaped() {
        assert_eq!(
            escape("<b>\"Sam\" & 'Jo'</b>"),
            "&lt;b&gt;&quot;Sam&quot; &amp; &#39;Jo&#39;&lt;/b&gt;"
        );
    }
}
