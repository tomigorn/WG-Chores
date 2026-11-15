#!/usr/bin/env bash
# Build the Android app on beefy inside the wgc-android-build image, from fastpi.
set -uo pipefail

REMOTE=${WGC_BEEFY_HOST:-beefy-direct}
WAKER_URL=${WGC_BEEFY_WAKER:-http://192.168.1.2:9001}
REMOTE_BASE=build/wg-chores
IMAGE=wgc-android-build:local
CACHE_VOLUME=wgc-gradle-cache
BUILDER_UID=10501
CPUS=${WGC_BUILD_CPUS:-6}
MEMORY=${WGC_BUILD_MEMORY:-20g}
LOCAL_OUT_ROOT=${WGC_ANDROID_OUT:-$HOME/.cache/claude-work/wgc-android}
MARKER=/run/beefy-keep-awake
WATCHDOG_SECONDS=${WGC_WATCHDOG_SECONDS:-10800}
SSH_OPTS=(-o BatchMode=yes -o ConnectTimeout=5 -o ServerAliveInterval=15 -o ServerAliveCountMax=4)

usage() {
    cat <<'EOF'
Usage: beefy-build.sh [options] <gradle args…>
       beefy-build.sh --self-test

Builds android/ on beefy in Docker (image wgc-android-build:local) and copies
APKs/AABs, test reports and screenshots back to ~/.cache/claude-work/wgc-android/latest/.
Only android/ is shipped to beefy; no keystore ever goes there.

Options:
  --emulator          start a headless emulator (KVM) for connected tests
  --project DIR       Gradle project inside android/ (default: android)
  --head              build the committed HEAD (default: working tree incl. untracked files)
  --allow-untracked   ship even if untracked files look like secrets (*.jks, .env*, …)
  --self-test         build the smoke project and run its instrumented test
                      (= --emulator --project android/build-env/smoke assembleDebug connectedCheck)
  --dry-run           print the plan, do not contact beefy
  -h, --help          this help

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
allow_untracked=0
while [ $# -gt 0 ]; do
    case $1 in
        -h|--help) usage; exit 0 ;;
        --emulator) emulator=1 ;;
        --head) head_only=1 ;;
        --dry-run) dry_run=1 ;;
        --self-test) self_test=1 ;;
        --allow-untracked) allow_untracked=1 ;;
        --project)
            [ $# -ge 2 ] && [ -n "$2" ] || die "--project needs a directory" "beefy-build.sh --project android assembleDebug"
            project=${2%/}; shift ;;
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
case $project in
    android|android/*) ;;
    *) die "--project must be inside android/ (only android/ is shipped to beefy)" "beefy-build.sh --project android assembleDebug" ;;
esac

repo=$(git -C "$(dirname "$0")" rev-parse --show-toplevel 2>/dev/null) || die "not inside the WG-Chores git repo"

# the app's versionName comes from server/Cargo.toml, which is not shipped to beefy
if [ "$project" = android ] && ! printf '%s\n' "${gradle_args[@]}" | grep -q '^-Pwgc\.version='; then
    version=$(sed -n 's/^version = "\(.*\)"/\1/p' "$repo/server/Cargo.toml" 2>/dev/null | head -1)
    [ -n "$version" ] && gradle_args+=("-Pwgc.version=$version")
fi

if [ "$head_only" = 0 ]; then
    suspicious=$(git -C "$repo" ls-files -o --exclude-standard -z -- android | while IFS= read -r -d '' f; do
        case ${f##*/} in
            *.jks|*.keystore|*.pem|.env*|*secret*|keystore.properties) echo "$f" ;;
        esac
    done)
    if [ -n "$suspicious" ] && [ "$allow_untracked" = 0 ]; then
        die "secret-like untracked file $(head -1 <<<"$suspicious") would be shipped to beefy" \
            "add it to android/.gitignore, or rerun with --allow-untracked if it is harmless"
    fi
fi

if [ "$dry_run" = 1 ]; then
    echo "host: $REMOTE"
    echo "ships: android/"
    echo "project: $project"
    echo "emulator: $([ "$emulator" = 1 ] && echo yes || echo no)"
    echo "source: $([ "$head_only" = 1 ] && echo "HEAD" || echo "working tree")"
    echo "gradle: ${gradle_args[*]}"
    echo "artifacts: $LOCAL_OUT_ROOT/latest"
    exit 0
fi

[ -f "$repo/$project/gradlew" ] || die "no gradlew in $project" "beefy-build.sh --project <dir with gradlew> …"

ssh_q() { ssh "${SSH_OPTS[@]}" "$REMOTE" "$@"; }

mkdir -p "$LOCAL_OUT_ROOT"
exec 8>"$LOCAL_OUT_ROOT/.lock"
if ! flock -n 8; then
    log "another beefy-build is running on fastpi, waiting (max 2 h)"
    flock -w 7200 8 || die "still locked after 2 h" "ps aux | grep beefy-build" 1
