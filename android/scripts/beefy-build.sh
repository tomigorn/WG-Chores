#!/usr/bin/env bash
# Build the Android app on beefy inside the wgc-android-build image, from fastpi.
set -uo pipefail

REMOTE=${WGC_BEEFY_HOST:-beefy-direct}
WAKER_URL=${WGC_BEEFY_WAKER:-http://192.168.1.2:9001}
REMOTE_DIR=build/wg-chores
IMAGE=wgc-android-build:local
CACHE_VOLUME=wgc-gradle-cache
BUILDER_UID=10501
CPUS=${WGC_BUILD_CPUS:-6}
MEMORY=${WGC_BUILD_MEMORY:-20g}
LOCAL_OUT_ROOT=${WGC_ANDROID_OUT:-$HOME/.cache/claude-work/wgc-android}
MARKER=/run/beefy-keep-awake

usage() {
    cat <<'EOF'
Usage: beefy-build.sh [options] <gradle args…>
       beefy-build.sh --self-test

Builds the repo on beefy in Docker (image wgc-android-build:local) and copies
APKs/AABs, test reports and screenshots back to ~/.cache/claude-work/wgc-android/latest/.

Options:
  --emulator        start a headless emulator (KVM) for connected tests
  --project DIR     Gradle project, relative to the repo root (default: android)
  --head            build the committed HEAD only (default: working tree incl. untracked files)
  --self-test       build the smoke project and run its instrumented test
                    (= --emulator --project android/build-env/smoke assembleDebug connectedCheck)
  --dry-run         print the plan, do not contact beefy
  -h, --help        this help

Examples:
  beefy-build.sh assembleDebug
  beefy-build.sh --emulator connectedCheck
  beefy-build.sh --self-test
EOF
}

die() { echo "beefy-build: $1" >&2; [ -n "${2:-}" ] && echo "  next: $2" >&2; exit "${3:-2}"; }
log() { printf '[%s] %s\n' "$(date +%T)" "$*"; }

emulator=0
project=android
head_only=0
dry_run=0
self_test=0
while [ $# -gt 0 ]; do
    case $1 in
        -h|--help) usage; exit 0 ;;
        --emulator) emulator=1 ;;
        --head) head_only=1 ;;
        --dry-run) dry_run=1 ;;
        --self-test) self_test=1 ;;
        --project)
            [ $# -ge 2 ] && [ -n "$2" ] || die "--project needs a directory" "beefy-build.sh --project android assembleDebug"
            project=$2; shift ;;
        --) shift; break ;;
        -*) die "unknown option $1" "beefy-build.sh --help" ;;
        *) break ;;
    esac
    shift
