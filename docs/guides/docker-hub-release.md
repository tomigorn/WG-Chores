# Docker Hub release

The image `tomigorn/wg-chores` (amd64 + arm64) is built and pushed by GitHub Actions. You do steps 1 to 8 once.

| Workflow | Runs on | Does |
|---|---|---|
| `.github/workflows/ci.yml` | pull requests, by hand, called by Release (so every push to `main` and every tag is tested once) | server fmt + clippy + tests, web tests + types + build, browser suite (Chromium) |
| `.github/workflows/release.yml` | push to `main`, tag `v*`, Monday 03:23 UTC, by hand | CI first; then per architecture on its own runner: image tests, push without a tag, start exactly that image and run `wg-chores healthcheck`; only then the tags |
| `.github/workflows/dependabot-auto-merge.yml` | Dependabot pull requests | minor/patch/digest: auto-merge once checks are green, then starts Release; major: label `major` |

| Tag | When |
|---|---|
| `latest` | every build of `main` |
| `sha-<7 chars>` | every build (the commit) |
| `vX.Y.Z` | a git tag `vX.Y.Z` (must equal `version` in `server/Cargo.toml`, else the run fails) |

Without the two secrets (or in a fork) the release builds and tests but pushes nothing.

## 1. Docker Hub repository
1. Open https://hub.docker.com and sign in (or **Sign up**, username `tomigorn`).
2. Open https://hub.docker.com/repository/create
3. **Namespace**: `tomigorn`, **Repository Name**: `wg-chores`, **Visibility**: **Public**.
4. Click **Create**.

Another username than `tomigorn`: change `IMAGE` and `repository:` in `release.yml`.

## 2. Access token
1. Open https://app.docker.com/settings/personal-access-tokens
2. Click **Generate new token**.
3. **Access token description**: `github-actions-wg-chores`
4. **Expiration date**: 1 year (put a reminder in your calendar to renew it).
5. **Access permissions**: **Read & Write**.
6. Click **Generate**, copy the token (it is shown only once), save it in KeePassXC.

## 3. GitHub secrets
1. Open https://github.com/tomigorn/WG-Chores/settings/secrets/actions
2. Click **New repository secret**: **Name** `DOCKERHUB_USERNAME`, **Secret** `tomigorn`, **Add secret**.
3. Again: **Name** `DOCKERHUB_TOKEN`, **Secret** the token from step 2, **Add secret**.

Public repository: safe. Secrets are never handed to workflow runs from forks' pull requests, and the release never runs on pull requests.

## 4. Push
1. Check the visibility: https://github.com/tomigorn/WG-Chores/settings, section **Danger Zone**, it says "This repository is currently public".
2. Scan what you push for secrets. Which scan:
```
cd ~/Documents/development/WG-Chores
git fetch origin
git rev-parse --verify -q origin/main && echo "range scan" || echo "full scan"
```
| Answer | Command |
|---|---|
| `range scan` (GitHub already has a `main`; the usual case) | `gitleaks git --log-opts="origin/main..HEAD"` |
| `full scan` (first push, GitHub has no `main` yet) | `gitleaks git` |

3. Any finding: stop, do not push. No findings ("no leaks found"):
```
git push origin main
```

## 5. Watch the run
1. Open https://github.com/tomigorn/WG-Chores/actions
2. Click the newest **Release** run. Jobs in order: **Tests**, **Can push**, **Image amd64** + **Image arm64**, **Publish multi-arch tags**, **Docker Hub description**.
3. All green = done. The first run takes longest (no cache yet).
4. Red: click the job, read the failing step. **Docker Hub description** may fail when the token lacks permission: optional, it never blocks the release (needs **Read, Write, Delete** if you want it).

## 6. Check on Docker Hub
1. Open https://hub.docker.com/r/tomigorn/wg-chores/tags
2. `latest` and `sha-…` are there, each with **linux/amd64** and **linux/arm64**.
3. Or in a terminal:
```
docker buildx imagetools inspect tomigorn/wg-chores:latest
```

## 7. Dependabot (dependency updates)
Config: `.github/dependabot.yml`; merging: `.github/workflows/dependabot-auto-merge.yml`. Settings at https://github.com/tomigorn/WG-Chores/settings:

1. Renovate installed earlier? https://github.com/settings/installations → **Renovate** → **Configure** → under "Repository access" remove `WG-Chores` (or **Uninstall**) → **Save**.
2. **General** → section "Pull Requests" → tick **Allow auto-merge** (saves at once).
3. **Rules** → **Rulesets** → **New ruleset** → **New branch ruleset**:
   - **Ruleset Name**: `main`
   - **Enforcement status**: **Active**
   - **Bypass list** → **Add bypass** → tick **Repository admin** → **Add selected** (stays "Always allow": your own pushes and tags keep working)
   - **Target branches** → **Add target** → **Include default branch**
   - Leave **Restrict deletions** and **Block force pushes** ticked; leave **Require a pull request before merging** off
   - Tick **Require status checks to pass**; leave **Require branches to be up to date before merging** off; **Add checks**, type each name, pick the one from **GitHub Actions**:
     - `Server (fmt, clippy, test)`
     - `README fits Docker Hub`
     - `Web (test, check, build)`
     - `Browser suite (Chromium)`
     - `Android (JVM tests, screenshots, release build)`
   - **Create**
