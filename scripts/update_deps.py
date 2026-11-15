#!/usr/bin/env python3
"""Hands-off dependency updates for WG Chores, run daily by .github/workflows/update.yml.

Commands:
  compatible   cargo, npm and base image digests within the current major (+ Android libraries with --android)
  separate     list the candidates tested one by one: majors, Node/Temurin LTS, Rust toolchain (JSON)
  apply ID     apply one candidate from `separate`
  actions      GitHub Actions pins: report, or rewrite with --write (by hand: GITHUB_TOKEN may not change workflows)
  changeset    write the working-tree changes as JSON for the ship step
  ship         (workflow only) commit the candidates as signed commits, run CI on each, fast-forward main, start Release

New versions must be published at least 3 days ago (Actions: 7); a version that fixes an advisory of the version in use is taken at once.
"""
from __future__ import annotations

import argparse
import base64
import datetime as dt
import json
import os
import re
import shutil
import subprocess
import sys
import tempfile
import time
import tomllib
import urllib.error
import urllib.parse
import urllib.request
import xml.etree.ElementTree as ET
from dataclasses import dataclass, field
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
UA = "WG-Chores-update (+https://github.com/tomigorn/WG-Chores)"
MIN_AGE_DAYS = 3
ACTIONS_MIN_AGE_DAYS = 7
SEPARATE_LIMIT = 5
IGNORE_FILE = "scripts/update-deps.ignore"
DOCKERFILES = ["Dockerfile", "android/build-env/Dockerfile"]
ALLOWED_PATHS = {
    "server/Cargo.toml",
    "server/Cargo.lock",
    "web/package.json",
    "web/package-lock.json",
    "Dockerfile",
    "android/build-env/Dockerfile",
    "rust-toolchain.toml",
    "android/gradle/libs.versions.toml",
    "android/app/gradle.lockfile",
}
IMAGE_PATHS = {"Dockerfile", "server/Cargo.toml", "server/Cargo.lock", "web/package.json", "web/package-lock.json"}
CRATES_IO = "registry+https://github.com/rust-lang/crates.io-index"
MAVEN_REPOS = ["https://dl.google.com/dl/android/maven2", "https://repo1.maven.org/maven2", "https://plugins.gradle.org/m2"]
GRADLE_LOCK_TASKS = [":app:dependencies", "assembleRelease", "testDebugUnitTest", "assembleDebugAndroidTest", "--write-locks"]


# ---------- versions ----------

@dataclass
class Release:
    version: str
    published: dt.datetime | None
    yanked: bool = False


def parse_version(v: str):
    m = re.fullmatch(r"v?(\d+(?:\.\d+)*)", v.strip())
    return tuple(int(x) for x in m.group(1).split(".")) if m else None


def compat_key(v: str):
    p = parse_version(v)
    if p is None:
        return None
    p = p + (0,) * (3 - len(p)) if len(p) < 2 else p
    if p[0] > 0:
        return (p[0],)
    if len(p) > 1 and p[1] > 0:
        return (0, p[1])
    return tuple(p[:3])


def maven_stable(v: str) -> bool:
    return re.search(r"(?i)(alpha|beta|rc|dev|eap|snapshot|preview|-m\d|\bcr\d)", v) is None and re.fullmatch(r"[\d.]+", v) is not None


def is_calendar(v: str) -> bool:
    p = parse_version(v)
    return bool(p) and p[0] >= 2000


def gradle_compatible(old: str, new: str) -> bool:
    po, pn = parse_version(old), parse_version(new)
    if not po or not pn or pn <= po:
        return False
    return is_calendar(old) or po[0] == pn[0]


def pick_newest(rels, cutoff, line=None, upto=None, above=None):
    """Newest stable, not yanked release published before cutoff (None = no age rule), optionally in the compat line of `line`."""
    best = None
    for r in rels:
        p = parse_version(r.version)
        if p is None or r.yanked:
            continue
        if line is not None and compat_key(r.version) != compat_key(line):
            continue
        if upto is not None and p > parse_version(upto):
            continue
        if above is not None and p <= parse_version(above):
            continue
        if cutoff is not None and (r.published is None or r.published > cutoff):
            continue
        if best is None or p > parse_version(best.version):
            best = r
    return best


# ---------- report ----------

@dataclass
class Report:
    updated: list = field(default_factory=list)
    held: list = field(default_factory=list)
    notes: list = field(default_factory=list)

    def up(self, s):
        self.updated.append(s)

    def hold(self, s):
        self.held.append(s)

    def note(self, s):
        self.notes.append(s)

    def markdown(self, title):
        out = [f"### {title}", ""]
        out += ["**Updated**", ""] + [f"- {s}" for s in self.updated] if self.updated else ["Nothing to update."]
        if self.held:
            out += ["", "**Held back**", ""] + [f"- {s}" for s in self.held]
        if self.notes:
            out += ["", "**Notes**", ""] + [f"- {s}" for s in self.notes]
        return "\n".join(out) + "\n"


# ---------- http ----------

def http(url, method="GET", headers=None, data=None, timeout=40):
    hdrs = {"User-Agent": UA, **(headers or {})}
    body = json.dumps(data).encode() if isinstance(data, (dict, list)) else data
    if isinstance(data, (dict, list)):
        hdrs.setdefault("Content-Type", "application/json")
    last = None
    for attempt in range(4):
        req = urllib.request.Request(url, data=body, method=method, headers=hdrs)
        try:
            with urllib.request.urlopen(req, timeout=timeout) as r:
                return r.status, dict(r.headers), r.read()
        except urllib.error.HTTPError as e:
            if e.code < 500 and e.code != 429:
                return e.code, dict(e.headers), e.read()
            last = e
        except (urllib.error.URLError, TimeoutError) as e:
            last = e
        time.sleep(2 * (attempt + 1))
    raise RuntimeError(f"{method} {url}: {last}")


