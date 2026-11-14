use crate::secret::write_once; // leak-check: ok
use base64::{Engine, engine::general_purpose::URL_SAFE_NO_PAD};
use chrono::{DateTime, Duration, Utc};
use ring::{
    aead, agreement, hmac,
    rand::{SecureRandom, SystemRandom},
    signature::{self, EcdsaKeyPair, KeyPair},
};
use std::io::ErrorKind;
use std::net::IpAddr;
use std::path::Path;
use url::{Host, Url};

const RECORD_SIZE: u32 = 4096;
/// Push services of the major browsers; a host must equal one or end with "." + one.
pub const PUSH_HOSTS: &[&str] = &[
    "fcm.googleapis.com",
    "updates.push.services.mozilla.com",
    "push.services.mozilla.com",
    "push.apple.com",
    "notify.windows.com",
];

pub struct Vapid {
    key: EcdsaKeyPair,
}

impl Vapid {
    pub fn generate() -> Vapid {
        let rng = SystemRandom::new();
        let doc = EcdsaKeyPair::generate_pkcs8(&signature::ECDSA_P256_SHA256_FIXED_SIGNING, &rng)
            .expect("p256 key generation");
        Vapid::from_pkcs8(doc.as_ref()).expect("own pkcs8")
    }

    fn from_pkcs8(der: &[u8]) -> Option<Vapid> {
        let key = EcdsaKeyPair::from_pkcs8(
            &signature::ECDSA_P256_SHA256_FIXED_SIGNING,
            der,
            &SystemRandom::new(),
        )
        .ok()?;
        Some(Vapid { key })
    }

    /// Loads the P-256 key from a PKCS#8 DER file, creating it (mode 0600) on first start.
    pub fn load_or_create(path: &Path) -> std::io::Result<Vapid> {
        match std::fs::read(path) {
            Ok(der) => return parse(path, &der),
            Err(e) if e.kind() != ErrorKind::NotFound => return Err(e),
            Err(_) => {}
        }
        let rng = SystemRandom::new();
        let doc = EcdsaKeyPair::generate_pkcs8(&signature::ECDSA_P256_SHA256_FIXED_SIGNING, &rng)
            .map_err(|_| std::io::Error::other("cannot generate a P-256 key"))?;
        match write_once(path, doc.as_ref()) {
            Ok(()) => parse(path, doc.as_ref()),
            Err(e) if e.kind() == ErrorKind::AlreadyExists => parse(path, &std::fs::read(path)?),
            Err(e) => Err(e),
        }
    }

    /// Uncompressed public point, base64url: the browser's `applicationServerKey`.
    pub fn public_b64(&self) -> String {
        URL_SAFE_NO_PAD.encode(self.key.public_key().as_ref())
    }

