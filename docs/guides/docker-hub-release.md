# Docker Hub release

The image `tomigorn/wg-chores` (amd64 + arm64) is built and pushed by GitHub Actions. You do steps 1 to 8 once.

| Workflow | Runs on | Does |
|---|---|---|
| `.github/workflows/ci.yml` | pull requests, by hand, started by Update for every candidate, called by Release (so every push to `main` and every tag is tested once) | server fmt + clippy + tests, web tests + types + build, browser suite (Chromium), Android; Node and JDK major taken from the Dockerfiles |
| `.github/workflows/release.yml` | push to `main`, tag `v*`, started by Update (after an image change landed; Mondays without cache), by hand | CI first; then per architecture on its own runner: image tests, push without a tag, start exactly that image and run `wg-chores healthcheck`; only then the tags |
| `.github/workflows/update.yml` | daily 01:37 UTC, by hand (**Run workflow**, options Android / dry run) | dependency updates without pull requests, see step 7 |

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

## 7. Automatic updates
`update.yml` + `scripts/update_deps.py`: updates are tested commits on branches `auto-update*`, never pull requests. What passes CI goes to `main` and Docker Hub; what fails stays out.

Once, at https://github.com/tomigorn/WG-Chores/settings:
1. Renovate installed earlier? https://github.com/settings/installations → **Renovate** → **Configure** → under "Repository access" remove `WG-Chores` (or **Uninstall**) → **Save**.
2. **Rules** → **Rulesets** → **MAIN** (https://github.com/tomigorn/WG-Chores/settings/rules/27331464): untick **Require status checks to pass**; leave **Restrict deletions** and **Block force pushes** ticked → **Save changes**. (The workflow token cannot be on the bypass list; CI runs on the candidate before `main` moves; your own pushes bypassed the checks anyway.)
3. **Advanced Security** → **Dependabot alerts**: leave **Enabled**; **Dependabot security updates**: **Disable** (the update job takes advisory fixes at once, without PRs).
4. **General** → section "Pull Requests" → untick **Allow auto-merge** (nothing uses it anymore).
5. Optional: https://github.com/tomigorn/WG-Chores/labels → `major` → **Delete**.
6. Failure mail: https://github.com/settings/notifications → section **System** → **Actions** → tick **Email** and **Only notify for failed workflows**. Mails for the scheduled run go to whoever last changed its `cron` line (you, with the push).
7. Check: https://github.com/tomigorn/WG-Chores/actions/workflows/update.yml → **Run workflow** → tick **dry run** → **Run workflow**. Green, and the run page lists what it would update.

| Update | What happens |
|---|---|
| cargo, npm within the major; base image digests; Android libraries (Mondays) | one commit "dependency updates" on `auto-update`; full CI (Android only when `android/` changed); green → `main` fast-forwards to it, **Release** starts |
| waiting time | new versions only 3 days after publishing (crates.io, npm, Maven dates); a version fixing an advisory (OSV: RustSec, GitHub) of the one in use at once; base image digests at once (rebuilt by Docker Official Images; a tag cannot pin an older digest) |
| majors (cargo, npm, below 1.0 also 0.32 → 0.42), `node` next LTS, `eclipse-temurin` next LTS, Rust toolchain | each its own commit on top (`auto-update-N`) with its own CI; up to 5 per run, rotated daily; the first green one lands, one per run; red ones stay out (warning, branch kept for a look) |
| `scripts/update-deps.ignore` | held back, each with a reason: `@playwright/test` (fastpi's Chromium must match), `typescript` major (svelte-check), `@types/node` major (moves with the `node` LTS step) |
| GitHub Actions pins | not automatic: the workflow token may not change workflow files. The run page lists them (7 days old, same major); by hand: `python3 scripts/update_deps.py actions --write`, commit, push. A vulnerable action still sends a Dependabot alert |
| Android majors, Gradle wrapper | by hand; Android majors are listed on the run page |
| compatible updates red | nothing lands, the run is red, you get the mail; fix on `main`, the next run retries |

| Command | Does |
|---|---|
| `python3 scripts/update_deps.py compatible --dry-run` | what the job would update today (scratch copy, nothing written) |
| `python3 scripts/update_deps.py separate` | today's separate candidates (JSON) |
| `python3 scripts/update_deps.py apply <id> --dry-run` | one candidate |
| `python3 -m unittest discover -s scripts -p 'test_*.py'` | unit tests |

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
| Base images (`node`, `rust` in the `Dockerfile`; `eclipse-temurin` in `android/build-env/Dockerfile`) | pinned by digest; Update moves the digest daily, CI, then **Release** |
| Weekly rebuild (Monday, after Update) | without cache: fresh Alpine packages in the build stage, so the CA bundle (the only OS file in the `scratch` image) stays current |
| Rust version (`rust-toolchain.toml`, used by CI) | Update, as a separate candidate (new clippy lints must not block the other updates); the image uses the newest Rust 1.x of `rust:1-alpine` |
| Rust crates, npm packages, Android libraries | Update, see step 7 |

## fastpi follows the image (at go-live, not yet applied)
| Step | Detail |
|---|---|
| Compose | `deploy/holy-grail/Projects/Docker/WG-Chores-v2/docker-compose.yaml` (and `WG-Chores-Demo`): `image: tomigorn/wg-chores:latest` (pull, no local build); go-live: [go-live-holy-grail.md](go-live-holy-grail.md) |
| Nightly update | 03:10 `auto_update.py` (HomeLab-FastPi `Docker/_global/`), one row in `auto-update.conf`; a new digest of `latest` is applied after a 24 h cool-down through `update-project.sh pull`: snapshot of compose/.env and the old image, backup, pull, recreate, wait, check every container is running and healthy, functional check, customization check, on failure the old image and the backup come back |
| Tier | the app migrates its database at start: `auto` row (owner 2026-11-15): backup `vol:wg-chores-v2_wg-chores-v2-data`, check `docker exec wg-chores-v2 /wg-chores healthcheck`, rollback on failure |
| Cool-down | each new `latest` digest restarts the 24 h cool-down: on days with a release fastpi waits for a day without one (open: HomeLab-FastPi `auto_update.py`) |
| Major versions | never applied by the nightly update: the image label `org.opencontainers.image.version` is the `server/Cargo.toml` version |
| Cup | lists `tomigorn/wg-chores` as soon as a container runs it; "update available" between a push to Docker Hub and fastpi's pull |