4. **Advanced Security** → **Dependabot alerts**: **Enable**; **Dependabot security updates**: **Enable**.
5. https://github.com/tomigorn/WG-Chores/labels → **New label** → name `major` → **Create label** (Dependabot and the workflow tag major PRs with it).
6. Check: https://github.com/tomigorn/WG-Chores/network/updates lists cargo, npm, gradle, docker, rust-toolchain, github-actions; **Check for updates** starts one at once.

| Update | What happens |
|---|---|
| minor, patch (one grouped PR per ecosystem), Docker digest, Rust toolchain | PR on Monday 04:00 (Zurich); auto-merge (squash) once all required checks are green; the workflow then starts **Release** |
| waiting time after a release | GitHub Actions 7 days, everything else 3 days (not for security updates) |
| major, and below 1.0 also a minor step (0.32 → 0.42) | own PR with label `major`, you merge it |
| `@playwright/test` | own PR, you merge it (fastpi's preinstalled Chromium must match the version) |
| `typescript` major | ignored: svelte-check 4 supports only TypeScript 5 and 6; drop the `ignore` in `.github/dependabot.yml` once it supports 7 |
| known vulnerability (cargo, npm, GitHub Actions) | security PR at once, any day; minor/patch ones auto-merge too |
| CI red (e.g. stale `android/app/gradle.lockfile`) | PR stays open; fix: `android/scripts/beefy-build.sh --write-locks`, commit the lockfile onto the PR branch, merge by hand |

A merge done by the workflow's token starts no push workflow, so the workflow starts **Release** itself (`gh workflow run`). It waits up to 80 min for the merge; later merges ship with the Monday release.

## 8. First version tag
v2 starts at `2.0.0` (the .NET v1 ended at 1.2.0). The tag must equal `version` in `server/Cargo.toml`.
1. The version is already `2.0.0` (commit "version bump to v2.0.0"). Check:
```
cd ~/Documents/development/WG-Chores
grep -m1 '^version' server/Cargo.toml          # shows: version = "2.0.0"
```
Another number: set it, update the lock file, commit with the fixed message, then rebuild and re-sign the Android files ([android/README.md](../../android/README.md#release-build)):
```
sed -i '0,/^version = ".*"/s//version = "1.1.0"/' server/Cargo.toml
(cd server && cargo update -p wg-chores --offline)
git commit -m "version bump to v1.1.0" -- server/Cargo.toml server/Cargo.lock
```
2. Tag (the tag must equal the version):
```
git tag -a v2.0.0 -m "WG-Chores v2.0.0"
```
3. Scan (see step 4 for which scan), then push the commit and the tag:
```
gitleaks git --log-opts="origin/main..HEAD"
git push origin main --follow-tags
```
4. Watch https://github.com/tomigorn/WG-Chores/actions: two **Release** runs start, one for `main` (moves `latest`) and one for `v2.0.0`. Both green = done.
5. Check https://hub.docker.com/r/tomigorn/wg-chores/tags: `v2.0.0` is there with **linux/amd64** and **linux/arm64**.

The `v2.0.0` run fails at **App version** when the tag and `server/Cargo.toml` differ. Fix: delete the tag (`git tag -d v2.0.0 && git push origin :refs/tags/v2.0.0`), fix the version, tag again.

## Roll back
1. In the compose file, pin an older tag instead of `latest`, e.g. `image: tomigorn/wg-chores:v2.0.0` or `image: tomigorn/wg-chores:sha-abc1234`.
2. `docker compose -f <compose file> up -d`
3. The database is migrated one way at start: an older image may not run on it. Then restore the backup from before the update (fastpi: `~/backups/pre-update/<date>/<project>/`).

Never delete tags on Docker Hub: they are the way back.

## How the image stays fresh
| What | How |
|---|---|
| Base images (`node`, `rust` in the `Dockerfile`; `eclipse-temurin` in `android/build-env/Dockerfile`) | pinned by digest; Dependabot moves the digest, auto-merge after CI, then **Release** |
| Weekly rebuild (Monday 03:23 UTC) | without cache: fresh Alpine packages in the build stage, so the CA bundle (the only OS file in the `scratch` image) stays current |
| Rust version (`rust-toolchain.toml`, used by CI) | Dependabot `rust-toolchain` PR, separate from the `rust` digest PR (the image uses the newest Rust 1.x of `rust:1-alpine`) |
| Rust crates, npm packages, Android libraries, Actions | Dependabot PRs, see step 7 |

## fastpi follows the image (at go-live, not yet applied)
| Step | Detail |
|---|---|
| Compose | `deploy/holy-grail/Projects/Docker/WG-Chores-v2/docker-compose.yaml` (and `WG-Chores-Demo`): `image: tomigorn/wg-chores:latest` (pull, no local build); go-live: [go-live-holy-grail.md](go-live-holy-grail.md) |
| Nightly update | 03:10 `auto_update.py` (HomeLab-FastPi `Docker/_global/`), one row in `auto-update.conf`; a new digest of `latest` is applied after a 24 h cool-down through `update-project.sh pull`: snapshot of compose/.env and the old image, backup, pull, recreate, wait, check every container is running and healthy, functional check, customization check, on failure the old image and the backup come back |
| Tier | the app migrates its database at start: `auto` row (owner 2026-11-15): backup `vol:wg-chores-v2_wg-chores-v2-data`, check `docker exec wg-chores-v2 /wg-chores healthcheck`, rollback on failure |
| Major versions | never applied by the nightly update: the image label `org.opencontainers.image.version` is the `server/Cargo.toml` version |
| Cup | lists `tomigorn/wg-chores` as soon as a container runs it; "update available" between a push to Docker Hub and fastpi's pull |