fi

git -C "$repo" for-each-ref --format='%(refname)' refs/wgc-build | xargs -r -n1 git -C "$repo" update-ref -d
rm -f "$LOCAL_OUT_ROOT"/src-*.bundle

run_id=$(date +%Y%m%d-%H%M%S)-$$
run_dir=$REMOTE_BASE/$run_id
container=wgc-android-build-$run_id
token="wgc-build $run_id"

marker_created=0
watchdog_pid=
remote_dir_created=0
remote_started=0
remote_pid=
snapshot_ref=
bundle=
cleanup() {
    local rc=$?
    trap - EXIT HUP INT TERM
    [ -n "$remote_pid" ] && kill "$remote_pid" 2>/dev/null
    if [ "$remote_started" = 1 ]; then
        ssh_q "sudo docker rm -f $container" >/dev/null 2>&1
    fi
    if [ "$remote_dir_created" = 1 ]; then
        ssh_q "sudo rm -rf ~/$run_dir" || echo "beefy-build: WARNING could not remove ~/$run_dir on beefy" >&2
    fi
    if [ "$marker_created" = 1 ]; then
        if ssh_q "[ -n '$watchdog_pid' ] && kill -- -$watchdog_pid 2>/dev/null
                  if [ \"\$(cat $MARKER 2>/dev/null)\" = '$token' ]; then sudo rm -f $MARKER; fi
                  ! { [ -e $MARKER ] && [ \"\$(cat $MARKER 2>/dev/null)\" = '$token' ]; }"; then
            log "keep-awake marker removed"
        else
            {
                echo
                echo "################################################################"
                echo "beefy-build: ERROR could not remove $MARKER on beefy"
                echo "  beefy stays awake until the 3 h watchdog removes it"
                echo "  next: ssh beefy-direct sudo rm -f $MARKER"
                echo "################################################################"
            } >&2
            [ "$rc" = 0 ] && rc=1
        fi
    fi
    [ -n "$snapshot_ref" ] && git -C "$repo" update-ref -d "$snapshot_ref" 2>/dev/null
    [ -n "$bundle" ] && rm -f "$bundle"
    exit "$rc"
}
trap cleanup EXIT
trap 'exit 129' HUP
trap 'exit 130' INT
trap 'exit 143' TERM

ensure_awake() {
    ssh_q true 2>/dev/null && return 0
    log "beefy is asleep, waking it via the Beefy-Waker"
    curl -fsS --max-time 15 -X POST -H 'X-Wake: 1' "$WAKER_URL/wake" >/dev/null ||
        die "the Beefy-Waker at $WAKER_URL did not answer" "curl -s $WAKER_URL/status" 1
    local deadline=$(( $(date +%s) + 240 ))
    while [ "$(date +%s)" -lt "$deadline" ]; do
        ssh_q true 2>/dev/null && { log "beefy is up"; return 0; }
        sleep 5
    done
    die "beefy did not come up within 4 min" "curl -s $WAKER_URL/status; ssh beefy-direct true" 1
}

started=$(date +%s)
ensure_awake

answer=$(ssh_q "if [ -e $MARKER ]; then echo existed; else
    echo '$token' | sudo tee $MARKER >/dev/null || exit 1
    setsid nohup sh -c 'sleep $WATCHDOG_SECONDS; [ \"\$(cat $MARKER 2>/dev/null)\" = \"$token\" ] && sudo rm -f $MARKER' </dev/null >/dev/null 2>&1 &
    echo \"created \$!\"; fi")
case $answer in
    created\ *) marker_created=1; watchdog_pid=${answer#created }; log "keep-awake marker set (watchdog removes it after 3 h)" ;;
    existed) log "keep-awake marker already set by someone else, leaving it alone" ;;
    *) die "could not set the keep-awake marker" "ssh beefy-direct sudo touch $MARKER" 1 ;;
esac

tmp_dir=$(mktemp -d)
export GIT_INDEX_FILE=$tmp_dir/index
if [ "$head_only" = 1 ]; then
    sub=$(git -C "$repo" rev-parse HEAD:android) &&
        tree=$(printf '040000 tree %s\tandroid\n' "$sub" | git -C "$repo" mktree)
else
    git -C "$repo" add -A -- android && tree=$(git -C "$repo" write-tree)
fi
rc=$?
[ "$rc" = 0 ] && commit=$(git -C "$repo" commit-tree "$tree" -m "beefy-build snapshot")
rc=$?
unset GIT_INDEX_FILE
rm -rf "$tmp_dir"
[ "$rc" = 0 ] || die "could not snapshot android/" "beefy-build.sh --head …" 1

snapshot_ref=refs/wgc-build/snapshot-$run_id
git -C "$repo" update-ref "$snapshot_ref" "$commit"
bundle=$(mktemp "$LOCAL_OUT_ROOT/src-XXXXXX.bundle")
git -C "$repo" bundle create -q "$bundle" "$snapshot_ref" || die "git bundle failed" "" 1
log "android/ snapshot bundled ($(du -h "$bundle" | cut -f1) transfer)"

remote_dir_created=1
ssh_q "mkdir -p $run_dir" && scp -q "${SSH_OPTS[@]}" "$bundle" "$REMOTE:$run_dir/src.bundle" ||
    die "copying the bundle to beefy failed" "" 1

tz=$(timedatectl show -p Timezone --value 2>/dev/null || cat /etc/timezone 2>/dev/null)
remote_args=$(printf '%q ' "$REMOTE_BASE" "$run_id" "$snapshot_ref" "$IMAGE" "$CACHE_VOLUME" "$BUILDER_UID" \
    "$CPUS" "$MEMORY" "$container" "${tz:-Europe/Zurich}" "$project" "$emulator" "${gradle_args[@]}")
remote_started=1
# in the background so a signal reaches the trap at once instead of after the build
ssh "${SSH_OPTS[@]}" "$REMOTE" "bash -s -- $remote_args" <<'REMOTE_SCRIPT' &
set -uo pipefail
base=$HOME/$1; run=$2; ref=$3; image=$4; volume=$5; uid=$6; cpus=$7; memory=$8; container=$9; tz=${10}; project=${11}; emulator=${12}
shift 12
dir=$base/$run
log() { printf '[%s] beefy: %s\n' "$(date +%T)" "$*"; }

exec 9>"$base/.lock"
if ! flock -n 9; then
    log "another build runs on beefy, waiting (max 2 h)"
    flock -w 7200 9 || exit 12
fi
sudo docker ps -aq --filter label=wgc-android-build | xargs -r sudo docker rm -f >/dev/null
find "$base" -mindepth 1 -maxdepth 1 -type d ! -name "$run" -exec sudo rm -rf {} +

mkdir -p "$dir/out"
git init -q "$dir/src"
git -C "$dir/src" fetch -q "$dir/src.bundle" "$ref" && git -C "$dir/src" -c advice.detachedHead=false checkout -q FETCH_HEAD || exit 10
rm -f "$dir/src.bundle"

env_dir=$dir/src/android/build-env
hash=$(cat "$env_dir/Dockerfile" "$env_dir/run-in-container.sh" | sha256sum | cut -c1-16)
have=$(sudo docker image inspect -f '{{index .Config.Labels "wgc.env-hash"}}' "$image" 2>/dev/null)
if [ "$have" != "$hash" ]; then
    log "building $image (env $hash)"
    t=$(date +%s)
    # BuildKit cannot use a user-defined network and the default bridge has no DNS on beefy
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
sudo docker run --rm --name "$container" --label wgc-android-build=1 \
    --cpus "$cpus" --memory "$memory" -e TZ="$tz" \
    --cap-drop ALL --security-opt no-new-privileges --network wgc-build \
    "${kvm[@]}" \
    -v "$dir/src:/work" -v "$dir/out:/out" -v "$volume:/home/builder/.gradle" \
    "$image" "$project" "$emulator" "$@"
REMOTE_SCRIPT
remote_pid=$!
wait "$remote_pid"
rc=$?
remote_pid=

local_out=$LOCAL_OUT_ROOT/$run_id
mkdir -p "$local_out"
ssh_q "sudo tar -C $run_dir/out -cf - ." | tar -C "$local_out" -xf -
copy=("${PIPESTATUS[@]}")
if [ "${copy[0]}" != 0 ] || [ "${copy[1]}" != 0 ]; then
    echo "beefy-build: WARNING copying the artifacts back failed (ssh ${copy[0]}, tar ${copy[1]})" >&2
    [ "$rc" = 0 ] && rc=1
fi
ln -sfn "$run_id" "$LOCAL_OUT_ROOT/latest"
ls -1d "$LOCAL_OUT_ROOT"/2* 2>/dev/null | head -n -10 | xargs -r rm -rf

echo
log "total $(( $(date +%s) - started )) s, exit $rc"
echo "Artifacts: $LOCAL_OUT_ROOT/latest/"
find "$local_out" \( -name '*.apk' -o -name '*.aab' \) -printf '  %P\n' 2>/dev/null
find "$local_out" -path '*/reports/androidTests/connected/*' -name index.html -printf '  %P\n' 2>/dev/null | head -3
find "$local_out" -name '*.png' -printf '  %P\n' 2>/dev/null | head -5
if [ "$rc" = 0 ]; then
    echo "  next: sign a release on fastpi (android/README.md, Signing), or beefy-build.sh --emulator connectedCheck"
else
    echo "  next: read $LOCAL_OUT_ROOT/latest/ (reports, emulator.log), fix, rerun the same command"
fi
exit "$rc"
