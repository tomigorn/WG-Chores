use std::io::{ErrorKind, Write};
use std::os::unix::fs::OpenOptionsExt;
use std::path::Path;

/// Server secret for signing short-lived cookies, created with mode 0600 on first start.
pub fn load_or_create(path: &Path) -> std::io::Result<[u8; 32]> {
    match std::fs::OpenOptions::new()
        .write(true)
        .create_new(true)
        .mode(0o600)
        .open(path)
    {
        Ok(mut f) => {
            let mut key = [0u8; 32];
            getrandom::fill(&mut key).map_err(|e| std::io::Error::other(e.to_string()))?;
            f.write_all(&key)?;
            f.sync_all()?;
            Ok(key)
        }
        Err(e) if e.kind() == ErrorKind::AlreadyExists => {
            let bytes = std::fs::read(path)?;
            bytes.try_into().map_err(|_| {
                std::io::Error::other(format!("{} must hold exactly 32 bytes", path.display()))
            })
        }
        Err(e) => Err(e),
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::os::unix::fs::PermissionsExt;

    fn dir(name: &str) -> std::path::PathBuf {
        let d = std::env::temp_dir().join(format!("wgc-secret-{name}-{}", std::process::id()));
        let _ = std::fs::remove_dir_all(&d);
        std::fs::create_dir_all(&d).unwrap();
        d
    }

    #[test]
    fn created_once_with_mode_0600() {
        let d = dir("create");
        let p = d.join("secret.key");
        let a = load_or_create(&p).unwrap();
        let meta = std::fs::metadata(&p).unwrap();
        assert_eq!(meta.permissions().mode() & 0o777, 0o600);
        assert_eq!(meta.len(), 32);
        assert_ne!(a, [0u8; 32]);
        assert_eq!(load_or_create(&p).unwrap(), a);
        std::fs::remove_dir_all(&d).ok();
    }

    #[test]
    fn wrong_length_rejected() {
        let d = dir("short");
        let p = d.join("secret.key");
        std::fs::write(&p, b"short").unwrap();
        assert!(load_or_create(&p).is_err());
        assert_eq!(std::fs::read(&p).unwrap(), b"short");
        std::fs::remove_dir_all(&d).ok();
    }
}
