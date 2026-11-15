#!/usr/bin/env bash
# Copies the WG Chores v2 deployment kit into HomeLab-FastPi. Never commits there, never touches a running service.
set -euo pipefail
KIT=$(cd "$(dirname "$0")" && pwd)
DEST=${WGC_PROJECTS:-/home/pi/Projects}

usage() {
  cat <<EOF
Usage: deploy/holy-grail/apply.sh [--dry-run | --apply | -h]

Puts the WG Chores v2 + demo deployment into HomeLab-FastPi ($DEST).
  --dry-run   (default) unified diff of every file that would change; writes nothing
  --apply     writes the files, creates the gitignored secrets/ folders and .env; no commit, no container started
  -h, --help  this text

New files: everything under deploy/holy-grail/Projects/ (same paths).
Inserted, once:
  Docker/CUSTOMIZATIONS.md                 sections WG-Chores-v2, WG-Chores-Demo (appended)
  Docker/_global/auto-update.conf          two auto rows after Audiobook-Requests
  Docker/grafana/prometheus/prometheus.yml targets in blackbox-public and blackbox-wan-public
  Docker/_global/checklist.md              two rows after Tech-Radar
  Docker/Authentik/README.md               provisioning line after AudioBookRequest
Env: WGC_PROJECTS (default /home/pi/Projects).
Tab completion: source deploy/holy-grail/completions/apply.sh.bash   (fish: apply.sh.fish)
Guide: docs/guides/go-live-holy-grail.md
EOF
}

usage_error() { echo "$1" >&2; echo "try: deploy/holy-grail/apply.sh --help" >&2; exit 2; }
(( $# <= 1 )) || usage_error "one option at most, got: $*"
mode=dry
case "${1:-}" in
  ""|--dry-run) mode=dry ;;
  --apply) mode=apply ;;
  -h|--help) usage; exit 0 ;;
  *) usage_error "unknown option: $1" ;;
esac
[[ -d $DEST/Docker ]] || { echo "no HomeLab-FastPi at $DEST (set WGC_PROJECTS)" >&2; exit 2; }

STAGE=$(mktemp -d); trap 'rm -rf "$STAGE"' EXIT

# Builds $STAGE/<path> for every target path that would change; prints the paths.
python3 - "$KIT" "$DEST" "$STAGE" <<'PY'
import pathlib, re, sys
kit, dest, stage = (pathlib.Path(a) for a in sys.argv[1:4])
snip = kit / "snippets"
out = {}

def current(rel):
    if rel in out:
        return out[rel]
    p = dest / rel
    return p.read_text() if p.exists() else None

def fail(msg):
    sys.exit(f"stopped: {msg}; nothing written")

for f in sorted((kit / "Projects").rglob("*")):
    if f.is_file():
        rel = str(f.relative_to(kit / "Projects"))
        if current(rel) != f.read_text():
            out[rel] = f.read_text()

def insert_after(rel, anchor, text, marker):
    old = current(rel)
    if old is None:
        fail(f"{rel} missing")
    if marker in old:
        return
    lines = old.splitlines(keepends=True)
    hit = [i for i, l in enumerate(lines) if re.match(anchor, l)]
    if len(hit) != 1:
        fail(f"{rel}: anchor {anchor!r} found {len(hit)} times")
    lines[hit[0] + 1:hit[0] + 1] = [text]
    out[rel] = "".join(lines)

customs = "Docker/CUSTOMIZATIONS.md"
old = current(customs)
if old is None:
    fail(f"{customs} missing")
if "\n## WG-Chores-v2\n" not in old:
    out[customs] = old.rstrip("\n") + "\n" + (snip / "CUSTOMIZATIONS.md").read_text()

insert_after("Docker/_global/auto-update.conf", r"notify\s+Audiobook-Requests\s",
             (snip / "auto-update.conf").read_text(), "WG-Chores-v2")
insert_after("Docker/_global/checklist.md", r"\| Tech-Radar \|",
             (snip / "checklist.md").read_text(), "| WG-Chores-v2 |")
