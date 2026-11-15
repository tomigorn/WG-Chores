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

check "project outside android" 2 "must be inside android/" --dry-run --project server assembleDebug
check "ships only android" 0 "ships: android/" --dry-run assembleDebug
check "ships the shared test vectors" 0 "ships: android/ docs/test-vectors/" --dry-run assembleDebug
ver=$(sed -n 's/^version = "\(.*\)"/\1/p' "$here/../../server/Cargo.toml" | head -1)
check "passes the app version" 0 "gradle: assembleDebug -Pwgc.version=$ver" --dry-run assembleDebug
check "explicit version wins" 0 "gradle: help -Pwgc.version=1.2.100" --dry-run help -Pwgc.version=1.2.100
check "smoke gets no version" 0 "gradle: assembleDebug connectedCheck" --dry-run --self-test
check "write locks default tasks" 0 "gradle: :app:dependencies assembleRelease testDebugUnitTest assembleDebugAndroidTest --write-locks" --dry-run --write-locks
check "write locks into the repo" 0 "locks: written to android/" --dry-run --write-locks
check "write locks with own tasks" 0 "gradle: :app:dependencies --write-locks" --dry-run --write-locks :app:dependencies
check "write locks not for the smoke app" 2 "--write-locks is for the app project only" --dry-run --self-test --write-locks

work=$(mktemp -d "${HOME}/.cache/claude-work/wgc-bbtest.XXXXXX")
trap 'rm -rf "$work"' EXIT
git init -q "$work/repo"
mkdir -p "$work/repo/android/scripts" "$work/repo/android/app"
cp "$script" "$work/repo/android/scripts/"
printf 'ignored.jks\n' > "$work/repo/android/.gitignore"
touch "$work/repo/android/app/ignored.jks"
script="$work/repo/android/scripts/beefy-build.sh"
check "ignored keystore is fine" 0 "ships: android/" --dry-run assembleDebug
touch "$work/repo/android/app/upload.jks"
check "untracked keystore aborts" 2 "secret-like untracked file android/app/upload.jks" --dry-run assembleDebug
check "allow untracked" 0 "ships: android/" --dry-run --allow-untracked assembleDebug
rm "$work/repo/android/app/upload.jks"
touch "$work/repo/android/.env.local"
check "untracked env aborts" 2 "secret-like untracked file android/.env.local" --dry-run assembleDebug
rm "$work/repo/android/.env.local"
script="$here/beefy-build.sh"

comp_bash() {
    # shellcheck source=/dev/null
    source "$here/completions/beefy-build.bash"
    COMP_WORDS=("$@"); COMP_CWORD=$(( ${#COMP_WORDS[@]} - 1 )); COMPREPLY=()
    _beefy_build_sh
    printf '%s\n' "${COMPREPLY[@]}"
}
check_comp() {
    local name=$1 want=$2; shift 2
    if comp_bash "$@" | grep -qxF -- "$want"; then echo "ok   $name"; else echo "FAIL $name: no $want in: $(comp_bash "$@" | tr '\n' ' ')"; fail=1; fi
}
check_comp "bash completes --self-test" --self-test beefy-build.sh --se
check_comp "bash completes --write-locks" --write-locks beefy-build.sh --wr
check_comp "bash bare tab lists options" --emulator beefy-build.sh ""
check_comp "bash bare tab lists tasks" assembleDebug beefy-build.sh ""
for t in testDebugUnitTest recordRoborazziDebug verifyRoborazziDebug connectedDebugAndroidTest releaseDeviceCheck; do
    check_comp "bash completes $t" "$t" beefy-build.sh ""
done
if command -v fish >/dev/null; then
    if fish -c "source $here/completions/beefy-build.fish; complete -C 'beefy-build.sh --se'" | grep -q -- '--self-test'; then
        echo "ok   fish completes --self-test"; else echo "FAIL fish completion"; fail=1; fi
    for want in --allow-untracked --write-locks testDebugUnitTest recordRoborazziDebug verifyRoborazziDebug connectedDebugAndroidTest releaseDeviceCheck; do
        if fish -c "source $here/completions/beefy-build.fish; complete -C 'beefy-build.sh '" | grep -q -- "^$want"; then
            echo "ok   fish bare tab lists $want"; else echo "FAIL fish bare tab: no $want"; fail=1; fi
    done
else
    echo "skip fish completion (fish not installed)"
fi

exit $fail
