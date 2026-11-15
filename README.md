# WG Chores

Shared household chore tracker: who did what, how long ago. One small Rust server (SQLite, web app built in) plus an installable web app (PWA, also on iPhone); native Android app in `android/`. The previous .NET Blazor version is at tag `v1-dotnet-blazor-server`.

| Doc | What |
|---|---|
| [docs/privacy.md](https://github.com/tomigorn/WG-Chores/blob/main/docs/privacy.md) | what is stored, where (every server also serves it at `/privacy`, and `docs/account-deletion.md` at `/account-deletion`) |
| [docs/account-deletion.md](https://github.com/tomigorn/WG-Chores/blob/main/docs/account-deletion.md) | how to delete an account |
| [docs/reference.md](https://github.com/tomigorn/WG-Chores/blob/main/docs/reference.md) | developer reference: API, push, Android app API, SSO details, web app internals, security, development |

## Android app
Source, build and signing: [android/README.md](https://github.com/tomigorn/WG-Chores/blob/main/android/README.md). Play Store:

| Doc | What |
|---|---|
| [docs/guides/play-store-first-release.md](https://github.com/tomigorn/WG-Chores/blob/main/docs/guides/play-store-first-release.md) | first release, step by step: create app, listing, app content, tests, production, updates |
| [docs/guides/android-signing-key.md](https://github.com/tomigorn/WG-Chores/blob/main/docs/guides/android-signing-key.md) | upload key: create, back up, reset |
| `android/store/` | listing texts (en, de), data safety and content rating answers, reviewer access template |

## Self-hosting
One container, data in the `/data` volume, port 8080 on 127.0.0.1, needs an https reverse proxy in front; HSTS is the proxy's job.

| Image tag | Use |
|---|---|
| `tomigorn/wg-chores:vX.Y.Z` (e.g. `v2.0.0`) | self-hosting: pin a release, update by changing the tag |
| `tomigorn/wg-chores:latest` | follows `main` (every merged change), for testing |
| `wg-chores:local` | your own build, until the image is on Docker Hub |

```
git clone https://github.com/tomigorn/WG-Chores.git && cd WG-Chores
# edit PUBLIC_URL (and TZ) in deploy/compose.example.yaml, then:
docker compose -f deploy/compose.example.yaml up -d
docker exec -it wg-chores /wg-chores create-user anna --display-name Anna
```
Then open `PUBLIC_URL`, sign in, create a household and share an invite link (Household → Settings → Invite).

| File | Use |
|---|---|
| `deploy/compose.example.yaml` | self-hosters: local accounts, hardened (read-only, no capabilities, 128 MB RAM limit), health check |
| `deploy/holy-grail/` | the author's server (SSO only) and the public demo on fastpi, as files for HomeLab-FastPi plus `apply.sh`: [docs/guides/go-live-holy-grail.md](https://github.com/tomigorn/WG-Chores/blob/main/docs/guides/go-live-holy-grail.md) |

Build the image (static binary on `scratch`, image about 6 MB disk space):
```
docker build -t wg-chores:local .                                  # runs web + server tests first
docker build --target test .                                       # tests only
docker build --build-arg CARGO_BUILD_JOBS=2 -t wg-chores:local .   # small machines
```
Base images are pinned by digest in the `Dockerfile` (`image:tag@sha256:…`); to update, take the tag's current digest from `docker buildx imagetools inspect <image:tag>`.

Secret files are read as uid 65035 (the container user): `chmod 644` the file, or `sudo chown 65035:65037` it and `chmod 400`. A bind-mounted `/data` folder instead of the volume needs `sudo chown 65035:65037 <folder>`.

### Configuration (environment)
| Variable | Default | Meaning |
|---|---|---|
| `PUBLIC_URL` | required | external address; https, or http on localhost |
| `TZ` | `UTC` | household dates and notification times |
| `LOCAL_ACCOUNTS` | `true` | username + password login |
| `OPEN_REGISTRATION` | `false` | anyone may sign up; invite links work either way |
| `MEMBER_DIRECTORY` | `false` | "Add member" lists every user of the server |
| `NOTIFY_TIMES` | `14:00,19:00` | due notification checks, local time in `TZ` (the Android app polls at these times) |
| `MIN_APP_VERSION` | `1.0.0` | oldest Android app version this server works with (`/api/v1/config` → `min_app_version`) |
| `OIDC_ISSUER`, `OIDC_CLIENT_ID`, `OIDC_CLIENT_SECRET` or `OIDC_CLIENT_SECRET_FILE` | off | SSO; all or none, the secret must not be empty; the issuer must be https (http only on `localhost` / `127.0.0.1`) |
| `OIDC_NAME` | `SSO` | button "Sign in with …" |
| `OIDC_REQUIRED_GROUP` | none | group (from the `groups` claim) needed to log in |
| `OIDC_ACCOUNT_URL` | none | link on the account page (http/https) |
| `DATA_DIR` | `/data` | `wg-chores.db`, `secret.key`, `vapid.key` (created if missing; keep private, back up `vapid.key`: a new one means every device enables notifications again) |
| `LISTEN` | `0.0.0.0:8080` | bind address |
| `TRUSTED_PROXIES` | empty | comma-separated IPs/CIDRs of reverse proxies whose `X-Forwarded-For` is believed (rate limits use the real client IP) |
| `PUSH_ALLOWED_HOSTS` | empty | extra push service host suffixes (built in: `fcm.googleapis.com`, `push.services.mozilla.com`, `updates.push.services.mozilla.com`, `push.apple.com`, `notify.windows.com`) |
| `DEMO_MODE` | `false` | on allows `demo-reset` (deletes all data) |
| `DEMO_PASSWORD_FILE` | none | file with the demo `reviewer` password (12+ characters) |
| `WGC_ALLOW_PLACEHOLDER` | unset | build time only: `1` allows a release build without `web/dist` |

Booleans: `true`/`false`/`1`/`0`. Empty = default.

### SSO setup
| Step | Value |
|---|---|
| Client | confidential, authorization code + PKCE |
| Redirect URI | `<PUBLIC_URL>/auth/oidc/callback` |
| Scopes | `openid profile email groups` |
| ID token | signed RS256 |
| Server env | `OIDC_ISSUER` (the provider's issuer URL), `OIDC_CLIENT_ID`, `OIDC_CLIENT_SECRET_FILE` |

Authentik: OAuth2/OpenID provider with a **signing key** (else it signs HS256, which is refused), a scope mapping for `groups` with "Include claims in id_token" on, redirect URI as above (strict); issuer = `https://<authentik>/application/o/<app-slug>/`. Optional group binding plus `OIDC_REQUIRED_GROUP`.

### Backup and restore
```
docker exec wg-chores /wg-chores backup /data/backup.db          # safe while running
docker cp wg-chores:/data/backup.db ./wg-chores-$(date +%F).db
docker cp wg-chores:/data/vapid.key ./vapid.key                  # once; keeps push working after a restore
```
Restore (container stopped):
```
docker compose -f deploy/compose.example.yaml stop
docker run --rm -v wg-chores_wg-chores-data:/data -v "$PWD":/b alpine \
  sh -c 'cp /b/wg-chores-2026-01-01.db /data/wg-chores.db && rm -f /data/wg-chores.db-wal /data/wg-chores.db-shm && chown 65035:65037 /data/wg-chores.db'
docker compose -f deploy/compose.example.yaml start
```
Container `wg-chores` and volume `wg-chores_wg-chores-data` are the names `deploy/compose.example.yaml` sets; with your own compose file check `docker ps` and `docker volume ls`.

### Migration from v1
Imports one household from a **copy** of the v1 SQLite file. The image has no shell, so the copy is removed with a throwaway container:
```
sqlite3 v1.db ".backup v1-copy.db" && sqlite3 v1-copy.db "PRAGMA journal_mode=DELETE;"   # consistent copy without a -wal file
docker cp v1-copy.db wg-chores:/data/v1.db
docker exec -it wg-chores /wg-chores create-user anna                       # each v2 user first (or let them sign in once via SSO)
docker exec wg-chores /wg-chores migrate-v1 --from /data/v1.db --household 'Flat Alpha' --map anna=anna,ben=ben
docker run --rm -v wg-chores_wg-chores-data:/data alpine rm /data/v1.db
```
Unmapped v1 names become former members; details under Admin commands.

## Admin commands
`wg-chores <command> --help` shows flags and examples. Commands other than `serve` read only `DATA_DIR` and `TZ` (plus the variables named below), not `PUBLIC_URL`.

| Command | What |
|---|---|
| `serve` | run the server (default) |
| `migrate-v1 --from v1.db --household <id\|name> --map old=user[,…] [--force]` | import ONE household from a copy of the v1 file (opened read-only, one transaction); `--map` is repeatable, matches v1 member names and history names case-insensitively, targets (lowercased) must be active v2 users (else nothing is written); unmapped names with dated history become former members, the rest are listed as "not imported"; room → category (empty → "Other"); `DoneAt` without offset = UTC, day in `TZ`; a same-named v2 household refuses the import unless `--force` |
| `backup <file> [--force]` | online backup of `DATA_DIR/wg-chores.db` (safe while running), written with mode 0600 (it holds password hashes); refuses to overwrite without `--force` |
| `create-user <username> [--display-name N] [--password-file F]` | local account, same rules as registration; password asked twice without echo, or from a file; works with `LOCAL_ACCOUNTS=false` but warns |
| `revoke-sessions <username \| display name>` or `--user-id <id>` | delete every session of that user (signed out everywhere at once); after removing someone from the SSO group; an SSO user without username is found by exact display name (several with that name: refused, ids listed) |
| `healthcheck` | `GET /healthz` on the local `LISTEN` port (loopback for `0.0.0.0`/`::`), exit 0 on 200, else 1; for container health checks (the image has no shell or curl) |
| `demo-reset` | needs `DEMO_MODE` on and `DEMO_PASSWORD_FILE`; deletes ALL data, creates `reviewer`, household "Demo WG", 10 sample chores in 4 categories with history |

Shell completion: `server/completions/wg-chores.fish` (copy to `~/.config/fish/completions/`) and `wg-chores.bash` (source it).

## Releases
GitHub Actions: `ci.yml` (tests on pull requests), `release.yml` (tests, then the multi-arch image `tomigorn/wg-chores`: `latest` + `sha-…` on every push to `main` and weekly, `vX.Y.Z` on version tags); Dependabot (`.github/dependabot.yml`) keeps dependencies and base image digests current; minor, patch and digest updates merge automatically after CI. Setup and rollback: [docs/guides/docker-hub-release.md](https://github.com/tomigorn/WG-Chores/blob/main/docs/guides/docker-hub-release.md).
Author's server (fastpi, Holy Grail SSO + public demo): kit in `deploy/holy-grail/`, steps in [docs/guides/go-live-holy-grail.md](https://github.com/tomigorn/WG-Chores/blob/main/docs/guides/go-live-holy-grail.md).
Development, API and internals: [docs/reference.md](https://github.com/tomigorn/WG-Chores/blob/main/docs/reference.md).
