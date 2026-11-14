#[path = "../build.rs"]
mod build;

use std::path::PathBuf;

fn scratch(name: &str) -> PathBuf {
    let d = std::env::temp_dir().join(format!("wgc-build-{name}-{}", std::process::id()));
    let _ = std::fs::remove_dir_all(&d);
    std::fs::create_dir_all(&d).unwrap();
    d
}

#[test]
fn release_without_web_app_fails() {
    let root = scratch("rel");
    let (manifest, out) = (root.join("server"), root.join("out"));
    std::fs::create_dir_all(&manifest).unwrap();
    std::fs::create_dir_all(&out).unwrap();
    let err = build::prepare(&manifest, &out, true, false).unwrap_err();
    assert!(err.contains("WGC_ALLOW_PLACEHOLDER"), "{err}");
    assert!(!out.join("embedded.rs").exists());
    std::fs::remove_dir_all(&root).ok();
}

#[test]
fn release_with_override_or_debug_uses_placeholder() {
    let root = scratch("ph");
    let (manifest, out) = (root.join("server"), root.join("out"));
    std::fs::create_dir_all(&manifest).unwrap();
    std::fs::create_dir_all(&out).unwrap();
    assert_eq!(build::prepare(&manifest, &out, true, true), Ok(true));
    assert_eq!(build::prepare(&manifest, &out, false, false), Ok(true));
    assert!(out.join("embedded.rs").is_file());
    std::fs::remove_dir_all(&root).ok();
}

#[test]
fn real_dist_is_embedded_in_release() {
    let root = scratch("dist");
    let (manifest, out) = (root.join("server"), root.join("out"));
    std::fs::create_dir_all(&manifest).unwrap();
    std::fs::create_dir_all(&out).unwrap();
    std::fs::create_dir_all(root.join("web/dist")).unwrap();
    std::fs::write(root.join("web/dist/index.html"), "<html></html>").unwrap();
    assert_eq!(build::prepare(&manifest, &out, true, false), Ok(false));
    std::fs::remove_dir_all(&root).ok();
}

#[test]
fn placeholder_files_are_not_rewritten() {
    let root = scratch("stable");
    let (manifest, out) = (root.join("server"), root.join("out"));
    std::fs::create_dir_all(&manifest).unwrap();
    std::fs::create_dir_all(&out).unwrap();
    build::prepare(&manifest, &out, false, false).unwrap();
    let mtime = |p: &str| std::fs::metadata(out.join(p)).unwrap().modified().unwrap();
    let (a, b) = (mtime("placeholder/index.html"), mtime("embedded.rs"));
    std::thread::sleep(std::time::Duration::from_millis(30));
    build::prepare(&manifest, &out, false, false).unwrap();
    assert_eq!(a, mtime("placeholder/index.html"));
    assert_eq!(b, mtime("embedded.rs"));
    std::fs::remove_dir_all(&root).ok();
}