def get_json(url, headers=None):
    status, _, body = http(url, headers=headers)
    if status != 200:
        raise RuntimeError(f"GET {url}: HTTP {status}")
    return json.loads(body)


def iso(s):
    if not s:
        return None
    s = s.replace("Z", "+00:00")
    t = dt.datetime.fromisoformat(s)
    return t if t.tzinfo else t.replace(tzinfo=dt.timezone.utc)


def day(t):
    return t.strftime("%Y-%m-%d") if t else "?"


def gh_headers():
    tok = os.environ.get("GH_TOKEN") or os.environ.get("GITHUB_TOKEN")
    h = {"Accept": "application/vnd.github+json", "X-GitHub-Api-Version": "2022-11-28"}
    if tok:
        h["Authorization"] = f"Bearer {tok}"
    return h


def run(cmd, cwd, check=True, env=None):
    p = subprocess.run(cmd, cwd=cwd, text=True, capture_output=True, env={**os.environ, **(env or {})})
    if check and p.returncode != 0:
        raise RuntimeError(f"{' '.join(cmd)} failed ({p.returncode}):\n{p.stdout[-3000:]}\n{p.stderr[-3000:]}")
    return p


# ---------- advisories (OSV: RustSec + GitHub advisories) ----------

def osv_affected(ecosystem, pkgs):
    pkgs = sorted(set(pkgs))
    hit = set()
    for i in range(0, len(pkgs), 500):
        chunk = pkgs[i:i + 500]
        q = {"queries": [{"package": {"name": n, "ecosystem": ecosystem}, "version": v} for n, v in chunk]}
        status, _, body = http("https://api.osv.dev/v1/querybatch", "POST", data=q)
        if status != 200:
            raise RuntimeError(f"OSV query: HTTP {status}")
        for pkg, res in zip(chunk, json.loads(body).get("results", [])):
            if res.get("vulns"):
                hit.add(pkg)
    return hit


# ---------- ignore file ----------

@dataclass
class Ignores:
    rules: list

    def ignored(self, eco, name, kind):
        return any(e == eco and n == name and (s == "all" or s == kind) for e, n, s, _ in self.rules)

    def reason(self, eco, name):
        return next((r for e, n, _, r in self.rules if e == eco and n == name), "")


def parse_ignore(text):
    rules = []
    for i, line in enumerate(text.splitlines(), 1):
        line = line.strip()
        if not line or line.startswith("#"):
            continue
        parts = line.split(None, 3)
        if len(parts) < 4 or parts[2] not in ("major", "all"):
            raise ValueError(f"{IGNORE_FILE}:{i}: expected '<ecosystem> <name> <major|all> <reason>'")
        rules.append(tuple(parts))
    return Ignores(rules)


def load_ignores(root):
    p = root / IGNORE_FILE
    return parse_ignore(p.read_text()) if p.exists() else Ignores([])


# ---------- cargo ----------

def cargo_lock_packages(text):
    data = tomllib.loads(text)
    return [(p["name"], p["version"]) for p in data.get("package", []) if p.get("source") == CRATES_IO]


def lock_changes(old, new):
    oldset = set(old)
    out = []
    for name, ver in new:
        if (name, ver) in oldset:
            continue
        prev = [v for n, v in old if n == name and compat_key(v) == compat_key(ver) and (n, v) not in set(new)]
        out.append((name, prev[0] if prev else None, ver))
    return out


def bump_req(old_req, new_version):
    n = len(old_req.split("."))
    return ".".join(new_version.split(".")[:n])


DEP_SECTION = re.compile(r"^\[(?:target\..+\.)?(?:dev-|build-)?dependencies\]\s*$")


def cargo_set_req(text, name, req):
    out, in_deps = [], False
    pat = re.compile(r'^(' + re.escape(name) + r'\s*=\s*(?:\{\s*version\s*=\s*)?")([^"]+)(")')
    for line in text.splitlines(keepends=True):
        if line.startswith("["):
            in_deps = bool(DEP_SECTION.match(line.strip()))
        elif in_deps:
            line = pat.sub(lambda m: m.group(1) + req + m.group(3), line)
        out.append(line)
    return "".join(out)


def cargo_manifest_reqs(text):
    data = tomllib.loads(text)
    tables = [data.get(k, {}) for k in ("dependencies", "dev-dependencies", "build-dependencies")]
    for t in data.get("target", {}).values():
        tables += [t.get(k, {}) for k in ("dependencies", "dev-dependencies", "build-dependencies")]
    reqs = {}
    for t in tables:
        for name, spec in t.items():
            if isinstance(spec, str):
                reqs.setdefault(name, spec)
            elif isinstance(spec, dict) and "version" in spec and "path" not in spec and "git" not in spec and "package" not in spec:
                reqs.setdefault(name, spec["version"])
    return {n: r.lstrip("^=~ ") for n, r in reqs.items()}


_crate_cache = {}


def crate_releases(name):
    if name not in _crate_cache:
        rels, url = [], f"https://crates.io/api/v1/crates/{urllib.parse.quote(name)}/versions?per_page=100"
        for _ in range(5):
            data = get_json(url)
            rels += [Release(v["num"], iso(v["created_at"]), v.get("yanked", False)) for v in data["versions"]]
            nxt = data.get("meta", {}).get("next_page")
            if not nxt:
                break
            url = f"https://crates.io/api/v1/crates/{urllib.parse.quote(name)}/versions{nxt}"
            time.sleep(1)
        _crate_cache[name] = rels
        time.sleep(1)
    return _crate_cache[name]