done
gradle_args=("$@")
if [ "$self_test" = 1 ]; then
    emulator=1
    project=android/build-env/smoke
    [ ${#gradle_args[@]} -gt 0 ] || gradle_args=(assembleDebug connectedCheck)
fi
[ ${#gradle_args[@]} -gt 0 ] || die "no gradle tasks given" "beefy-build.sh assembleDebug   (or --self-test)"

if [ "$dry_run" = 1 ]; then
    echo "host: $REMOTE"
    echo "project: $project"
    echo "emulator: $([ "$emulator" = 1 ] && echo yes || echo no)"
    echo "source: $([ "$head_only" = 1 ] && echo "HEAD" || echo "working tree")"
    echo "gradle: ${gradle_args[*]}"
    echo "artifacts: $LOCAL_OUT_ROOT/latest"
    exit 0
fi

repo=$(git -C "$(dirname "$0")" rev-parse --show-toplevel 2>/dev/null) || die "not inside the WG-Chores git repo"
[ -f "$repo/$project/gradlew" ] || die "no gradlew in $project" "beefy-build.sh --project <dir with gradlew> …"

ssh_q() { ssh -o BatchMode=yes -o ConnectTimeout=5 "$REMOTE" "$@"; }

ensure_awake() {
    ssh_q true 2>/dev/null && return 0
    log "beefy is asleep, waking it via the Beefy-Waker"
    curl -fsS --max-time 15 -X POST -H 'X-Wake: 1' "$WAKER_URL/wake" >/dev/null ||
        die "the Beefy-Waker at $WAKER_URL did not answer" "docker ps --filter name=Beefy-Waker" 1
    local deadline=$(( $(date +%s) + 240 ))
    while [ "$(date +%s)" -lt "$deadline" ]; do
        ssh_q true 2>/dev/null && { log "beefy is up"; return 0; }
        sleep 5
    done
    die "beefy did not come up within 4 min" "ssh beefy-direct true" 1
}

marker_created=0
snapshot_ref=
bundle=
cleanup() {
    if [ "$marker_created" = 1 ]; then
        if ssh_q "sudo rm -f $MARKER"; then
            log "keep-awake marker removed"
        else
            echo "beefy-build: WARNING could not remove $MARKER on beefy" >&2
            echo "  next: ssh beefy-direct sudo rm -f $MARKER" >&2
        fi
    fi
    [ -n "$snapshot_ref" ] && git -C "$repo" update-ref -d "$snapshot_ref" 2>/dev/null
    [ -n "$bundle" ] && rm -f "$bundle"
}
trap cleanup EXIT
trap 'exit 130' INT TERM

started=$(date +%s)
ensure_awake

case $(ssh_q "if [ -e $MARKER ]; then echo existed; else sudo touch $MARKER && echo created; fi") in
    created) marker_created=1; log "keep-awake marker set" ;;
    existed) log "keep-awake marker already set by someone else, leaving it alone" ;;
    *) die "could not set the keep-awake marker" "ssh beefy-direct sudo touch $MARKER" 1 ;;
esac

if [ "$head_only" = 1 ]; then
    commit=$(git -C "$repo" rev-parse HEAD)
else
    tmp_dir=$(mktemp -d)
    tmp_index=$tmp_dir/index
    export GIT_INDEX_FILE=$tmp_index
    git -C "$repo" read-tree HEAD && git -C "$repo" add -A &&
        tree=$(git -C "$repo" write-tree) &&
        commit=$(git -C "$repo" commit-tree "$tree" -p HEAD -m "beefy-build snapshot")
    rc=$?
    unset GIT_INDEX_FILE
    rm -rf "$tmp_dir"
    [ "$rc" = 0 ] || die "could not snapshot the working tree" "beefy-build.sh --head …" 1
fi
snapshot_ref=refs/wgc-build/snapshot-$$
git -C "$repo" update-ref "$snapshot_ref" "$commit"
mkdir -p "$LOCAL_OUT_ROOT"
bundle=$(mktemp "$LOCAL_OUT_ROOT/src-XXXXXX.bundle")
git -C "$repo" bundle create -q "$bundle" "$snapshot_ref" || die "git bundle failed" "" 1
log "source $(git -C "$repo" rev-parse --short "$commit") bundled ($(du -h "$bundle" | cut -f1) transfer)"

ssh_q "mkdir -p $REMOTE_DIR.in" && scp -q -o BatchMode=yes "$bundle" "$REMOTE:$REMOTE_DIR.in/src.bundle" ||
    die "copying the bundle to beefy failed" "" 1

remote_args=$(printf '%q ' "$REMOTE_DIR" "$snapshot_ref" "$IMAGE" "$CACHE_VOLUME" "$BUILDER_UID" "$CPUS" "$MEMORY" "$project" "$emulator" "${gradle_args[@]}")
ssh -o BatchMode=yes "$REMOTE" "bash -s -- $remote_args" <<'REMOTE_SCRIPT'
set -uo pipefail
dir=$HOME/$1; ref=$2; image=$3; volume=$4; uid=$5; cpus=$6; memory=$7; project=$8; emulator=$9
shift 9
log() { printf '[%s] beefy: %s\n' "$(date +%T)" "$*"; }

sudo rm -rf "$dir"
mkdir -p "$dir/out"
git init -q "$dir/src"
git -C "$dir/src" fetch -q "$dir.in/src.bundle" "$ref" && git -C "$dir/src" -c advice.detachedHead=false checkout -q FETCH_HEAD || exit 10
rm -rf "$dir.in"

env_dir=$dir/src/android/build-env
hash=$(cat "$env_dir/Dockerfile" "$env_dir/run-in-container.sh" | sha256sum | cut -c1-16)
have=$(sudo docker image inspect -f '{{index .Config.Labels "wgc.env-hash"}}' "$image" 2>/dev/null)
if [ "$have" != "$hash" ]; then
    log "building $image (env $hash)"
    t=$(date +%s)
    sudo docker build -q --network host --label "wgc.env-hash=$hash" -t "$image" "$env_dir" >/dev/null || exit 11
    log "image built in $(( $(date +%s) - t )) s"
    sudo docker image prune -f --filter label=wgc.env-hash >/dev/null
fi
log "image size $(sudo docker image inspect -f '{{.Size}}' "$image" | numfmt --to=iec) (disk space)"

sudo chown -R "$uid:$uid" "$dir/src" "$dir/out"
# the default bridge cannot resolve names on beefy, a user-defined network uses the embedded DNS
sudo docker network inspect wgc-build >/dev/null 2>&1 || sudo docker network create wgc-build >/dev/null
kvm=()
if [ "$emulator" = 1 ]; then
    kvm=(--device /dev/kvm --group-add "$(getent group kvm | cut -d: -f3)")
fi
sudo docker run --rm --name wgc-android-build \
    --cpus "$cpus" --memory "$memory" \
    --cap-drop ALL --security-opt no-new-privileges --network wgc-build \
    "${kvm[@]}" \
    -v "$dir/src:/work" -v "$dir/out:/out" -v "$volume:/home/builder/.gradle" \
    "$image" "$project" "$emulator" "$@"
REMOTE_SCRIPT
rc=$?

stamp=$(date +%Y%m%d-%H%M%S)
local_out=$LOCAL_OUT_ROOT/$stamp
mkdir -p "$local_out"
ssh_q "sudo tar -C $REMOTE_DIR/out -cf - ." | tar -C "$local_out" -xf - 2>/dev/null
ln -sfn "$stamp" "$LOCAL_OUT_ROOT/latest"
ls -1d "$LOCAL_OUT_ROOT"/2* 2>/dev/null | head -n -10 | xargs -r rm -rf

echo
log "total $(( $(date +%s) - started )) s, gradle exit $rc"
echo "Artifacts: $LOCAL_OUT_ROOT/latest/"
find "$local_out" \( -name '*.apk' -o -name '*.aab' \) -printf '  %P\n' 2>/dev/null
find "$local_out" -path '*/reports/androidTests/connected/*' -name index.html -printf '  %P\n' 2>/dev/null | head -3
find "$local_out" -name '*.png' -printf '  %P\n' 2>/dev/null | head -5
if [ "$rc" = 0 ]; then
    echo "  next: adb install $LOCAL_OUT_ROOT/latest/…/app-debug.apk, or beefy-build.sh --emulator connectedCheck"
else
    echo "  next: read $LOCAL_OUT_ROOT/latest/ (reports, emulator.log), fix, rerun the same command"
fi
exit "$rc"
