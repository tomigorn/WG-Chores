# WG Chores Android

Builds run on beefy (x86_64, KVM) in Docker, driven from fastpi. Nothing is installed on beefy's host. Release artifacts are built unsigned on beefy and signed on fastpi.

## Build

| Command | Does |
|---|---|
| `android/scripts/beefy-build.sh assembleDebug` | build the app in `android/` |
| `android/scripts/beefy-build.sh --emulator connectedCheck` | instrumented tests on a headless emulator |
| `android/scripts/beefy-build.sh bundleRelease assembleRelease` | unsigned release AAB + APK (sign them on fastpi, below) |
| `android/scripts/beefy-build.sh --self-test` | build env self-test: smoke app builds, its Compose test passes on the emulator |
| `android/scripts/beefy-build.sh --help` | all options (`--project`, `--head`, `--allow-untracked`, `--dry-run`) |
| `android/scripts/test-beefy-build.sh` | offline checks: arguments, secret guard, completion |

Artifacts (APK/AAB, reports, `screenshots/<package>.png` of the app on the emulator, `emulator.log`): `~/.cache/claude-work/wgc-android/latest/` (last 10 runs kept).

Completion:
- fish: `ln -s $PWD/android/scripts/completions/beefy-build.fish ~/.config/fish/completions/beefy-build.sh.fish`
- bash: `source android/scripts/completions/beefy-build.bash` (e.g. in `~/.bashrc`)

## App
| Part | What |
|---|---|
| Stack | Kotlin, Compose Material 3, Navigation Compose, OkHttp + kotlinx.serialization, DataStore, androidx.browser; versions in `gradle/libs.versions.toml` |
| IDs | `ch.tomigorn.wgchores`, minSdk 26, targetSdk 36, compileSdk 37 |
| Version | `versionName` = `server/Cargo.toml` version (`beefy-build.sh` passes `-Pwgc.version`), `versionCode` = major·10000 + minor·100 + patch |
| Server | https only (debug builds also `http://localhost`, `127.0.0.1`, `10.0.2.2` for tests); probe `GET /api/v1/config` (5 s, JSON only, max 64 KiB), must answer `name: "WG Chores"`; "Change server" wipes the session |
| Update | app older than `min_app_version` (fresh or cached config, also offline) → "Please update the app" with the Play link |
| Sign-in | local form and/or SSO; SSO via Auth Tab (`wgchores` scheme, PKCE), Custom Tab + the `.SsoCallback` intent filter only on browsers without Auth Tab; that alias is enabled just for the Custom Tab, disabled on return, and the only way a `wgchores://auth` intent is accepted |
| Fallback alias | exported only while a Custom Tab sign-in is open; another app can then at most send a wrong code and force a retry (the code is useless without the PKCE verifier) |
| Start | last household (`/me` → `last_household_id`, cached for offline starts), else "My households" (list, create); Settings, Account, chore page and Add chore are placeholders for now |
| Chore list | grouped by category, categories and chores A–Z (`java.text.Collator`, primary strength, numbers by value), sticky headers; ✓ = done today, snackbar Undo / Other date (6 s); long press = sheet Today / Yesterday / 2 days ago / Pick date (max today in the server `tz`) + note; below 16 em width (small phone or large font) the row stacks |
| Due rule | `domain/Due.kt`, checked against `docs/test-vectors/*.json` |
| Offline | Room `wgc.db`: `pending_marks`, `pending_deletes` (Undo of a mark the server may have), `cache` (last list per household); per account (server + user id); client UUID, retries are idempotent; refused (4xx except 401/429) or older than 30 days → dropped with a notice; sent on app start, network back, each loaded list; "N waiting" chip; other edits offline → "Connect to the internet to save this"; sign-out with waiting marks asks first, then drops them |
| Refresh | pull to refresh, on resume, every 60 s while shown |
| Token | bearer per device, in DataStore, AES-GCM with a key in the Android Keystore, decrypted once and kept in memory; Keystore failure → not stored, calm error; no backup or device transfer (`data_extraction_rules.xml`) |
| Requests | 15 s timeout, answers above 4 MiB refused, JSON content type checked case-insensitively; no answer, a stalled body or a gateway error → offline; `401 unauthenticated` → login, server kept; any other failure → generic error, never a crash |
| Texts | `values/strings.xml` (en) + `values-de/`, wording from `web/src/lib/i18n`; per-app language from the generated locale config |
| Look | colours from `web/src/app.css` (`ui/theme/Theme.kt`), no dynamic colour, system font; icons: Lucide (ISC, `web/THIRD_PARTY.md`) |

## Tests
| Command | Does |
|---|---|
| `android/scripts/beefy-build.sh testDebugUnitTest` | JVM tests (Robolectric) incl. screenshot check |
| `android/scripts/beefy-build.sh recordRoborazziDebug` | record screenshots; copy them from `latest/app/build/outputs/screenshots/` to `app/src/test/screenshots/` |
| `android/scripts/beefy-build.sh verifyRoborazziDebug` | compare with the goldens in `app/src/test/screenshots/`; diffs in `latest/app/build/outputs/roborazzi/` |
| `android/scripts/beefy-build.sh --emulator connectedDebugAndroidTest` | sign-in, SSO callback alias, chore list (✓, Undo, date sheet, offline mark synced once after a restart) on the emulator against a fake server (`app/src/sharedTest/`) |