def enforce_cargo_age(server, old, cutoff, rep, ign, affected):
    """Hold back every lockfile change newer than cutoff unless the version in use has an advisory."""
    lock = server / "Cargo.lock"
    held = {}
    for _ in range(4):
        progress = False
        for name, prev, ver in lock_changes(old, cargo_lock_packages(lock.read_text())):
            if prev and ign.ignored("cargo", name, "all"):
                if run(["cargo", "update", "-p", f"{name}@{ver}", "--precise", prev], server, check=False).returncode == 0:
                    held[name] = f"cargo {name} {ver}: ignored ({ign.reason('cargo', name)})"
                    progress = True
                continue
            if prev and (name, prev) in affected:
                continue
            rels = crate_releases(name)
            pub = next((r.published for r in rels if r.version == ver), None)
            if pub and pub <= cutoff:
                continue
            target = pick_newest(rels, cutoff, line=ver, upto=ver)
            if target and prev and parse_version(target.version) < parse_version(prev):
                target = None
            target_v = target.version if target else prev
            if target_v and target_v != ver:
                if run(["cargo", "update", "-p", f"{name}@{ver}", "--precise", target_v], server, check=False).returncode == 0:
                    held[name] = f"cargo {name} {ver}: too new (published {day(pub)}), {'stays at' if target_v == prev else 'takes'} {target_v}"
                    progress = True
        if not progress:
            break
    for name, prev, ver in lock_changes(old, cargo_lock_packages(lock.read_text())):
        label = f"cargo {name} {prev} → {ver}" if prev else f"cargo {name} {ver} (new)"
        pub = next((r.published for r in crate_releases(name) if r.version == ver), None)
        if prev and (name, prev) in affected:
            label += " (advisory on the old version: taken at once)"
        elif not pub or pub > cutoff:
            label += f" (published {day(pub)}: newer than allowed, but required by another update)"
        rep.up(label)
    for msg in held.values():
        rep.hold(msg)


def cargo_compatible(root, cutoff, rep, ign):
    server = root / "server"
    old = cargo_lock_packages((server / "Cargo.lock").read_text())
    affected = osv_affected("crates.io", old)
    run(["cargo", "update"], server, env={"CARGO_TERM_COLOR": "never"})
    enforce_cargo_age(server, old, cutoff, rep, ign, affected)
    still = osv_affected("crates.io", cargo_lock_packages((server / "Cargo.lock").read_text()))
    for n, v in sorted(still):
        rep.note(f"cargo {n} {v} has an advisory and no compatible fix: needs a major or a replacement (https://osv.dev/list?ecosystem=crates.io&q={n})")


def cargo_majors(root, cutoff, ign):
    out = []
    for name, req in sorted(cargo_manifest_reqs((root / "server/Cargo.toml").read_text()).items()):
        if ign.ignored("cargo", name, "major") or compat_key(req) is None:
            continue
        best = pick_newest(crate_releases(name), cutoff)
        if best and compat_key(best.version) != compat_key(req) and parse_version(best.version) > parse_version(req):
            out.append({"id": f"cargo:{name}@{best.version}", "title": f"{name} {req} → {best.version}"})
    return out


def cargo_apply_major(root, name, ver, cutoff, rep, ign):
    server = root / "server"
    toml_path = server / "Cargo.toml"
    req = cargo_manifest_reqs(toml_path.read_text())[name]
    old = cargo_lock_packages((server / "Cargo.lock").read_text())
    toml_path.write_text(cargo_set_req(toml_path.read_text(), name, bump_req(req, ver)))
    run(["cargo", "update", "-w"], server, env={"CARGO_TERM_COLOR": "never"})
    enforce_cargo_age(server, old, cutoff, rep, ign, set())


# ---------- npm ----------

NPM = ["--package-lock-only", "--ignore-scripts", "--no-audit", "--no-fund"]


def npm_lock_versions(lock):
    out = {}
    for path, meta in lock.get("packages", {}).items():
        if not path or meta.get("link") or "version" not in meta:
            continue
        name = path.split("node_modules/")[-1]
        out.setdefault(name, set()).add(meta["version"])
    return out


def npm_changes(old, new):
    return {n: sorted(vs - old.get(n, set())) for n, vs in sorted(new.items()) if vs - old.get(n, set())}


_npm_cache = {}


def npm_releases(name):
    if name not in _npm_cache:
        doc = get_json("https://registry.npmjs.org/" + name.replace("/", "%2F"))
        times = doc.get("time", {})
        _npm_cache[name] = [Release(v, iso(times.get(v)), bool(m.get("deprecated"))) for v, m in doc.get("versions", {}).items()]
    return _npm_cache[name]


def npm_direct(pkg):
    out = {}
    for group, flag in (("dependencies", "--save-prod"), ("devDependencies", "--save-dev")):
        for n, v in pkg.get(group, {}).items():
            out[n] = (v.lstrip("^~="), flag)
    return out


def npm_install(web, specs, before=None):
    by_flag = {}
    for spec, flag in specs:
        by_flag.setdefault(flag, []).append(spec)
    for flag, names in by_flag.items():
        extra = [f"--before={before.isoformat()}"] if before else []
        run(["npm", "install", *NPM, "--save-exact", flag, *extra, *names], web)