insert_after("Docker/Authentik/README.md", r"AudioBookRequest: ",
             (snip / "authentik-readme.md").read_text(), "provision-wg-chores-sso.py")

prom = "Docker/grafana/prometheus/prometheus.yml"
text = current(prom)
if text is None:
    fail(f"{prom} missing")
lines = text.splitlines(keepends=True)
targets = (snip / "prometheus-targets.yml").read_text()
for job in ("blackbox-public", "blackbox-wan-public"):
    start = [i for i, l in enumerate(lines) if l.strip() == f'- job_name: "{job}"']
    if len(start) != 1:
        fail(f"{prom}: job {job} found {len(start)} times")
    end = next((i for i in range(start[0] + 1, len(lines)) if "- job_name:" in lines[i]), len(lines))
    if "wg-chores.holy-grail.ch" in "".join(lines[start[0]:end]):
        continue
    t = next((i for i in range(start[0], end) if lines[i].strip() == "- targets:"), None)
    if t is None:
        fail(f"{prom}: job {job} has no '- targets:' line")
    lines[t + 1:t + 1] = [targets]
if "".join(lines) != text:
    out[prom] = "".join(lines)

for rel, content in out.items():
    p = stage / rel
    p.parent.mkdir(parents=True, exist_ok=True)
    p.write_text(content)
    print(rel)
PY
[[ ${PIPESTATUS[0]:-0} == 0 ]] || exit 1

mapfile -t changed < <(cd "$STAGE" && find . -type f | sed 's#^\./##' | sort)

if [[ $mode == dry ]]; then
  for rel in "${changed[@]}"; do
    old="$DEST/$rel"; [[ -e $old ]] || old=/dev/null
    diff -u --label "a/$rel" --label "b/$rel" "$old" "$STAGE/$rel" || true
  done
  if (( ${#changed[@]} == 0 )); then echo "nothing to change: $DEST already has the kit"
  else echo; echo "${#changed[@]} file(s) would change in $DEST. Nothing written."; echo "next: deploy/holy-grail/apply.sh --apply   (with the owner's yes)"; fi
  exit 0
fi

for rel in "${changed[@]}"; do
  install -D -m 644 "$STAGE/$rel" "$DEST/$rel"
  echo "wrote $rel"
done
for d in WG-Chores-v2 WG-Chores-Demo; do
  install -d -m 700 "$DEST/Docker/$d/secrets"
  [[ -e $DEST/Docker/$d/.env ]] || { install -m 600 "$DEST/Docker/$d/.env.example" "$DEST/Docker/$d/.env"; echo "wrote Docker/$d/.env (gitignored)"; }
done
cat <<EOF

Written, not committed. Check and commit in HomeLab-FastPi:
  git -C $DEST status --short
  $DEST/Docker/_global/scripts/test_post_update_check.sh
  git -C $DEST add Docker/WG-Chores-v2 Docker/WG-Chores-Demo Docker/_global/post-update.d/WG-Chores-v2.sh Docker/_global/post-update.d/WG-Chores-Demo.sh Docker/CUSTOMIZATIONS.md Docker/_global/auto-update.conf Docker/_global/checklist.md
  git -C $DEST commit -m "WG-Chores-v2, WG-Chores-Demo, Global: compose, checks, auto-update rows"
  git -C $DEST add Docker/Traefik/traefik/dynamic/wg-chores-v2.yml && git -C $DEST commit -m "Traefik: wg-chores and wg-chores-demo routes without forward auth"
  git -C $DEST add Docker/Authentik/scripts/provision-wg-chores-sso.py Docker/Authentik/README.md && git -C $DEST commit -m "Authentik: WG Chores OIDC provider, group WG"
  git -C $DEST add Docker/grafana/prometheus/prometheus.yml && git -C $DEST commit -m "grafana: blackbox probes for wg-chores and wg-chores-demo"
Traefik hot-reloads the route now: docker logs --since 1m traefik 2>&1 | grep -i error
Probes go live with: docker restart prometheus   (go-live step 7, once both sites answer; HUP keeps the old single-file mount)
next: docs/guides/go-live-holy-grail.md step 3 (Authentik provisioning)
EOF
