# WG Chores Android

Builds run on beefy (x86_64, KVM) in Docker, driven from fastpi. Nothing is installed on beefy's host.

## Build

| Command | Does |
|---|---|
| `android/scripts/beefy-build.sh assembleDebug` | build the app in `android/` |
| `android/scripts/beefy-build.sh --emulator connectedCheck` | instrumented tests on a headless emulator |
| `android/scripts/beefy-build.sh --self-test` | build env self-test: smoke app builds, its Compose test passes on the emulator |
| `android/scripts/beefy-build.sh --help` | all options (`--project`, `--head`, `--dry-run`) |
| `android/scripts/test-beefy-build.sh` | offline checks of the script's arguments |

Artifacts (APK/AAB, reports, `screenshots/`, `emulator.log`): `~/.cache/claude-work/wgc-android/latest/` (last 10 runs kept).

## How it works
1. beefy asleep → wake via the Beefy-Waker (`POST :9001/wake`), never plain `ssh beefy`.
2. Sets `/run/beefy-keep-awake`, removes it on every exit (trap); a marker someone else set is left alone.
3. Working tree (incl. untracked, not ignored files; `--head` = committed HEAD) → `git bundle` → `~/build/wg-chores/src` on beefy (scratch, recreated each run).
4. Image `wgc-android-build:local` from `build-env/Dockerfile`, rebuilt when `Dockerfile` or `run-in-container.sh` change (label `wgc.env-hash`).
5. Container: uid 10501, `--cpus 6`, `--memory 20g`, `--cap-drop ALL`, network `wgc-build` (beefy's default bridge cannot resolve names), Gradle cache in volume `wgc-gradle-cache`, `--device /dev/kvm` with `--emulator`.

## Image
| Part | Version |
|---|---|
| Base | `eclipse-temurin:21-jdk` pinned by digest (Ubuntu 26.04) |
| cmdline-tools | 15859902 (sha256 checked) |
| SDK | platform-tools, `platforms;android-36`, `build-tools;36.0.0`, emulator, `system-images;android-36;google_apis;x86_64` |
| Size | 9.7 GB disk space on beefy (2.9 GB compressed) |

API 36 = Play's target requirement since 2026-08-31 (developer.android.com/google/play/requirements/target-sdk). Compose BOM 2026.08.00+ (Compose 1.12) needs compileSdk 37; the smoke app stays on BOM 2026.06.01.

## Smoke project
`build-env/smoke/`: Compose hello world + one instrumented test. AGP 9.5.1, Kotlin 2.4.21, Gradle 9.9.1 (wrapper, sha256 pinned).

## Timings (beefy, 2026-11-15)
| Step | Time |
|---|---|
| Image build, cold | 227 s |
| Emulator boot | 29 s |
| Self-test, first run (empty Gradle cache) | 121 s total, Gradle 85 s |
| Self-test, warm | 75 s total, Gradle 40 s |
| `assembleDebug`, warm | 11 s total, Gradle 9 s |
