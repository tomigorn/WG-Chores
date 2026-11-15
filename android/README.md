# WG Chores Android

Builds run on beefy (x86_64, KVM) in Docker, driven from fastpi. Nothing is installed on beefy's host. Release artifacts are built unsigned on beefy and signed on fastpi.

## Build

| Command | Does |
|---|---|
| `android/scripts/beefy-build.sh assembleDebug` | build the app in `android/` |
| `android/scripts/beefy-build.sh --emulator connectedCheck` | instrumented tests on a headless emulator |
| `android/scripts/beefy-build.sh bundleRelease assembleRelease` | unsigned release AAB + APK (sign them on fastpi, below) |
| `android/scripts/beefy-build.sh --self-test` | build env self-test: smoke app builds, its Compose test passes on the emulator |
| `android/scripts/beefy-build.sh --help` | all options (`--project`, `--head`, `--allow-untracked`, `--write-locks`, `--dry-run`) |
| `android/scripts/test-beefy-build.sh` | offline checks: arguments, secret guard, completion |

Artifacts (APK/AAB, reports, `screenshots/<package>.png` of the app on the emulator, `emulator.log`): `~/.cache/claude-work/wgc-android/latest/` (last 10 runs kept).

Completion:
- fish: `ln -s $PWD/android/scripts/completions/beefy-build.fish ~/.config/fish/completions/beefy-build.sh.fish`
- bash: `source android/scripts/completions/beefy-build.bash` (e.g. in `~/.bashrc`)