def npm_compatible(root, cutoff, rep, ign):
    web = root / "web"
    pkg = json.loads((web / "package.json").read_text())
    old_lock = json.loads((web / "package-lock.json").read_text())
    old = npm_lock_versions(old_lock)
    affected = osv_affected("npm", [(n, v) for n, vs in old.items() for v in vs])
    direct = npm_direct(pkg)
    aged, urgent = [], []
    for name, (cur, flag) in sorted(direct.items()):
        if ign.ignored("npm", name, "compatible"):
            continue
        rels = npm_releases(name)
        vuln = (name, cur) in affected
        target = pick_newest(rels, None if vuln else cutoff, line=cur, above=cur)
        newest = pick_newest(rels, None, line=cur, above=cur)
        if target:
            (urgent if vuln else aged).append((f"{name}@{target.version}", flag))
            rep.up(f"npm {name} {cur} → {target.version}" + (" (advisory: taken at once)" if vuln else ""))
        if newest and (not target or newest.version != target.version):
            rep.hold(f"npm {name} {newest.version}: too new (published {day(newest.published)})")
    if aged:
        npm_install(web, aged, before=cutoff)
    if urgent:
        npm_install(web, urgent)
    # transitive: learn what `npm update` would change on a scratch copy, then update only names old enough
    with tempfile.TemporaryDirectory() as tmp:
        for f in ("package.json", "package-lock.json"):
            shutil.copy(web / f, Path(tmp) / f)
        before = npm_lock_versions(json.loads((Path(tmp) / "package-lock.json").read_text()))
        run(["npm", "update", *NPM], tmp)
        after = npm_lock_versions(json.loads((Path(tmp) / "package-lock.json").read_text()))
    take = []
    for name, vers in npm_changes(before, after).items():
        if name in direct:
            continue
        rels = {r.version: r for r in npm_releases(name)}
        vuln = any((name, v) in affected for v in before.get(name, ()))
        fresh = [v for v in vers if not (rels.get(v) and rels[v].published and rels[v].published <= cutoff)]
        if vuln or not fresh:
            take.append(name)
            rep.up(f"npm {name} {', '.join(sorted(before.get(name, {'-'})))} → {', '.join(vers)}" + (" (advisory: taken at once)" if vuln else ""))
        else:
            rep.hold(f"npm {name} {', '.join(fresh)}: too new (published {', '.join(day(rels[v].published) if v in rels else '?' for v in fresh)})")
    if take:
        run(["npm", "update", *NPM, *take], web)
    lock = json.loads((web / "package-lock.json").read_text())
    for n, v in sorted(osv_affected("npm", [(n, v) for n, vs in npm_lock_versions(lock).items() for v in vs])):
        rep.note(f"npm {n} {v} has an advisory and no fix within the allowed ranges (https://osv.dev/list?ecosystem=npm&q={n})")


def npm_majors(root, cutoff, ign):
    out = []
    pkg = json.loads((root / "web/package.json").read_text())
    for name, (cur, _) in sorted(npm_direct(pkg).items()):
        if ign.ignored("npm", name, "major"):
            continue
        best = pick_newest(npm_releases(name), cutoff, above=cur)
        if best and compat_key(best.version) != compat_key(cur):
            out.append({"id": f"npm:{name}@{best.version}", "title": f"{name} {cur} → {best.version}"})
    return out


def npm_apply(root, name, ver, cutoff):
    web = root / "web"
    _, flag = npm_direct(json.loads((web / "package.json").read_text()))[name]
    pub = next((r.published for r in npm_releases(name) if r.version == ver), None)
    npm_install(web, [(f"{name}@{ver}", flag)], before=max(cutoff, pub) if pub else None)


# ---------- docker ----------

FROM_RE = re.compile(r"^FROM\s+(?:--platform=\S+\s+)?([a-z0-9][a-z0-9._/-]*):([A-Za-z0-9._-]+)@(sha256:[0-9a-f]{64})", re.M)


def parse_froms(text):
    return [(m.group(1), m.group(2), m.group(3)) for m in FROM_RE.finditer(text)]


def repin(text, image, old_tag, new_tag, digest):
    pat = re.compile(r"^(FROM\s+(?:--platform=\S+\s+)?)" + re.escape(image) + ":" + re.escape(old_tag) + r"@sha256:[0-9a-f]{64}", re.M)
    return pat.sub(lambda m: f"{m.group(1)}{image}:{new_tag}@{digest}", text)


def major_tag(tag):
    m = re.fullmatch(r"(\d+)(-.+)?", tag)
    return (int(m.group(1)), "{}" + (m.group(2) or "")) if m else None


def node_lts_target(schedule, current, today, min_age_days=MIN_AGE_DAYS):
    best = None
    for key, v in schedule.items():
        m = re.fullmatch(r"v(\d+)", key)
        if not m or "lts" not in v:
            continue
        major = int(m.group(1))
        lts = iso(v["lts"] + "T00:00:00")
        end = iso(v["end"] + "T00:00:00")
        if major > current and lts + dt.timedelta(days=min_age_days) <= today < end and (best is None or major > best):
            best = major
    return best


def temurin_lts_target(lts, current):
    newer = [x for x in lts if x > current]
    return max(newer) if newer else None


def docker_repo(image):
    return image if "/" in image else f"library/{image}"


def docker_digest(image, tag):
    if "." in image.split("/")[0]:
        raise RuntimeError(f"{image}: only Docker Hub images are supported")
    repo = docker_repo(image)
    tok = get_json(f"https://auth.docker.io/token?service=registry.docker.io&scope=repository:{repo}:pull")["token"]
    accept = "application/vnd.oci.image.index.v1+json, application/vnd.docker.distribution.manifest.list.v2+json"
    status, hdrs, _ = http(f"https://registry-1.docker.io/v2/{repo}/manifests/{tag}", "HEAD", {"Authorization": f"Bearer {tok}", "Accept": accept})
    if status == 404:
        return None
    if status != 200:
        raise RuntimeError(f"{image}:{tag}: HTTP {status}")
    ctype = next((v for k, v in hdrs.items() if k.lower() == "content-type"), "")
    if "index" not in ctype and "list" not in ctype:
        raise RuntimeError(f"{image}:{tag} is not a multi-arch index ({ctype})")
    return next(v for k, v in hdrs.items() if k.lower() == "docker-content-digest")


def docker_compatible(root, rep):
    for f in DOCKERFILES:
        p = root / f
        text = p.read_text()
        for image, tag, digest in parse_froms(text):
            new = docker_digest(image, tag)
            if new is None:
                rep.note(f"{f}: {image}:{tag} no longer exists")
            elif new != digest:
                text = repin(text, image, tag, tag, new)
                rep.up(f"{f}: {image}:{tag} digest {digest[7:19]} → {new[7:19]}")
        p.write_text(text)