    /// RFC 8292 `Authorization` header for one push request.
    pub fn authorization(&self, endpoint: &Url, sub: &str, now: DateTime<Utc>) -> String {
        let header = URL_SAFE_NO_PAD.encode(br#"{"typ":"JWT","alg":"ES256"}"#);
        let claims = serde_json::json!({
            "aud": endpoint.origin().ascii_serialization(),
            "exp": (now + Duration::hours(12)).timestamp(),
            "sub": sub,
        });
        let input = format!("{header}.{}", URL_SAFE_NO_PAD.encode(claims.to_string()));
        let sig = self
            .key
            .sign(&SystemRandom::new(), input.as_bytes())
            .expect("ecdsa signing");
        format!(
            "vapid t={input}.{}, k={}",
            URL_SAFE_NO_PAD.encode(sig.as_ref()),
            self.public_b64()
        )
    }
}

fn parse(path: &Path, der: &[u8]) -> std::io::Result<Vapid> {
    Vapid::from_pkcs8(der).ok_or_else(|| {
        std::io::Error::other(format!(
            "{} is not a P-256 private key (PKCS#8 DER); delete it to create a new one (every browser then has to enable notifications again)",
            path.display()
        ))
    })
}

/// Decodes base64url with or without padding.
pub fn b64_decode(s: &str) -> Option<Vec<u8>> {
    URL_SAFE_NO_PAD.decode(s.trim_end_matches('=')).ok()
}

/// A browser's `p256dh` key: an uncompressed point that is really on the curve.
pub fn valid_public_key(bytes: &[u8]) -> bool {
    if bytes.len() != 65 || bytes[0] != 4 {
        return false;
    }
    let rng = SystemRandom::new();
    let Ok(eph) = agreement::EphemeralPrivateKey::generate(&agreement::ECDH_P256, &rng) else {
        return false;
    };
    let peer = agreement::UnparsedPublicKey::new(&agreement::ECDH_P256, bytes);
    agreement::agree_ephemeral(eph, &peer, |_| ()).is_ok()
}

fn host_allowed(host: &str, extra: &[String]) -> bool {
    PUSH_HOSTS
        .iter()
        .copied()
        .chain(extra.iter().map(String::as_str))
        .any(|s| {
            host == s
                || host
                    .strip_suffix(s)
                    .is_some_and(|p| p.len() > 1 && p.ends_with('.'))
        })
}

/// Push endpoints must be https on port 443 at a known push service (plus `extra` suffixes); plain http to loopback only when allowed (tests).
pub fn check_endpoint(raw: &str, extra: &[String], allow_loopback_http: bool) -> Option<Url> {
    if raw.len() > 2048 {
        return None;
    }
    let u = Url::parse(raw).ok()?;
    let host = u.host()?;
    let loopback = match &host {
        Host::Domain(_) => false,
        Host::Ipv4(ip) => IpAddr::V4(*ip).is_loopback(),
        Host::Ipv6(ip) => IpAddr::V6(*ip).is_loopback(),
    };
    if loopback {
        return (allow_loopback_http && matches!(u.scheme(), "http" | "https")).then_some(u);
    }
    let ok = u.scheme() == "https"
        && u.port().is_none()
        && matches!(&host, Host::Domain(d) if host_allowed(&d.to_ascii_lowercase(), extra));
    ok.then_some(u)
}

fn hmac256(key: &[u8], parts: &[&[u8]]) -> [u8; 32] {
    let k = hmac::Key::new(hmac::HMAC_SHA256, key);
    let mut ctx = hmac::Context::with_key(&k);
    for p in parts {
        ctx.update(p);
    }
    ctx.sign().as_ref().try_into().expect("32-byte tag")
}

fn derive(
    ecdh: &[u8],
    as_public: &[u8],
    ua_public: &[u8],
    auth: &[u8],
    salt: &[u8],
) -> ([u8; 16], [u8; 12]) {
    let prk_key = hmac256(auth, &[ecdh]);
    let ikm = hmac256(&prk_key, &[b"WebPush: info\0", ua_public, as_public, &[1]]);
    let prk = hmac256(salt, &[&ikm]);
    let cek = hmac256(&prk, &[b"Content-Encoding: aes128gcm\0\x01"]);
    let nonce = hmac256(&prk, &[b"Content-Encoding: nonce\0\x01"]);
    (
        cek[..16].try_into().expect("16"),
        nonce[..12].try_into().expect("12"),
    )
}

fn seal(
    ecdh: &[u8],
    as_public: &[u8],
    ua_public: &[u8],
    auth: &[u8],
    salt: &[u8; 16],
    plaintext: &[u8],
) -> Vec<u8> {
    let (cek, nonce) = derive(ecdh, as_public, ua_public, auth, salt);
    let key =
        aead::LessSafeKey::new(aead::UnboundKey::new(&aead::AES_128_GCM, &cek).expect("aes key"));
    let mut buf = plaintext.to_vec();
    buf.push(2);
    key.seal_in_place_append_tag(
        aead::Nonce::assume_unique_for_key(nonce),
        aead::Aad::empty(),
        &mut buf,
    )
    .expect("aes-gcm");
    let mut out = Vec::with_capacity(86 + buf.len());
    out.extend_from_slice(salt);
    out.extend_from_slice(&RECORD_SIZE.to_be_bytes());
    out.push(as_public.len() as u8);
    out.extend_from_slice(as_public);
    out.extend_from_slice(&buf);
    out
}

/// RFC 8291 aes128gcm body for one subscription, with a fresh sender key and salt.
pub fn encrypt(ua_public: &[u8], auth: &[u8], plaintext: &[u8]) -> Option<Vec<u8>> {
    let rng = SystemRandom::new();
    let eph = agreement::EphemeralPrivateKey::generate(&agreement::ECDH_P256, &rng).ok()?;
    let as_public = eph.compute_public_key().ok()?;
    let mut salt = [0u8; 16];
    rng.fill(&mut salt).ok()?;
    let peer = agreement::UnparsedPublicKey::new(&agreement::ECDH_P256, ua_public);
    let ecdh = agreement::agree_ephemeral(eph, &peer, |s| s.to_vec()).ok()?;
    Some(seal(
        &ecdh,
        as_public.as_ref(),
        ua_public,
        auth,
        &salt,
        plaintext,
    ))
}

/// Receiver side of RFC 8291, for tests: `ecdh` is ECDH(ua_private, sender key in the header).
#[cfg(any(test, feature = "test-util"))]
pub fn decrypt(ecdh: &[u8], ua_public: &[u8], auth: &[u8], body: &[u8]) -> Option<Vec<u8>> {
    let id_len = *body.get(20)? as usize;
    let (salt, as_public) = (&body[..16], body.get(21..21 + id_len)?);
    let (cek, nonce) = derive(ecdh, as_public, ua_public, auth, salt);
    let key = aead::LessSafeKey::new(aead::UnboundKey::new(&aead::AES_128_GCM, &cek).ok()?);
    let mut buf = body[21 + id_len..].to_vec();
    let plain = key
        .open_in_place(
            aead::Nonce::assume_unique_for_key(nonce),
            aead::Aad::empty(),
            &mut buf,
        )
        .ok()?;
    let end = plain.iter().rposition(|b| *b != 0)?;
    (plain[end] == 2).then(|| plain[..end].to_vec())
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::os::unix::fs::PermissionsExt;

    fn b(s: &str) -> Vec<u8> {
        b64_decode(&s.replace([' ', '\n'], "")).unwrap()
    }

    const AS_PUBLIC: &str =
        "BP4z9KsN6nGRTbVYI_c7VJSPQTBtkgcy27mlmlMoZIIgDll6e3vCYLocInmYWAmS6TlzAC8wEqKK6PBru3jl7A8";
    const UA_PUBLIC: &str =
        "BCVxsr7N_eNgVRqvHtD0zTZsEc6-VV-JvLexhqUzORcxaOzi6-AYWXvTBHm4bjyPjs7Vd8pZGH6SRpkNtoIAiw4";
    const ECDH: &str = "kyrL1jIIOHEzg3sM2ZWRHDRB62YACZhhSlknJ672kSs";
    const AUTH: &str = "BTBZMqHH6r4Tts7J_aSIgg";
    const SALT: &str = "DGv6ra1nlYgDCS1FRnbzlw";
    const BODY: &str = "DGv6ra1nlYgDCS1FRnbzlwAAEABBBP4z9KsN6nGRTbVYI_c7VJSPQTBtkgcy27ml
        mlMoZIIgDll6e3vCYLocInmYWAmS6TlzAC8wEqKK6PBru3jl7A_yl95bQpu6cVPT
        pK4Mqgkf1CXztLVBSt2Ks3oZwbuwXPXLWyouBWLVWGNWQexSgSxsj_Qulcy4a-fN";

    #[test]
    fn rfc8291_vector() {
        let ua_private =
            p256::SecretKey::from_slice(&b("q1dXpw3UpT5VOmu_cf_v6ih07Aems3njxI-JWgLcM94")).unwrap(); // leak-check: ok
        let as_public = p256::PublicKey::from_sec1_bytes(&b(AS_PUBLIC)).unwrap();
        let shared =
            p256::ecdh::diffie_hellman(ua_private.to_nonzero_scalar(), as_public.as_affine());
        assert_eq!(shared.raw_secret_bytes()[..], b(ECDH)[..]);
        let (cek, nonce) = derive(&b(ECDH), &b(AS_PUBLIC), &b(UA_PUBLIC), &b(AUTH), &b(SALT));
        assert_eq!(cek[..], b("oIhVW04MRdy2XN9CiKLxTg")[..]);
        assert_eq!(nonce[..], b("4h_95klXJ5E_qnoN")[..]);
        let salt: [u8; 16] = b(SALT).try_into().unwrap();
        let plain = b"When I grow up, I want to be a watermelon";
        let body = seal(
            &b(ECDH),
            &b(AS_PUBLIC),
            &b(UA_PUBLIC),
            &b(AUTH),
            &salt,
            plain,
        );
        assert_eq!(body, b(BODY));
        assert_eq!(
            decrypt(&b(ECDH), &b(UA_PUBLIC), &b(AUTH), &body).unwrap(),
            plain
        );
        let mut tampered = body.clone();
        *tampered.last_mut().unwrap() ^= 1;
        assert!(decrypt(&b(ECDH), &b(UA_PUBLIC), &b(AUTH), &tampered).is_none());
    }

    #[test]
    fn encrypt_uses_fresh_key_and_salt() {
        let ua = b(UA_PUBLIC);
        let x = encrypt(&ua, &b(AUTH), b"hi").unwrap();
        let y = encrypt(&ua, &b(AUTH), b"hi").unwrap();
        assert_ne!(x[..86], y[..86]);
        assert_eq!(x.len(), 86 + 3 + 16);
        assert!(encrypt(&[4u8; 65], &b(AUTH), b"hi").is_none());
    }

    #[test]
    fn public_keys_validated() {
        assert!(valid_public_key(&b(UA_PUBLIC)));
        assert!(!valid_public_key(&[4u8; 65]));
        assert!(!valid_public_key(&b(UA_PUBLIC)[1..]));
    }

    #[test]
    fn endpoints_checked() {
        let none: &[String] = &[];
        let ok = |u: &str| check_endpoint(u, none, false).is_some();
        assert!(ok("https://fcm.googleapis.com/fcm/send/abc"));
        assert!(ok("https://FCM.googleapis.com/fcm/send/abc"));
        assert!(ok("https://updates.push.services.mozilla.com/wpush/v2/x"));
        assert!(ok("https://web.push.apple.com/x"));
        assert!(ok("https://wns2-par02p.notify.windows.com/w/?token=x"));
        for bad in [
            "http://fcm.googleapis.com/x",
            "https://fcm.googleapis.com:8443/x",
            "https://xfcm.googleapis.com/x",
            "https://fcm.googleapis.com.evil.example/x",
            "https://fcm.googleapis.com./x",
            "https://authentik-server/x",
            "https://localhost/x",
            "https://localhost./x",
            "https://foo.localhost/x",
            "https://192.168.1.2/x",
            "https://[::1]/x",
        ] {
            assert!(!ok(bad), "{bad}");
        }
        assert!(check_endpoint("http://127.0.0.1:9/x", none, false).is_none());
        assert!(check_endpoint("http://127.0.0.1:9/x", none, true).is_some());
        assert!(check_endpoint("ftp://127.0.0.1/x", none, true).is_none());
        assert!(check_endpoint("https://internal.example.com/x", none, true).is_none());
        let extra = ["push.example.org".to_string()];
        assert!(check_endpoint("https://a.push.example.org/x", &extra, false).is_some());
        assert!(check_endpoint("https://evilpush.example.org/x", &extra, false).is_none());
        let long = format!("https://fcm.googleapis.com/{}", "a".repeat(2030));
        assert!(!ok(&long));
    }

    #[test]
    fn vapid_key_created_once_with_mode_0600() {
        let d = std::env::temp_dir().join(format!("wgc-vapid-{}", std::process::id()));
        let _ = std::fs::remove_dir_all(&d);
        std::fs::create_dir_all(&d).unwrap();
        let p = d.join("vapid.key");
        let a = Vapid::load_or_create(&p).unwrap();
        assert_eq!(
            std::fs::metadata(&p).unwrap().permissions().mode() & 0o777,
            0o600
        );
        let der = std::fs::read(&p).unwrap();
        assert_eq!(der[0], 0x30);
        assert!(Vapid::from_pkcs8(&der).is_some());
        assert_eq!(b(&a.public_b64()).len(), 65);
        assert_eq!(
            Vapid::load_or_create(&p).unwrap().public_b64(),
            a.public_b64()
        );
        let names: Vec<_> = std::fs::read_dir(&d)
            .unwrap()
            .map(|e| e.unwrap().file_name())
            .collect();
        assert_eq!(names, vec![std::ffi::OsString::from("vapid.key")]);
        std::fs::write(&p, "junk").unwrap();
        let msg = Vapid::load_or_create(&p).err().unwrap().to_string();
        assert!(msg.contains("delete it"), "{msg}");
        std::fs::remove_dir_all(&d).ok();
    }

    #[test]
    fn vapid_jwt_signed_for_origin() {
        let v = Vapid::generate();
        let now: DateTime<Utc> = "2026-01-01T12:00:00Z".parse().unwrap();
        let ep = Url::parse("https://push.example.com:8443/send/abc").unwrap();
        let h = v.authorization(&ep, "https://chores.example.com/", now);
        let (t, k) = h
            .strip_prefix("vapid t=")
            .unwrap()
            .split_once(", k=")
            .unwrap();
        assert_eq!(k, v.public_b64());
        let (input, sig) = t.rsplit_once('.').unwrap();
        let pk = signature::UnparsedPublicKey::new(&signature::ECDSA_P256_SHA256_FIXED, b(k));
        pk.verify(input.as_bytes(), &b(sig)).unwrap();
        let claims: serde_json::Value =
            serde_json::from_slice(&b(input.split_once('.').unwrap().1)).unwrap();
        assert_eq!(claims["aud"], "https://push.example.com:8443");
        assert_eq!(claims["sub"], "https://chores.example.com/");
        assert_eq!(claims["exp"], now.timestamp() + 12 * 3600);
    }
}
