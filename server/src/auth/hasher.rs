use crate::error::ApiError;
#[cfg(feature = "test-util")]
use argon2::{Algorithm, Params, Version};
use argon2::{Argon2, PasswordHasher, PasswordVerifier};
use axum::http::StatusCode;
use std::sync::Arc;
use std::sync::atomic::{AtomicUsize, Ordering};
use tokio::sync::{Semaphore, SemaphorePermit};

const MAX_HASH_WAITERS: usize = 16;

pub struct Hasher {
    argon: Argon2<'static>,
    dummy: String,
    slots: Semaphore,
    waiting: AtomicUsize,
}

struct Waiter<'a>(&'a AtomicUsize);

impl Drop for Waiter<'_> {
    fn drop(&mut self) {
        self.0.fetch_sub(1, Ordering::SeqCst);
    }
}

pub fn busy() -> ApiError {
    let mut e = ApiError::new(
        StatusCode::SERVICE_UNAVAILABLE,
        "busy",
        "The server is busy. Try again in a few seconds.",
    );
    e.retry_after = Some(5);
    e
}

impl Hasher {
    pub fn new() -> Arc<Hasher> {
        Self::with(Argon2::default())
    }

    /// Cheap parameters, for tests only.
    #[cfg(feature = "test-util")]
    pub fn insecure_for_tests() -> Arc<Hasher> {
        let params = Params::new(8, 1, 1, None).expect("params");
        Self::with(Argon2::new(Algorithm::Argon2id, Version::V0x13, params))
    }

    /// Tests: every call answers `busy` until switched off again.
    #[cfg(feature = "test-util")]
    pub fn set_busy_for_tests(&self, on: bool) {
        if on {
            self.slots.forget_permits(4);
            self.waiting.store(MAX_HASH_WAITERS, Ordering::SeqCst);
        } else {
            self.waiting.store(0, Ordering::SeqCst);
            self.slots.add_permits(4);
        }
    }

    fn with(argon: Argon2<'static>) -> Arc<Hasher> {
        let dummy = argon
            .hash_password(b"dummy password for timing")
            .expect("hash")
            .to_string();
        Arc::new(Hasher {
            argon,
            dummy,
            slots: Semaphore::new(4),
            waiting: AtomicUsize::new(0),
        })
    }

    async fn permit(&self) -> Result<SemaphorePermit<'_>, ApiError> {
        if let Ok(p) = self.slots.try_acquire() {
            return Ok(p);
        }
        if self.waiting.fetch_add(1, Ordering::SeqCst) >= MAX_HASH_WAITERS {
            self.waiting.fetch_sub(1, Ordering::SeqCst);
            return Err(busy());
        }
        let _w = Waiter(&self.waiting);
        self.slots.acquire().await.map_err(|_| ApiError::internal())
    }

    pub async fn hash(self: &Arc<Self>, pw: String) -> Result<String, ApiError> {
        let me = self.clone();
        let _permit = self.permit().await?;
        tokio::task::spawn_blocking(move || {
            me.argon
                .hash_password(pw.as_bytes())
                .ok()
                .map(|h| h.to_string())
        })
        .await
        .ok()
        .flatten()
        .ok_or_else(ApiError::internal)
    }

    /// `hash` None verifies against a dummy hash so unknown users cost the same time.
    pub async fn verify(
        self: &Arc<Self>,
        pw: String,
        hash: Option<String>,
    ) -> Result<bool, ApiError> {
        let me = self.clone();
        let known = hash.is_some();
        let _permit = self.permit().await?;
        let ok = tokio::task::spawn_blocking(move || {
            let h = hash.as_deref().unwrap_or(&me.dummy);
            me.argon.verify_password(pw.as_bytes(), h).is_ok()
        })
        .await
        .unwrap_or(false);
        Ok(ok && known)
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[tokio::test]
    async fn hasher_answers_busy_when_more_than_16_wait() {
        let params = argon2::Params::new(8, 1, 1, None).unwrap();
        let h = Hasher::with(Argon2::new(
            argon2::Algorithm::Argon2id,
            argon2::Version::V0x13,
            params,
        ));
        let held = h.slots.acquire_many(4).await.unwrap();
        let mut waiting = Vec::new();
        for _ in 0..16 {
            let h = h.clone();
            waiting.push(tokio::spawn(async move {
                h.verify("password".into(), None).await
            }));
        }
        while h.waiting.load(std::sync::atomic::Ordering::SeqCst) < 16 {
            tokio::task::yield_now().await;
        }
        let e = h.hash("another password".into()).await.unwrap_err();
        assert_eq!(
            (e.status.as_u16(), e.code, e.retry_after),
            (503, "busy", Some(5))
        );
        let e = h.verify("password".into(), None).await.unwrap_err();
        assert_eq!(e.code, "busy");
        drop(held);
        for w in waiting {
            assert!(!w.await.unwrap().unwrap());
        }
        assert!(h.hash("ok now".into()).await.is_ok());
    }
}