def docker_majors(root, today):
    out = []
    for f in DOCKERFILES:
        for image, tag, _ in parse_froms((root / f).read_text()):
            mt = major_tag(tag)
            if not mt:
                continue
            cur, fmt = mt
            target = None
            if image == "node":
                target = node_lts_target(get_json("https://raw.githubusercontent.com/nodejs/Release/main/schedule.json"), cur, today)
            elif image == "eclipse-temurin":
                target = temurin_lts_target(get_json("https://api.adoptium.net/v3/info/available_releases")["available_lts_releases"], cur)
            if target and docker_digest(image, fmt.format(target)):
                out.append({"id": f"docker:{f}:{image}:{tag}:{fmt.format(target)}", "title": f"{image}:{tag} → {fmt.format(target)} ({f})"})
    return out


def docker_apply(root, f, image, old_tag, new_tag, cutoff, rep):
    p = root / f
    p.write_text(repin(p.read_text(), image, old_tag, new_tag, docker_digest(image, new_tag)))
    if image == "node":
        major = major_tag(new_tag)[0]
        web = root / "web"
        direct = npm_direct(json.loads((web / "package.json").read_text()))
        if "@types/node" in direct:
            t = pick_newest(npm_releases("@types/node"), cutoff, line=f"{major}.0.0")
            if t:
                npm_install(web, [(f"@types/node@{t.version}", direct["@types/node"][1])], before=cutoff)
                rep.up(f"npm @types/node → {t.version} (matches Node {major})")


# ---------- rust toolchain ----------

def rust_stable(text):
    data = tomllib.loads(text)
    return data["pkg"]["rust"]["version"].split()[0], iso(data["date"] + "T00:00:00")


def set_channel(text, version):
    return re.sub(r'^channel\s*=\s*"[^"]*"', f'channel = "{version}"', text, flags=re.M)


def toolchain_candidate(root, cutoff):
    cur = tomllib.loads((root / "rust-toolchain.toml").read_text())["toolchain"]["channel"]
    _, _, body = http("https://static.rust-lang.org/dist/channel-rust-stable.toml")
    ver, date = rust_stable(body.decode())
    if parse_version(cur) and parse_version(ver) > parse_version(cur) and date <= cutoff:
        return [{"id": f"toolchain:{ver}", "title": f"Rust toolchain {cur} → {ver}"}]
    return []


# ---------- github actions ----------

USES_RE = re.compile(r"uses:\s*([\w.-]+/[\w.-]+)(?:/[\w./-]+)?@([0-9a-f]{40})\s*#\s*(v?[\d.]+)")


def parse_uses(text):
    return [(m.group(1), m.group(2), m.group(3)) for m in USES_RE.finditer(text)]


def pick_action(rels, current, cutoff):
    cur = parse_version(current)
    best = None
    for r in rels:
        p = parse_version(r.version)
        if not p or p[0] != cur[0] or p <= cur or r.published is None or r.published > cutoff:
            continue
        if best is None or p > parse_version(best.version):
            best = r
    return best


def repin_uses(text, repo, old_sha, new_sha, tag):
    pat = re.compile(r"(uses:\s*" + re.escape(repo) + r"(?:/[\w./-]+)?@)" + old_sha + r"(\s*#\s*)v?[\d.]+")
    return pat.sub(lambda m: f"{m.group(1)}{new_sha}{m.group(2)}{tag}", text)


def action_releases(repo):
    rels = get_json(f"https://api.github.com/repos/{repo}/releases?per_page=50", gh_headers())
    return [Release(r["tag_name"], iso(r["published_at"])) for r in rels if not r["draft"] and not r["prerelease"]]


def actions_update(root, cutoff, rep, write):
    files = sorted((root / ".github/workflows").glob("*.yml"))
    seen = {}
    for f in files:
        for repo, sha, tag in parse_uses(f.read_text()):
            seen.setdefault((repo, sha, tag), None)
    for repo, sha, tag in seen:
        best = pick_action(action_releases(repo), tag, cutoff)
        if not best:
            continue
        status, _, body = http(f"https://api.github.com/repos/{repo}/commits/{best.version}", headers=gh_headers())
        if status != 200:
            rep.note(f"{repo} {best.version}: commit not found (HTTP {status})")
            continue
        new_sha = json.loads(body)["sha"]
        rep.up(f"{repo} {tag} → {best.version}")
        if write:
            for f in files:
                f.write_text(repin_uses(f.read_text(), repo, sha, new_sha, best.version))


# ---------- gradle ----------

def gradle_refs(text):
    data = tomllib.loads(text)
    refs = {}
    for lib in data.get("libraries", {}).values():
        if isinstance(lib, dict) and isinstance(lib.get("version"), dict) and "ref" in lib["version"]:
            g, n = (lib["group"], lib["name"]) if "group" in lib else lib["module"].split(":")
            refs.setdefault(lib["version"]["ref"], []).append((g, n))
    for plug in data.get("plugins", {}).values():
        if isinstance(plug, dict) and isinstance(plug.get("version"), dict) and "ref" in plug["version"]:
            refs.setdefault(plug["version"]["ref"], []).append((plug["id"], f"{plug['id']}.gradle.plugin"))
    return refs


def gradle_set_version(text, key, version):
    out, section = [], None
    for line in text.splitlines(keepends=True):
        s = line.strip()
        if s.startswith("["):
            section = s
        elif section == "[versions]":
            line = re.sub(r'^(' + re.escape(key) + r'\s*=\s*")[^"]*(")', lambda m: m.group(1) + version + m.group(2), line)
        out.append(line)
    return "".join(out)


def maven_versions(xml_text):
    return [v.text for v in ET.fromstring(xml_text).iter("version") if v.text]


def maven_find(group, artifact):
    for repo in MAVEN_REPOS:
        status, _, body = http(f"{repo}/{group.replace('.', '/')}/{artifact}/maven-metadata.xml")
        if status == 200:
            return repo, maven_versions(body.decode())
    return None, []