Screenshots: 320 dp wide, font 1.0 and 2.0, light and dark. `versionCode` needs minor and patch below 100 (the build fails otherwise).

## How it works
1. One run at a time: lock on fastpi (`~/.cache/claude-work/wgc-android/.lock`) and on beefy (`~/build/wg-chores/.lock`).
2. beefy asleep → wake via the Beefy-Waker (`POST :9001/wake`, header `X-Wake: 1`), never plain `ssh beefy`.
3. Sets `/run/beefy-keep-awake` (content `wgc-build <run>`) plus a 3 h watchdog on beefy; removed on every exit incl. Ctrl-C/HUP/TERM; a failed removal is a loud error. A marker someone else set is left alone.
4. Only `android/` and `docs/test-vectors/` (due rule vectors shared with server and web) are shipped: working tree incl. untracked, not ignored files (`--head` = committed HEAD) → `git bundle` → `~/build/wg-chores/<run>/` on beefy, removed at the end of the run. Untracked `*.jks`, `*.keystore`, `*.pem`, `.env*`, `*secret*`, `keystore.properties` abort the run (`--allow-untracked` overrides).
5. Image `wgc-android-build:local` from `build-env/Dockerfile`, rebuilt when `Dockerfile` or `run-in-container.sh` change (label `wgc.env-hash`; built with `--network host`).
6. Container `wgc-android-build-<run>`: uid 10501, `--cpus 6`, `--memory 20g`, `--cap-drop ALL`, `TZ` of fastpi, network `wgc-build` (beefy's default bridge cannot resolve names), Gradle cache in volume `wgc-gradle-cache`, `--device /dev/kvm` with `--emulator`. Removed on cleanup; leftovers of a killed run are removed by the next run.

Night window: beefy runs its own jobs 03:55–04:45 (auto-update 04:12, backup 04:26). A build may run then (`--cpus 6` of 12 keeps it moderate); avoid image rebuilds in that window.

## Signing (fastpi only)
Upload key and password file stay in `~/secrets/wg-chores/` on fastpi (mode 600, backed up to KeePassXC); no keystore ever goes to beefy. fastpi has no JDK, so signing runs in the pinned JDK container (arm64 from the same multi-arch digest).

One-time: copy `apksigner.jar` out of the build image.
```
mkdir -p ~/.local/share/wg-chores
ssh beefy-direct 'sudo docker run --rm --entrypoint cat wgc-android-build:local /opt/android-sdk/build-tools/36.0.0/lib/apksigner.jar' > ~/.local/share/wg-chores/apksigner.jar
```

Sign (in the folder with the unsigned artifacts; keystore/alias names as created with the keystore):
```
J=eclipse-temurin:21-jdk@sha256:0a3f329821dcadf54b2a3033a15e2e9f007069a9db43d7f51897e6b80e726b07
RUN="docker run --rm --user $(id -u):$(id -g) -v $HOME/.local/share/wg-chores:/tools:ro -v $HOME/secrets/wg-chores:/keys:ro -v $PWD:/w -w /w $J"
$RUN java -jar /tools/apksigner.jar sign --ks /keys/upload.jks --ks-pass file:/keys/upload.pass --ks-key-alias upload --out app-release.apk app-release-unsigned.apk
$RUN java -jar /tools/apksigner.jar verify --print-certs app-release.apk
$RUN jarsigner -keystore /keys/upload.jks -storepass:file /keys/upload.pass -sigalg SHA256withRSA -digestalg SHA-256 -signedjar app-release-signed.aab app-release.aab upload
```
APK: `apksigner` (v2/v3 signatures); AAB: `jarsigner` (what Play expects for the upload key). Both checked with a throwaway key on fastpi 2026-11-15.

## Image
| Part | Version |
|---|---|
| Base | `eclipse-temurin:21-jdk` pinned by digest (Ubuntu 26.04), `tzdata` |
| cmdline-tools | 15859902 (sha256 checked) |
| SDK | platform-tools, `platforms;android-36`, `platforms;android-37.0`, `build-tools;36.0.0`, emulator, `system-images;android-36;google_apis;x86_64` |
| Size | 9.9 GB disk space on beefy (2.9 GB compressed) |

SDK levels: targetSdk 36 = Play's requirement since 2026-08-31 (developer.android.com/google/play/requirements/target-sdk); compileSdk 37 because Compose 1.12 (BOM 2026.08.00+) needs it. Emulator runs API 36.

## Smoke project
`build-env/smoke/`: Compose hello world + one instrumented test; compileSdk 37, targetSdk 36, minSdk 26, Compose BOM 2026.09.00, AGP 9.5.1, Kotlin 2.4.21, Gradle 9.9.1 (wrapper, sha256 pinned).

## Timings (beefy, 2026-11-15)
| Step | Time |
|---|---|
| Image build, cold | 224 s |
| Emulator boot | 28–36 s |
| Self-test, first run (empty Gradle cache) | 121 s total, Gradle 85 s |
| Self-test, warm | 75–80 s total, Gradle 40–67 s |
| `assembleDebug`, warm | 11 s total, Gradle 9 s |
