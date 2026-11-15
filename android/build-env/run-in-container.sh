#!/usr/bin/env bash
# Entry point of wgc-android-build: run-in-container.sh <project dir> <0|1 emulator> <gradle args…>
set -uo pipefail

project=${1:?project dir}
emulator=${2:?0 or 1}
shift 2
out=/out
mkdir -p "$out"

log() { printf '[%s] %s\n' "$(date +%T)" "$*"; }

emu_pid=
stop_emulator() {
    [ -n "$emu_pid" ] || return 0
    adb emu kill >/dev/null 2>&1
    for _ in $(seq 30); do kill -0 "$emu_pid" 2>/dev/null || break; sleep 1; done
    kill -9 "$emu_pid" 2>/dev/null
    emu_pid=
}
trap stop_emulator EXIT

if [ "$emulator" = 1 ]; then
    [ -w /dev/kvm ] || { log "ERROR /dev/kvm not writable: run with --device /dev/kvm and the kvm group"; exit 3; }
    image="system-images;android-${ANDROID_API_LEVEL};google_apis;x86_64"
    echo no | avdmanager --silent create avd --force --name wgc --package "$image" --device pixel_6 >/dev/null
    start=$(date +%s)
    emulator -avd wgc -no-window -no-audio -no-boot-anim -gpu swiftshader_indirect -no-snapshot -no-metrics \
        -accel on -memory 4096 >"$out/emulator.log" 2>&1 &
    emu_pid=$!
    adb start-server >/dev/null 2>&1
    booted=0
    for _ in $(seq 360); do
        kill -0 "$emu_pid" 2>/dev/null || { log "ERROR emulator exited, see emulator.log"; exit 3; }
        if [ "$(adb shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')" = 1 ]; then booted=1; break; fi
        sleep 1
    done
    [ "$booted" = 1 ] || { log "ERROR emulator did not boot within 6 min"; exit 3; }
    log "emulator booted in $(( $(date +%s) - start )) s"
    echo "$(( $(date +%s) - start ))" > "$out/emulator-boot-seconds"
    for s in window_animation_scale transition_animation_scale animator_duration_scale; do
        adb shell settings put global "$s" 0
    done
fi

cd "/work/$project" || { log "ERROR no project at $project"; exit 2; }
start=$(date +%s)
./gradlew --no-daemon --console=plain "$@"
rc=$?
log "gradle finished with $rc after $(( $(date +%s) - start )) s"
echo "$(( $(date +%s) - start ))" > "$out/gradle-seconds"

if [ -n "$emu_pid" ]; then
    mkdir -p "$out/screenshots"
    apk=$(find . -path '*/build/outputs/apk/debug/*.apk' ! -name '*androidTest*' | head -1)
    aapt2=$(ls -d "$ANDROID_HOME"/build-tools/*/aapt2 | tail -1)
    if [ -n "$apk" ] && pkg=$("$aapt2" dump packagename "$apk" 2>/dev/null) && adb install -r "$apk" >/dev/null 2>&1; then
        adb shell monkey -p "$pkg" -c android.intent.category.LAUNCHER 1 >/dev/null 2>&1
        sleep 5
        adb exec-out screencap -p > "$out/screenshots/$pkg.png" 2>/dev/null
    else
        adb exec-out screencap -p > "$out/screenshots/device.png" 2>/dev/null
    fi
fi

find . -path '*/build/outputs' -o -path '*/build/reports' -o -path '*/build/test-results' | grep -v '/intermediates/' |
    while read -r d; do
        mkdir -p "$out/$(dirname "$d")"
        cp -r "$d" "$out/$(dirname "$d")/"
    done
exit "$rc"