def maven_published(repo, group, artifact, version):
    status, hdrs, _ = http(f"{repo}/{group.replace('.', '/')}/{artifact}/{version}/{artifact}-{version}.pom", "HEAD")
    if status != 200:
        return None
    lm = next((v for k, v in hdrs.items() if k.lower() == "last-modified"), None)
    return dt.datetime.strptime(lm, "%a, %d %b %Y %H:%M:%S %Z").replace(tzinfo=dt.timezone.utc) if lm else None


def gradle_compatible_update(root, cutoff, rep, ign):
    path = root / "android/gradle/libs.versions.toml"
    text = path.read_text()
    current = tomllib.loads(text).get("versions", {})
    changed = False
    for key, members in sorted(gradle_refs(text).items()):
        cur = current.get(key)
        if not cur or ign.ignored("gradle", key, "compatible"):
            continue
        g, a = members[0]
        repo, versions = maven_find(g, a)
        if not repo:
            rep.note(f"gradle {key}: {g}:{a} not found in google/central/plugin portal")
            continue
        stable = sorted((v for v in versions if maven_stable(v) and parse_version(v) and parse_version(v) > parse_version(cur)), key=parse_version, reverse=True)
        majors = [v for v in stable if not gradle_compatible(cur, v)]
        if majors:
            rep.hold(f"gradle {key} {majors[0]}: major, by hand (Android majors usually need Gradle/AGP/code changes)")
        chosen = None
        for v in [v for v in stable if gradle_compatible(cur, v)][:6]:
            pub = maven_published(repo, g, a, v)
            if pub and pub <= cutoff:
                chosen = v
                break
            rep.hold(f"gradle {key} {v}: too new (published {day(pub)})")
        if not chosen:
            continue
        missing = [f"{mg}:{ma}" for mg, ma in members[1:] if not maven_published(maven_find(mg, ma)[0] or repo, mg, ma, chosen)]
        if missing:
            rep.hold(f"gradle {key} {chosen}: not published for {', '.join(missing)}")
            continue
        text = gradle_set_version(text, key, chosen)
        rep.up(f"gradle {key} {cur} → {chosen}")
        changed = True
    path.write_text(text)
    return changed


def gradle_write_locks(root, rep):
    if not os.environ.get("ANDROID_HOME"):
        rep.note("android/app/gradle.lockfile not refreshed (no Android SDK here): run android/scripts/beefy-build.sh --write-locks")
        return
    run(["./gradlew", "--no-daemon", "--console=plain", *GRADLE_LOCK_TASKS], root / "android")
    rep.up("android/app/gradle.lockfile refreshed")


# ---------- candidates ----------

def rotate(items, day_no, limit):
    if not items:
        return []
    k = day_no % len(items)
    return (items[k:] + items[:k])[:limit]


def path_allowed(p):
    return p in ALLOWED_PATHS


def change_flags(paths):
    return {"android": any(p.startswith("android/") for p in paths), "image": any(p in IMAGE_PATHS for p in paths)}


def pick_landing(main_sha, base_sha, base_result, separate):
    """Returns (sha to fast-forward main to or None, base sha the separate candidates sit on or None)."""
    if base_sha != main_sha and base_result != "pass":
        return None, None
    for sha, result in separate:
        if result == "pass":
            return sha, base_sha
    return (base_sha if base_sha != main_sha else None), base_sha


def separate_candidates(root, cutoff, today, ign):
    out = []
    out += toolchain_candidate(root, cutoff)
    out += docker_majors(root, today)
    out += cargo_majors(root, cutoff, ign)
    out += npm_majors(root, cutoff, ign)
    return out


def apply_candidate(root, cid, cutoff, rep, ign):
    kind, _, rest = cid.partition(":")
    if kind == "toolchain":
        p = root / "rust-toolchain.toml"
        p.write_text(set_channel(p.read_text(), rest))
    elif kind == "cargo":
        name, ver = rest.rsplit("@", 1)
        cargo_apply_major(root, name, ver, cutoff, rep, ign)
    elif kind == "npm":
        name, ver = rest.rsplit("@", 1)
        npm_apply(root, name, ver, cutoff)
    elif kind == "docker":
        f, image, old_tag, new_tag = rest.split(":")
        docker_apply(root, f, image, old_tag, new_tag, cutoff, rep)
    else:
        raise ValueError(f"unknown candidate {cid!r} (see: update_deps.py separate)")
    rep.up(cid)


# ---------- changeset + ship ----------

def git_changed(root):
    out = run(["git", "status", "--porcelain", "--untracked-files=no", "-z"], root).stdout
    return sorted(e[3:] for e in out.split("\0") if e)


def changeset(root, title):
    paths = git_changed(root)
    bad = [p for p in paths if not path_allowed(p)]
    if bad:
        raise RuntimeError(f"changed files outside the allowed set: {', '.join(bad)}")
    files = {p: base64.b64encode((root / p).read_bytes()).decode() for p in paths}
    return {"title": title, "files": files}


