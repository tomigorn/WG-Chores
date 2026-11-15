#!/usr/bin/env bash
# Offline checks of beefy-build.sh argument handling (never contacts beefy).
set -u
here=$(cd "$(dirname "$0")" && pwd)
script="$here/beefy-build.sh"
fail=0

check() {
    local name=$1 want_rc=$2 want_text=$3; shift 3
    local got rc
    got=$("$script" "$@" 2>&1); rc=$?
    if [ "$rc" -ne "$want_rc" ] || ! grep -qF -- "$want_text" <<<"$got"; then
        echo "FAIL $name: rc=$rc (want $want_rc), output: $got"; fail=1
    else
        echo "ok   $name"
    fi
}

check "help" 0 "Usage: beefy-build.sh" --help
check "short help" 0 "--self-test" -h
check "no args" 2 "no gradle tasks"
check "unknown option" 2 "unknown option --bogus" --bogus assembleDebug
check "project without value" 2 "--project needs a directory" --project
check "dry run shows plan" 0 "gradle: assembleDebug" --dry-run assembleDebug
check "dry run emulator" 0 "emulator: yes" --dry-run --emulator connectedCheck
check "self-test plan" 0 "project: android/build-env/smoke" --dry-run --self-test
check "self-test tasks" 0 "gradle: assembleDebug connectedCheck" --dry-run --self-test
check "custom project" 0 "project: android/other" --dry-run --project android/other assembleDebug

exit $fail