## App
| Part | What |
|---|---|
| Stack | Kotlin, Compose Material 3, Navigation Compose, OkHttp + kotlinx.serialization, DataStore, androidx.browser, AppCompat (per-app language on Android 8–12), WorkManager; versions in `gradle/libs.versions.toml` |
| IDs | `ch.tomigorn.wgchores`, minSdk 26, targetSdk 36, compileSdk 37 |
| Version | `versionName` = `server/Cargo.toml` version (`beefy-build.sh` passes `-Pwgc.version`), `versionCode` = major·10000 + minor·100 + patch |
| Server | https only (debug and the `minified` check build also `http://localhost`, `127.0.0.1`, `10.0.2.2` for tests, flag `BuildConfig.ALLOW_LOCAL_HTTP`); probe `GET /api/v1/config` (5 s, JSON only, max 64 KiB response size), must answer `name: "WG Chores"`; "Change server" wipes the session |
| Update | app older than `min_app_version` (fresh or cached config, also offline) → "Please update the app" with the Play link |
| Sign-in | local form and/or SSO; SSO via Auth Tab (`wgchores` scheme, PKCE), Custom Tab + the `.SsoCallback` intent filter only on browsers without Auth Tab; after the provider the server asks "Sign in to the WG Chores app?" (one tap, form POST, 303 to `wgchores://auth?code=…`); Cancel or closing the tab sends nothing back, the login screen stays ready for another try |
| Fallback alias | `.SsoCallback` points at `SsoCallbackActivity` (no UI, not exported itself): it hands the URI to the app in memory, opens `MainActivity`, disables the alias and finishes. Enabled only while a Custom Tab sign-in is open, disabled again on return or the next app start; another app can then at most send a wrong code and force a retry (useless without the PKCE verifier). Callbacks are ignored once a token is stored or when no sign-in was started here |
| Start | last household (`/me` → `last_household_id`, cached for offline starts), else "My households" (list, create, gear = household settings, "Join with an invite link": paste → preview → Join; every URL in the pasted text is tried, default ports and trailing punctuation ignored, links of another server are refused) ; the list keeps its scroll position when you come back from a household (kept in `HomeViewModel`, survives rotation, not process death) |
| Chore page | tap a chore name: category · interval, last done (days box in the due colour, "N days overdue"), "Usually done every ~N days", history newest first with notes (20 per page, more on scroll, cached for offline); tap an entry = sheet to change date/note or delete (with confirm; offline the delete waits in the outbox, a later refusal says "Couldn't delete the entry from <date>"); footer Done today (fills the row) + a square calendar button "Other date…" as tall as it (through the outbox) |
| Add / edit chore | name, category (suggestion chips or a new name; "rename for all N chores" when shared: the chore is saved first, then the others are renamed; a failed rename says the chore is saved), interval chips in the web order, tap the picked chip to unpick ("Nothing picked = every month"), archive with confirm |
| Household settings | rename; members (add by exact username; every refusal says "No user with that name can be added.", or search when the server's member directory answers `/users` with 200/400; 404 or errors = off, asked again when the network returns), remove with confirm; invite links: create → Copy (marked sensitive on Android 13+) / Share (Android share sheet), list with creator and "expires in N days", cancel; archived chores with Restore; leave (last member: delete confirm, `confirm_delete`) |
| Invite links | no App Link or custom scheme for invites (App Links can't be declared for arbitrary self-hosted domains); users paste the link in "My households" |
| Account | picture (https, max 512 KiB download size, 8 MiB RAM cache (LRU); 4xx or undecodable pictures are not asked again, network errors are; else initials), name, username; SSO: "Manage your account" (Custom Tab); local: display name, password (current + new ×2, min 12); signed-in devices with Sign out (this device = normal sign-out); Sign out; Delete account (local: password, SSO: typed display name) → local data and the token key wiped → server picker |
| Settings | theme, language (system / English / Deutsch, `AppCompatDelegate.setApplicationLocales`), notification switches saved with `PATCH /me`, one request at a time, a failure goes back to the last server answer; /me not loaded → Try again; switches show off while the phone does not allow notifications, turning one on asks for the permission (Android 13+), a hint below says why (never asked / explain / only the system settings, with a button that opens them), re-read on every resume; privacy and deletion pages of the server (Custom Tab); last line "App 2.0.0 · Server 2.0.0" (server version from the cached config; unknown = only App; Server moves to a second line when it does not fit) |
| Notifications | WorkManager, no Firebase: one unique one-time work `notify-check` at the next server notify time (`/api/v1/config` `notify_times` in the server `tz`, DST-safe via `java.time`, independent of the phone's zone; unknown `tz` → the phone's zone + log warning, same as "today" and the due levels), network required, backoff 15 min (3 retries, then the next time is queued anyway); the check queues the next one behind itself (APPEND_OR_REPLACE); app start, sign-in, config or switch changes re-plan (same plan = KEEP, so a process started for the check doesn't cancel it); both switches off or "Please update the app" → cancelled (cursors kept); sign-out / server picker → cancelled, notifications and cursors cleared. Check: send waiting marks, `GET /me` (switches; both off → stop), phone blocks notifications → no poll (nothing acknowledged), `GET /notifications/poll?since=<cursor>` up to 5 pages while `more`. Shown: due → one per household ("Clean toilet is due" / "2 chores are due", texts as the web push), done by others → one per household ("Since 14:00: Sam ✓ Wipe stove, …"); a notification still in the shade is extended (chore names / done lines kept in its extras, the first "since" stays), a dismissed one starts fresh. Channels "Chores due" and "Done by others", renamed with the app language. Language: Android 13+ per-app language, older versions the tag Settings stored (DataStore `notify`). The cursor (per account) is stored only after the page was shown (= acknowledgement): delivery is at least once, a crash between showing and storing alerts again but merges into the same notification. Tap → `MainActivity` (immutable PendingIntent, action + account + household extras), opened only for the signed-in account and a household in its cached lists or the server's list |
| Chore list | grouped by category, categories and chores A–Z (`java.text.Collator`, primary strength, numbers by value), sticky headers; ✓ = done today, snackbar Undo / Other date (6 s); long press = sheet Today / Yesterday / 2 days ago / Pick date (max today in the server `tz`) + note; below 16 em width (small phone or large font) the row stacks; back from a chore page keeps the scroll position (also after a refresh or activity restart); top bar: when the household name would show fewer than 9 characters, chip, menu and avatar move to a second row and the name may take 2 lines (only between words) |
| Due rule | `domain/Due.kt`, checked against `docs/test-vectors/*.json` |
| Offline | Room `wgc.db` (schema version 2, JSON in `app/schemas/` (also debug assets for `RoomMigrationTest`), exported on every build and copied back to `latest/app/build/outputs/room-schemas/`; commit it with each schema change): `pending_marks`, `pending_deletes` (Undo of a mark the server may have, or an entry deleted on the chore page: `entry_on`), `cache` (last list per household, last chore page); per account (server + user id); client UUID, retries are idempotent; refused (4xx except 401/429) or older than 30 days → dropped with a notice (own text for a lost Undo); a mark the server answers 410 `deleted` (undone elsewhere meanwhile) counts as settled, no notice; a mark already tried is changed by PATCH, not in the queue (a resend would only replay the old date); sent on app start, network back, each loaded list; "N waiting" chip; other edits offline → "Connect to the internet to save this"; sign-out with waiting marks asks first, then drops them |
| Refresh | pull to refresh, on resume, every 60 s while shown |
| Token | bearer per device, in DataStore, AES-GCM with a key in the Android Keystore, decrypted once and kept in memory; Keystore failure → not stored, calm error; no backup or device transfer (`data_extraction_rules.xml`); "Change server" and account deletion also delete the Keystore key |
| Requests | 15 s timeout, answers above 4 MiB response size refused, JSON content type checked case-insensitively; no answer, a stalled body or a gateway error → offline; `401 unauthenticated` → login, server kept; any other failure → generic error, never a crash |
| Texts | `values/strings.xml` (en) + `values-de/`, wording from `web/src/lib/i18n`; per-app language from the generated locale config; name fields cut at the server's limits counted in code points (an emoji is never split) |
| Look | colours from `web/src/app.css` (`ui/theme/Theme.kt`), no dynamic colour, system font; icons: Lucide (ISC, `web/THIRD_PARTY.md`) |

## Tests
| Command | Does |
|---|---|
| `android/scripts/beefy-build.sh testDebugUnitTest` | JVM tests (Robolectric) incl. screenshot check |
| `android/scripts/beefy-build.sh recordRoborazziDebug` | record screenshots; copy them from `latest/app/build/outputs/screenshots/` to `app/src/test/screenshots/` |
| `android/scripts/beefy-build.sh verifyRoborazziDebug` | compare with the goldens in `app/src/test/screenshots/`; diffs in `latest/app/build/outputs/roborazzi/` |
| `android/scripts/beefy-build.sh --emulator connectedDebugAndroidTest` | sign-in, SSO callback alias, chore list (✓, Undo, date sheet, offline mark synced once after a restart), chore page (mark, change and delete entries), interval edit, invite share sheet, join by pasted link, account deletion, signing out another device, language switch, SSO in the emulator's Chrome (confirm page, form POST, 303 back to the app), background check via the WorkManager test driver (due + done notifications posted, tap opens the household), on the emulator against a fake server (`app/src/sharedTest/`) |
| `android/scripts/beefy-build.sh --emulator releaseDeviceCheck` | R8 check on the emulator (JVM test `device/ReleaseDeviceCheck.kt` drives adb, screen set to 1080×1920): the release APK (debug key, inside the container) refuses `http://`; the `minified` build (release code and R8 rules, debug key, local http; never upload it) signs in to the fake server with demo data, marks a chore done, opens sheet, chore page, settings, runs the background check (job forced via `cmd jobscheduler`: poll + due notification) and takes the 5 store screenshots (1080×1920; list shots end at a whole row) to `latest/app/build/outputs/device-check/screenshots/` (copy them to `store/screenshots/`). Only this task runs `ch.tomigorn.wgchores.device.*` |

## Dependency locks
`app/gradle.lockfile` pins every library of all app configurations (release, test, androidTest; Trivy scans it). Gradle fails a build whose resolved versions differ from it. Dependabot (`gradle`) updates it with each library bump; when it misses a transitive change, CI fails and the PR stays open: run `--write-locks` below, commit the lockfile onto the PR branch, merge by hand.

| Command | Does |
|---|---|
| `android/scripts/beefy-build.sh --write-locks` | after a change in `gradle/libs.versions.toml` or `app/build.gradle.kts`: resolves everything on beefy (`:app:dependencies assembleRelease testDebugUnitTest assembleDebugAndroidTest --write-locks`) and copies the new `app/gradle.lockfile` into `android/app/`; commit it with the change |

CI: job `android` in `.github/workflows/ci.yml` runs `testDebugUnitTest verifyRoborazziDebug assembleRelease` on every pull request (Dependabot's included; a stale lockfile fails it and blocks the auto-merge); the image release (`release.yml`) skips it.

Screenshots: 320 dp wide, font 1.0 and 2.0, light and dark. `versionCode` needs minor and patch below 100 (the build fails otherwise).

## How it works
1. One run at a time: lock on fastpi (`~/.cache/claude-work/wgc-android/.lock`) and on beefy (`~/build/wg-chores/.lock`).
2. beefy asleep → wake via the Beefy-Waker (`POST :9001/wake`, header `X-Wake: 1`), never plain `ssh beefy`.
3. Sets `/run/beefy-keep-awake` (content `wgc-build <run>`) plus a 3 h watchdog on beefy; removed on every exit incl. Ctrl-C/HUP/TERM; a failed removal is a loud error. A marker someone else set is left alone.
4. Only `android/` and `docs/test-vectors/` (due rule vectors shared with server and web) are shipped: working tree incl. untracked, not ignored files (`--head` = committed HEAD) → `git bundle` → `~/build/wg-chores/<run>/` on beefy, removed at the end of the run. Untracked `*.jks`, `*.keystore`, `*.pem`, `.env*`, `*secret*`, `keystore.properties` abort the run (`--allow-untracked` overrides).
5. Image `wgc-android-build:local` from `build-env/Dockerfile`, rebuilt when `Dockerfile` or `run-in-container.sh` change (label `wgc.env-hash`; built with `--network host`).
6. Container `wgc-android-build-<run>`: uid 10501, `--cpus 6`, `--memory 20g`, `--cap-drop ALL`, `TZ` of fastpi, network `wgc-build` (beefy's default bridge cannot resolve names), Gradle cache in volume `wgc-gradle-cache`, `--device /dev/kvm` with `--emulator`. Removed on cleanup; leftovers of a killed run are removed by the next run.

Night window: beefy runs its own jobs 03:55–04:45 (auto-update 04:12, backup 04:26). A build may run then (`--cpus 6` of 12 keeps it moderate); avoid image rebuilds in that window.

## Release build
```
android/scripts/beefy-build.sh --emulator bundleRelease releaseDeviceCheck
```
- R8 on (`isMinifyEnabled`, `isShrinkResources`, `app/proguard-rules.pro`), no signing config: `latest/app/build/outputs/bundle/release/app-release.aab` and `latest/app/build/outputs/apk/release/app-release-unsigned.apk`
- Sign them on fastpi (below) into `~/.cache/claude-work/wgc-android/release/wg-chores-<version>.aab` / `.apk`; never committed
- Store graphics: `NODE_PATH=<dir with playwright> node android/store/graphics.mjs <roboto-latin-600-normal.woff2>` (from `@fontsource/roboto`) writes `store/icon-512.png` and `store/feature-graphic.png`

## Signing (fastpi only)
Upload key and password file stay in `~/secrets/wg-chores/` on fastpi (mode 600, backed up to KeePassXC); no keystore ever goes to beefy. fastpi has no JDK, so signing runs in the pinned JDK container (arm64 from the same multi-arch digest).

One-time: copy `apksigner.jar` out of the build image.
```
mkdir -p ~/.local/share/wg-chores
ssh beefy-direct 'sudo docker run --rm --entrypoint cat wgc-android-build:local /opt/android-sdk/build-tools/36.0.0/lib/apksigner.jar' > ~/.local/share/wg-chores/apksigner.jar
```

Sign (from the repo root, after the release build):
```
V=$(sed -n 's/^version = "\(.*\)"/\1/p' server/Cargo.toml | head -1)
mkdir -p ~/.cache/claude-work/wgc-android/release && cd ~/.cache/claude-work/wgc-android/release
cp ../latest/app/build/outputs/apk/release/app-release-unsigned.apk ../latest/app/build/outputs/bundle/release/app-release.aab .
J=eclipse-temurin:21-jdk@sha256:0a3f329821dcadf54b2a3033a15e2e9f007069a9db43d7f51897e6b80e726b07
RUN="docker run --rm --user $(id -u):$(id -g) -v $HOME/.local/share/wg-chores:/tools:ro -v $HOME/secrets/wg-chores:/keys:ro -v $PWD:/w -w /w $J"
$RUN java -jar /tools/apksigner.jar sign --ks /keys/upload.jks --ks-pass file:/keys/upload.pass --ks-key-alias upload --out wg-chores-$V.apk app-release-unsigned.apk
$RUN java -jar /tools/apksigner.jar verify --print-certs wg-chores-$V.apk
$RUN jarsigner -keystore /keys/upload.jks -storepass:file /keys/upload.pass -sigalg SHA256withRSA -digestalg SHA-256 -signedjar wg-chores-$V.aab app-release.aab upload
$RUN jarsigner -verify wg-chores-$V.aab
rm app-release-unsigned.apk app-release.aab wg-chores-$V.apk.idsig
```
APK: `apksigner` (v2/v3 signatures); AAB: `jarsigner` (what Play expects for the upload key). Both checked with a throwaway key on fastpi 2026-11-15.

## Image
| Part | Version |
|---|---|
| Base | `eclipse-temurin:25-jdk` pinned by digest (Ubuntu 26.04), `tzdata`; signing on fastpi stays on the pinned `21-jdk` (Signing, above) |
| cmdline-tools | 15859902 (sha256 checked) |
| SDK | platform-tools, `platforms;android-36`, `platforms;android-37.0`, `build-tools;36.0.0`, emulator, `system-images;android-36;google_apis;x86_64` |
| Size | 9.9 GB disk space on beefy (2.9 GB compressed image size) |

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
