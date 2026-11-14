use std::{
    fmt::Write as _,
    fs,
    path::{Path, PathBuf},
};

fn walk(dir: &Path, base: &Path, out: &mut Vec<(String, PathBuf)>) {
    let Ok(rd) = fs::read_dir(dir) else { return };
    for e in rd.flatten() {
        let p = e.path();
        if p.is_dir() {
            walk(&p, base, out);
        } else {
            let rel = p
                .strip_prefix(base)
                .unwrap()
                .to_string_lossy()
                .replace('\\', "/");
            out.push((rel, p));
        }
    }
}

fn write_if_changed(path: &Path, content: &str) {
    if fs::read_to_string(path).is_ok_and(|old| old == content) {
        return;
    }
    fs::write(path, content).unwrap();
}

/// Writes `embedded.rs` into `out_dir`; the placeholder is only allowed outside release builds or with `allow_placeholder`.
#[allow(dead_code)]
pub fn prepare(
    manifest: &Path,
    out_dir: &Path,
    release: bool,
    allow_placeholder: bool,
) -> Result<bool, String> {
    let mut dist = manifest.join("../web/dist");
    let placeholder = !dist.join("index.html").is_file();
    if placeholder {
        if release && !allow_placeholder {
            return Err("web/dist/index.html is missing: build the web app first (cd web && npm run build), or set WGC_ALLOW_PLACEHOLDER=1 for a build without it".into());
        }
        dist = out_dir.join("placeholder");
        fs::create_dir_all(dist.join("assets")).unwrap();
        for (name, body) in [
            (
                "index.html",
                "<!doctype html><html><head><meta charset=\"utf-8\"><title>WG Chores</title></head><body>WG Chores web app not built</body></html>",
            ),
            ("assets/placeholder-0000.js", "// placeholder\n"),
            ("sw.js", "// placeholder\n"),
            ("manifest.webmanifest", "{\"name\":\"WG Chores\"}"),
        ] {
            write_if_changed(&dist.join(name), body);
        }
    }
    let dist = dist.canonicalize().unwrap();
    let mut files = vec![];
    walk(&dist, &dist, &mut files);
    files.sort();
    let mut src = String::from("pub static FILES: &[(&str, &[u8])] = &[\n");
    for (rel, p) in files {
        writeln!(
            src,
            "    ({rel:?}, include_bytes!({:?})),",
            p.to_string_lossy()
        )
        .unwrap();
    }
    src.push_str("];\n");
    write_if_changed(&out_dir.join("embedded.rs"), &src);
    Ok(placeholder)
}

#[allow(dead_code)]
fn main() {
    println!("cargo:rerun-if-changed=openapi.json");
    println!("cargo:rerun-if-changed=build.rs");
    println!("cargo:rerun-if-env-changed=WGC_ALLOW_PLACEHOLDER");
    let manifest = PathBuf::from(std::env::var("CARGO_MANIFEST_DIR").unwrap());
    let out_dir = PathBuf::from(std::env::var("OUT_DIR").unwrap());
    let release = std::env::var("PROFILE").is_ok_and(|p| p == "release");
    let allow = std::env::var("WGC_ALLOW_PLACEHOLDER").is_ok_and(|v| v == "1");
    match prepare(&manifest, &out_dir, release, allow) {
        Ok(false) => println!("cargo:rerun-if-changed=../web/dist"),
        Ok(true) => {}
        Err(e) => {
            eprintln!("error: {e}");
            std::process::exit(1);
        }
    }
}
