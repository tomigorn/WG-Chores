use std::io::{ErrorKind, Write};
use std::os::unix::fs::OpenOptionsExt;
use std::path::Path;

/// Server key for signing short-lived cookies, created with mode 0600 on first start.
pub fn load_or_create(path: &Path) -> std::io::Result<[u8; 32]> {
    match std::fs::read(path) {
        Ok(bytes) => return check(path, bytes),
        Err(e) if e.kind() != ErrorKind::NotFound => return Err(e),
        Err(_) => {}
    }
    let mut key = [0u8; 32];
    getrandom::fill(&mut key).map_err(|e| std::io::Error::other(e.to_string()))?;
    let mut suffix = [0u8; 8];
    getrandom::fill(&mut suffix).map_err(|e| std::io::Error::other(e.to_string()))?;
    let name = path
        .file_name()
        .map_or("secret.key".into(), |n| n.to_string_lossy());
    let tmp = path.with_file_name(format!(".{name}.{:016x}.tmp", u64::from_le_bytes(suffix)));
    let written = write_new(&tmp, &key).and_then(|()| std::fs::hard_link(&tmp, path));
    let _ = std::fs::remove_file(&tmp);
    match written {
        Ok(()) => {
            if let Some(dir) = path.parent() {
                std::fs::File::open(dir)?.sync_all()?;
            }
            Ok(key)
        }
        Err(e) if e.kind() == ErrorKind::AlreadyExists => check(path, std::fs::read(path)?),
        Err(e) => Err(e),
    }
}

fn write_new(path: &Path, key: &[u8; 32]) -> std::io::Result<()> {
    let mut f = std::fs::OpenOptions::new()
        .write(true)
        .create_new(true)
        .mode(0o600)
        .open(path)?;
    f.write_all(key)?;
    f.sync_all()
}

fn check(path: &Path, bytes: Vec<u8>) -> std::io::Result<[u8; 32]> {
    bytes.try_into().map_err(|b: Vec<u8>| {
        std::io::Error::other(format!(
            "{} holds {} bytes instead of 32; delete it to create a new key (only logins in progress are lost)",
            path.display(),
            b.len()
        ))
    })
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
        let names: Vec<_> = std::fs::read_dir(&d)
            .unwrap()
            .map(|e| e.unwrap().file_name())
            .collect();
        assert_eq!(names, vec![std::ffi::OsString::from("secret.key")]);
        std::fs::remove_dir_all(&d).ok();
    }

    #[test]
    fn wrong_length_rejected() {
        let d = dir("short");
        let p = d.join("secret.key");
        std::fs::write(&p, b"short").unwrap();
        assert!(load_or_create(&p).is_err());
        assert_eq!(std::fs::read(&p).unwrap(), b"short");
        std::fs::write(&p, b"").unwrap();
        let msg = load_or_create(&p).unwrap_err().to_string();
        assert!(msg.contains(p.to_str().unwrap()), "{msg}");
        assert!(msg.contains("delete it"), "{msg}");
        std::fs::remove_dir_all(&d).ok();
    }
}