class GitHub:
    def __init__(self, repo):
        self.repo = repo
        self.h = gh_headers()

    def api(self, method, path, data=None, ok=(200, 201, 204)):
        status, _, body = http(f"https://api.github.com{path}", method, self.h, data)
        if status not in ok:
            raise RuntimeError(f"{method} {path}: HTTP {status} {body[:300]!r}")
        return json.loads(body) if body else {}

    def set_branch(self, name, sha):
        status, _, _ = http(f"https://api.github.com/repos/{self.repo}/git/refs", "POST", self.h, {"ref": f"refs/heads/{name}", "sha": sha})
        if status != 201:
            self.api("PATCH", f"/repos/{self.repo}/git/refs/heads/{name}", {"sha": sha, "force": True})

    def update_main(self, sha):
        return http(f"https://api.github.com/repos/{self.repo}/git/refs/heads/main", "PATCH", self.h, {"sha": sha, "force": False})[0]

    def delete_branch(self, name):
        http(f"https://api.github.com/repos/{self.repo}/git/refs/heads/{name}", "DELETE", self.h)

    def commit(self, branch, head, title, files):
        q = "mutation($input: CreateCommitOnBranchInput!) { createCommitOnBranch(input: $input) { commit { oid } } }"
        inp = {
            "branch": {"repositoryNameWithOwner": self.repo, "branchName": branch},
            "message": {"headline": title},
            "expectedHeadOid": head,
            "fileChanges": {"additions": [{"path": p, "contents": c} for p, c in sorted(files.items())]},
        }
        res = self.api("POST", "/graphql", {"query": q, "variables": {"input": inp}})
        if res.get("errors"):
            raise RuntimeError(f"createCommitOnBranch: {res['errors']}")
        return res["data"]["createCommitOnBranch"]["commit"]["oid"]

    def dispatch(self, workflow, ref, inputs):
        self.api("POST", f"/repos/{self.repo}/actions/workflows/{workflow}/dispatches", {"ref": ref, "inputs": inputs})

    def find_run(self, workflow, branch, sha, timeout=600):
        end = time.time() + timeout
        while time.time() < end:
            runs = self.api("GET", f"/repos/{self.repo}/actions/workflows/{workflow}/runs?branch={branch}&event=workflow_dispatch&head_sha={sha}&per_page=5")
            if runs.get("workflow_runs"):
                return runs["workflow_runs"][0]["id"]
            time.sleep(10)
        raise RuntimeError(f"no {workflow} run appeared for {branch} ({sha[:7]})")

    def run_result(self, run_id):
        r = self.api("GET", f"/repos/{self.repo}/actions/runs/{run_id}")
        return (r["conclusion"] if r["status"] == "completed" else None), r["html_url"]


def ship(args, gh=None):
    gh = gh or GitHub(os.environ["GITHUB_REPOSITORY"])
    out, summary = Path(args.candidates), []
    main_sha = args.main_sha
    base = json.loads((out / "base.json").read_text()) if (out / "base.json").exists() else None
    seps = [json.loads(p.read_text()) for p in sorted(out.glob("separate-*.json"))]
    for cs in [c for c in [base, *seps] if c]:
        bad = [p for p in cs["files"] if not path_allowed(p)]
        if bad:
            raise RuntimeError(f"candidate {cs['title']!r} touches files outside the allowed set: {bad}")

    for ref in gh.api("GET", f"/repos/{gh.repo}/git/matching-refs/heads/auto-update"):
        gh.delete_branch(ref["ref"].removeprefix("refs/heads/"))
    branches, tests = {}, []
    base_sha = main_sha
    if base and base["files"]:
        gh.set_branch("auto-update", main_sha)
        base_sha = gh.commit("auto-update", main_sha, base["title"], base["files"])
        branches["auto-update"] = base_sha
        tests.append(("auto-update", base_sha, base, change_flags(list(base["files"]))))
    for i, cs in enumerate(seps, 1):
        if not cs["files"]:
            summary.append(f"- {cs['title']}: no file changes, skipped")
            continue
        name = f"auto-update-{i}"
        gh.set_branch(name, base_sha)
        sha = gh.commit(name, base_sha, cs["title"], cs["files"])
        branches[name] = sha
        flags = change_flags(list(cs["files"]) + (list(base["files"]) if base else []))
        tests.append((name, sha, cs, flags))

    runs = {}
    for name, sha, cs, flags in tests:
        gh.dispatch("ci.yml", name, {"skip_android": "false" if flags["android"] else "true"})
        runs[name] = gh.find_run("ci.yml", name, sha)
    results, urls = {}, {}
    end = time.time() + args.timeout * 60
    while len(results) < len(runs) and time.time() < end:
        for name, rid in runs.items():
            if name not in results:
                concl, url = gh.run_result(rid)
                urls[name] = url
                if concl:
                    results[name] = "pass" if concl == "success" else "fail"
        if len(results) < len(runs):
            time.sleep(30)
    for name in runs:
        results.setdefault(name, "fail")

    base_result = results.get("auto-update")
    sep_results = [(sha, results[name]) for name, sha, cs, _ in tests if name != "auto-update"]
    target, _ = pick_landing(main_sha, base_sha, base_result, sep_results)

    landed, image = False, False
    if target:
        status = gh.update_main(target)
        if status == 200:
            landed = True
            chain = [t for t in tests if t[0] == "auto-update"] + [t for t in tests if t[1] == target and t[0] != "auto-update"]
            image = any(t[3]["image"] for t in chain)
        else:
            summary.append(f"- main moved during the run (HTTP {status}): nothing landed, the next run retries")

    lines = ["## Update run", ""]
    for name, sha, cs, flags in tests:
        state = {"pass": "passed", "fail": "FAILED"}[results[name]]
        mark = " → **live on main**" if landed and (sha == target or name == "auto-update") else ""
        lines.append(f"- `{name}` {cs['title']}: tests {state} ([CI run]({urls.get(name, '')})){mark}")
    if not tests:
        lines.append("- nothing to update today")
    passing_seps = [n for n, sha, cs, _ in tests if n != "auto-update" and results[n] == "pass" and sha != target]
    if passing_seps:
        lines.append(f"- passed but waits for the next run (one separate candidate lands per run): {', '.join(passing_seps)}")
    lines += summary

    for name in branches:
        if results.get(name) == "pass" or name == "auto-update" and landed:
            gh.delete_branch(name)

    if args.release:
        no_cache = args.weekly_rebuild
        if (landed and image) or no_cache:
            gh.dispatch("release.yml", "main", {"no_cache": "true" if no_cache else "false"})
            lines.append(f"- Release started{' without cache (weekly rebuild)' if no_cache else ''}")
    text = "\n".join(lines) + "\n"
    print(text)
    if os.environ.get("GITHUB_STEP_SUMMARY"):
        with open(os.environ["GITHUB_STEP_SUMMARY"], "a") as fh:
            fh.write(text)
    failed = base_result == "fail"
    for name, sha, cs, _ in tests:
        if results[name] == "fail" and name != "auto-update":
            print(f"::warning::{cs['title']}: tests failed, held back (branch {name} kept for a look)")
    if failed:
        print("::error::the compatible updates failed CI: nothing landed. Look at the CI run, fix on main, the next run retries")
        return 1
    return 0


