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

fn main() {
    println!("cargo:rerun-if-changed=../web/dist");
    println!("cargo:rerun-if-changed=openapi.json");
    println!("cargo:rerun-if-changed=build.rs");
    let manifest = PathBuf::from(std::env::var("CARGO_MANIFEST_DIR").unwrap());
    let out_dir = PathBuf::from(std::env::var("OUT_DIR").unwrap());
    let mut dist = manifest.join("../web/dist");
    if !dist.join("index.html").is_file() {
        dist = out_dir.join("placeholder");
        let _ = fs::remove_dir_all(&dist);
        fs::create_dir_all(dist.join("assets")).unwrap();
        fs::write(dist.join("index.html"), "<!doctype html><html><head><meta charset=\"utf-8\"><title>WG Chores</title></head><body>WG Chores web app not built</body></html>").unwrap();
        fs::write(dist.join("assets/placeholder-0000.js"), "// placeholder\n").unwrap();
        fs::write(dist.join("sw.js"), "// placeholder\n").unwrap();
        fs::write(
            dist.join("manifest.webmanifest"),
            "{\"name\":\"WG Chores\"}",
        )
        .unwrap();
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
    fs::write(out_dir.join("embedded.rs"), src).unwrap();
}