# ---------- cli ----------

def scratch_copy(root):
    base = Path(os.environ.get("XDG_CACHE_HOME", Path.home() / ".cache")) / "wgc-update-dry-run"
    base.mkdir(parents=True, exist_ok=True)
    tmp = Path(tempfile.mkdtemp(dir=base))
    files = run(["git", "ls-files", "-z"], root).stdout.split("\0")
    for f in files:
        if f and (root / f).is_file():
            (tmp / f).parent.mkdir(parents=True, exist_ok=True)
            shutil.copy2(root / f, tmp / f)
    run(["git", "init", "-q"], tmp)
    run(["git", "add", "-A"], tmp)
    run(["git", "-c", "user.name=dry", "-c", "user.email=dry@example.com", "-c", "commit.gpgsign=false", "commit", "-qm", "base", "--no-verify"], tmp)
    return tmp


def main(argv=None):
    ap = argparse.ArgumentParser(prog="update_deps.py", description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--root", default=str(ROOT), help="repository root (default: this checkout)")
    ap.add_argument("--min-age", type=int, default=MIN_AGE_DAYS, help="days a new version must be published (default 3)")
    sub = ap.add_subparsers(dest="cmd", required=True)
    c = sub.add_parser("compatible", help="cargo, npm, base image digests (+ Android libraries with --android)")
    c.add_argument("--android", action="store_true", help="also Android libraries and gradle.lockfile")
    c.add_argument("--dry-run", action="store_true", help="work on a scratch copy and show what would change")
    c.add_argument("--report", help="append the Markdown report to this file")
    s = sub.add_parser("separate", help="list separate candidates as JSON")
    s.add_argument("--limit", type=int, default=SEPARATE_LIMIT, help=f"at most this many, rotated daily (default {SEPARATE_LIMIT})")
    a = sub.add_parser("apply", help="apply one candidate from `separate`")
    a.add_argument("id")
    a.add_argument("--dry-run", action="store_true", help="work on a scratch copy and show what would change")
    a.add_argument("--report", help="append the Markdown report to this file")
    g = sub.add_parser("actions", help="GitHub Actions pins within the major (7 days old)")
    g.add_argument("--write", action="store_true", help="rewrite the workflow files")
    g.add_argument("--report", help="append the Markdown report to this file")
    w = sub.add_parser("changeset", help="write working-tree changes as JSON")
    w.add_argument("--title", required=True)
    w.add_argument("--out", required=True)
    p = sub.add_parser("ship", help="workflow only: commit, test, land, release")
    p.add_argument("--candidates", required=True, help="folder with base.json / separate-*.json")
    p.add_argument("--main-sha", required=True)
    p.add_argument("--timeout", type=int, default=120, help="minutes to wait for CI")
    p.add_argument("--release", action="store_true", help="start release.yml when an image file landed")
    p.add_argument("--weekly-rebuild", action="store_true", help="start release.yml without cache in any case")
    args = ap.parse_args(argv)

    root = Path(args.root).resolve()
    now = dt.datetime.now(dt.timezone.utc)
    cutoff = now - dt.timedelta(days=args.min_age)
    ign = load_ignores(root)

    if args.cmd == "separate":
        cands = separate_candidates(root, cutoff, now, ign)
        print(json.dumps(rotate(cands, now.timetuple().tm_yday, args.limit), indent=1))
        if len(cands) > args.limit:
            print(f"{len(cands)} candidates, {args.limit} per run (rotated daily)", file=sys.stderr)
        return 0
    if args.cmd == "changeset":
        Path(args.out).write_text(json.dumps(changeset(root, args.title)))
        return 0
    if args.cmd == "ship":
        return ship(args)

    rep = Report()
    if args.cmd == "actions":
        actions_update(root, now - dt.timedelta(days=ACTIONS_MIN_AGE_DAYS), rep, args.write)
        title = "GitHub Actions pins"
        if rep.updated and not args.write:
            rep.note("GITHUB_TOKEN may not change workflow files: apply by hand with `python3 scripts/update_deps.py actions --write`, commit, push")
    else:
        work = scratch_copy(root) if args.dry_run else root
        try:
            if args.cmd == "compatible":
                title = "Compatible updates"
                cargo_compatible(work, cutoff, rep, ign)
                npm_compatible(work, cutoff, rep, ign)
                docker_compatible(work, rep)
                if args.android and gradle_compatible_update(work, cutoff, rep, ign):
                    if args.dry_run:
                        rep.note("dry run: gradle.lockfile not refreshed")
                    else:
                        gradle_write_locks(work, rep)
            else:
                title = f"Candidate {args.id}"
                apply_candidate(work, args.id, cutoff, rep, ign)
            if args.dry_run:
                print(run(["git", "diff", "--stat"], work).stdout)
        finally:
            if args.dry_run:
                shutil.rmtree(work, ignore_errors=True)
    md = rep.markdown(title)
    print(md)
    if getattr(args, "report", None):
        with open(args.report, "a") as fh:
            fh.write(md + "\n")
    if args.cmd == "compatible" and not getattr(args, "dry_run", False):
        print("next: git diff   (in CI the workflow commits and tests this)", file=sys.stderr)
    elif getattr(args, "dry_run", False):
        print(f"next: python3 scripts/update_deps.py {args.cmd}{' ' + args.id if args.cmd == 'apply' else ''}   (without --dry-run writes the files)", file=sys.stderr)
    return 0


if __name__ == "__main__":
    try:
        sys.exit(main())
    except (RuntimeError, ValueError) as e:
        print(f"error: {e}", file=sys.stderr)
        print("hint: python3 scripts/update_deps.py --help", file=sys.stderr)
        sys.exit(1)
