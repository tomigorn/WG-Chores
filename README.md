# WG Chores

Shared household chore tracker: who did what, how long ago. Rust server, web app (PWA), Android app.

**Status: v2 in development.** The previous .NET Blazor version is at tag `v1-dotnet-blazor-server`.

## Run (skeleton)
```
cd server
PUBLIC_URL=http://localhost:8080 cargo run
curl localhost:8080/healthz
```
Config is by environment variables, see `server/src/config.rs`.

| Variable | Default |
|---|---|
| `PUBLIC_URL` | required (https, or http on localhost) |
| `LISTEN` | `0.0.0.0:8080` |
| `DATA_DIR` | `/data` (holds `wg-chores.db`, `secret.key` and `vapid.key` (PKCS#8 DER), all created if missing; keep the keys private, back up `vapid.key`: a new one means every browser has to enable notifications again) |
| `TZ` | `UTC` |
| `LOCAL_ACCOUNTS`, `OPEN_REGISTRATION`, `MEMBER_DIRECTORY` | `true`, `false`, `false` (`true`/`false`/`1`/`0`) |
| `NOTIFY_TIMES` | `14:00,19:00` (due checks, local time in `TZ`) |
| `PUSH_ALLOWED_HOSTS` | empty; comma-separated extra push service host suffixes (built in: `fcm.googleapis.com`, `push.services.mozilla.com`, `updates.push.services.mozilla.com`, `push.apple.com`, `notify.windows.com`) |
| `TRUSTED_PROXIES` | empty; comma-separated IPs/CIDRs of reverse proxies whose `X-Forwarded-For` is believed (rate limits use the real client IP) |
| `OIDC_ISSUER`, `OIDC_CLIENT_ID`, `OIDC_CLIENT_SECRET` or `_FILE` | all or none; secret must not be empty |
| `OIDC_NAME`, `OIDC_REQUIRED_GROUP`, `OIDC_ACCOUNT_URL` (http/https) | optional |

## Invite links
| What | Value |
|---|---|
| Create / list / cancel (any member) | `POST` / `GET /api/v1/households/{id}/invites`, `DELETE …/invites/{invite_id}` |
| Link | `<PUBLIC_URL>/invite/<token>`: one use, 7 days, max 20 open per household; the token is shown only at creation, stored as SHA-256 |
| Public preview | `GET /api/v1/invites/{token}` (404 for unknown or malformed) |
| Accept (logged in) | `POST /api/v1/invites/{token}/accept`: 410 `invite_used` / `invite_expired` / `invite_cancelled`; existing member: 200 `already_member`, token unused |
| Sign-up with a link | `POST /api/v1/auth/register` with `invite`: works with `OPEN_REGISTRATION=false`, needs `LOCAL_ACCOUNTS=true` |

## Push notifications (Web Push)
| What | Value |
|---|---|
| Server key | `GET /api/v1/push/key` → `{"key": …}` (VAPID public key, base64url; browser `applicationServerKey`) |
| Subscribe | `POST /api/v1/push/subscriptions` with the browser's `PushSubscription` JSON plus `lang` (`en`/`de`, default `en`): 204; https on port 443 at a known push service (`PUSH_ALLOWED_HOSTS` adds more), max 20 per user (oldest dropped); the same endpoint from another account moves to it |
| Unsubscribe | `DELETE /api/v1/push/subscriptions {"endpoint": …}`: own only, 404 otherwise |
| Toggles | `PATCH /api/v1/me {"notify_due": bool, "notify_done": bool}` (SSO accounts too) |
| Done by someone else | sent right after a new completion to the other members' devices; payload `{title, body, url, tag}`, tag `done-<completion id>` |
| Due | at each `NOTIFY_TIMES`: chores due or overdue, once per due period per device (a newer completion re-arms), one push per household, tag `due-<household id>` |
| Delivery | TTL 12 h, urgency normal, aes128gcm, endpoint re-checked; 404/410 deletes the subscription, so do 5 failed deliveries in a row (other status codes, endpoint no longer allowed); network errors don't count |
| Body | due list cut to 3000 bytes with `…` |

## SSO (OIDC)
| What | Value |
|---|---|
| Redirect URI to register at the provider | `<PUBLIC_URL>/auth/oidc/callback` |
| Scopes requested | `openid profile email groups` |
| Client type | confidential, authorization code + PKCE (S256), ID token signed RS256 |
| Login start | `GET /auth/oidc/start` (optional `?invite=<token>`) |
| Errors | redirect to `/login?error=not_allowed` (group missing), `sso_failed`, `sso_unavailable` (retried after 30 s); details in the server log; a login started from an invite adds `&invite=<token>` |

Authentik: the provider needs a signing key (RS256; without one it signs HS256, which is refused) and the `groups` claim in the ID token (scope mapping for `groups`, "Include claims in id_token" on).
Two login tabs at once: only the one started last can finish; the other returns `sso_failed`.

Users are matched by issuer + `sub`; name, picture (https only) and username (`preferred_username`, if free) are refreshed at every login.

Fake provider for tests and the browser suite:
```
cargo run --features test-util --bin fake-oidc -- 8081
curl -X POST -H 'content-type: application/json' localhost:8081/_test/user \
  -d '{"sub":"u1","name":"Jane Doe","preferred_username":"jane","picture":null,"email":null,"groups":["WG"]}'
```
It prints the `OIDC_*` values to use.

## Web app (`web/`)
Svelte 5 + Vite + TypeScript, no UI or CSS framework. Node 24.

| Command (in `web/`) | What |
|---|---|
| `npm ci` | install |
| `npm run dev` | dev server on :5173; `/api` and `/auth` go to `WGC_SERVER` (default `http://localhost:8080`) |
| `npm test` | Vitest (due rules against `docs/test-vectors`, i18n keys, API client, router, theme, chore list logic, words, outbox, long press; components via `svelte/server` or mounted in happy-dom for `*.dom.test.ts` / `*.svelte.test.ts`) |
| `npm run check` | svelte-check (types), and tsc for `e2e/` |
| `npm run build` | `web/dist`, then `touch ../server/build.rs` and rebuild the server to embed it |
| `npm run e2e:build` | web build + `cargo build --features test-util --bins` (server with the web app embedded, plus `fake-oidc`); on fastpi prefix the cargo step with the rust-build flock |
| `npm run e2e` | browser suite (Playwright, Chromium only, about 3 min on fastpi); not part of `npm test` |
| `npm run e2e:layout` | only the layout matrix |

Browser suite (`web/e2e/`):

| What | Detail |
|---|---|
| Projects | `desktop`, `iphone` (iPhone 13 emulated in Chromium), `pixel` (Pixel 7), `layout`; WebKit is not installed, so real iOS Safari still needs a phone test |
| Servers | every spec file starts its own `wg-chores` (temp `DATA_DIR` under `~/.cache/claude-work/wgc-e2e/`, free port, `PUBLIC_URL=http://localhost:<port>`) and, for SSO, `fake-oidc`; data is seeded through the API |
| Specs | `auth`, `households`, `chores`, `invites` (sign-up via invite with `OPEN_REGISTRATION=false`, SSO from an invite), `offline` (queue, cached list, `resume_after_idle_offline_and_server_restart`), `push` (fake `PushManager`) |
| Layout | every screen at 320, 375, 414, 768, 1280 px (1280 with a desktop user agent), light + dark, root font 100 % and 200 % (plus German at 320 px), fresh identical data per width; screenshots in `~/.cache/claude-work/wgc-layout/` |
| Layout checks | `[data-nowrap]` (badges, dates, short values): one line, a cut one still shows ≥ 8 characters; `[data-clamp]` (names, titles): at most the clamp (2 lines), whole words, ≥ 8 characters; `[data-copy]` (UI text): never a word broken across lines; no sideways scroll; no overlapping controls (a control drawn on top of a larger one that holds it, like the tick on a row link, counts as nested); controls ≥ 40×40 px at 100 %; fixed bars never cover controls at the page end |
| Wraps by design | button labels, menu items and segmented controls may wrap onto a second line at large text; overlap and sideways scroll are still checked |
| Narrow screens | below 21em of container width (a small phone, or large text) names and titles take up to two lines, chore rows put who · when on its own line (below 16em also badge and tick), the top bar drops the avatar and chevron (account is in the menu), the install banner puts its text below the icon |
| Always failing on | any browser dialog, any uncaught page error |
| Playwright | `@playwright/test` pinned to the preinstalled Chromium build; never `playwright install`; traces off |

Dev with a local server:
```
cd server && PUBLIC_URL=http://localhost:8080 DATA_DIR=./data OPEN_REGISTRATION=true cargo run
cd web && npm run dev
```

| Topic | Detail |
|---|---|
| Routes | `/` (→ last household, else `/households`), `/login`, `/households` (`?new=1` opens the create form), `/households/:id`, `/households/:id/settings`, `/households/:id/new-chore`, `/chores/:id`, `/chores/:id/edit`, `/invite/:token`, `/settings`, `/account`; unknown → `/` |
| Chore page | last done (days, date, who, "N days overdue"), "Usually done every ~N days" (server median, hidden below 3 completions), history 20 per page, more on scroll; tap an entry = change date/note or delete (confirm); footer Done today / Other date…; read-only from the IndexedDB cache when offline |
| Add / edit chore | name, category (existing ones offered, or a new one), interval chips (tap again = none = every month); renaming a category for all its chores is a checkbox when the category text changes; archive with confirm, restore in household settings |
| Household settings | rename, members (add by exact username, or search when `MEMBER_DIRECTORY=true`: detected from `GET /api/v1/users` answering 404), remove (confirm), invite links (create → Copy / Share, list with creator and expiry, cancel), archived chores (Restore), leave (confirm; last member: deletes the household) |
| Invite page | public preview (household, inviter, expiry or why invalid); signed out: SSO (`/auth/oidc/start?invite=…`), sign in, create account when the server allows it (joins at once); signed in: Join → household |
| Account / settings | SSO: picture, name, link to `OIDC_ACCOUNT_URL`; local: change name and password; delete account (type username, SSO: display name); theme, language, notification toggles (`PATCH /api/v1/me`) |
| Chore list | grouped by category, categories and chores A–Z with `Intl.Collator` (accent/case-insensitive), no category last ("Other"); "today" = server `TZ` from `/api/v1/config`; refetch on tab visible and every 60 s while visible |
| Marking done | ✓ = done today at once (optimistic), bar 6 s with Undo / Other date; long press 500 ms (≤ 10 px movement), right click, context-menu key or Shift+F10 = date sheet (today, yesterday, 2 days ago, pick date ≤ today, note) |
| Offline | done marks go to an IndexedDB outbox (`wgc` → `outbox`, per user id) with a client UUID, sent at app start, on `online` and after each successful load; 4xx (not 401/429) drops the item with a message and reloads the list; sign out tries to send first and warns when marks would be lost, then clears that user's outbox and cache; top bar shows the waiting count; other edits offline say "Connect to the internet to save this"; last list per user and household cached in IndexedDB (`cache`) and shown offline |
| API client | `src/lib/api.ts`: always `X-WGC: 1`, same-origin cookie; `401 unauthenticated` → `/login` (back to the intended page after sign-in), network or gateway failure → `OfflineError`; never a "session expired" dialog |
| Language | setting in `localStorage` `wgc.lang`, else browser language (`de*` → German); texts in `src/lib/i18n/{en,de}.json` (same keys, Swiss "ss") |
| Theme | `localStorage` `wgc.theme` (`light`/`dark`, none = system); tokens incl. due colours in `src/app.css` |
| Icons | Lucide, copied paths only; licences in `web/THIRD_PARTY.md` |
| PWA | `public/manifest.webmanifest`; icons in `public/icons/` from `NODE_PATH=<dir with playwright> node scripts/icons.mjs` (192, 512, maskable 512, Apple touch 180, monochrome badge 96) |
| Service worker | `src/sw.ts` + `src/sw/core.ts`, built by `sw-plugin.ts` to `/sw.js` (no hash, build fails if it would import another chunk) with the precache list and a content version injected; precaches the shell (index, assets, icons, manifest); navigation network-first, 3 s timeout or 5xx → cached `index.html`; never touches `/api`, `/auth`, `/healthz`; old `wgc-shell-*` caches dropped on activate; a new version waits for "Update available · Reload" (Reload sends skip-waiting to that waiting worker; already activated by another tab → the bar goes away, Reload just reloads; the first-visit claim is ignored); push → notification (`tag`, click focuses an open window and routes to `url`, else opens it) |
| Offline start | last `config` + `me` in `localStorage` `wgc.session` (cleared at sign-out and on any 401; a 401 also drops that user's cached lists but keeps waiting done marks), so a reload without network opens the cached list; refreshed on `online` |
| Install banner | top of the household and household list screens, never in the installed app: Android/desktop only when the browser offers install (`beforeinstallprompt`), iPhone/iPad Safari (iPadOS: Mac UA + touch) a 3-step guide sheet, other iPhone browsers "open in Safari first"; dismiss hides it 30 days (`wgc.install-dismissed`); `PLAY_STORE_URL` in `src/lib/install.ts` adds a Play link once set |
| Push | `src/lib/push.ts`: permission asked only when a toggle is turned on (same click), then subscribe with the server key and `POST`; both toggles off → unsubscribe + `DELETE`; prompt closed → "Notifications were not allowed"; denied → toggles show the account setting, can be switched off, note "Blocked in browser settings on this device"; iPhone tab → toggles disabled, "Install the app first"; at start a granted device re-posts (or drops) its subscription silently |

## Web app, headers, API doc
| Topic | Detail |
|---|---|
| Web app | `web/dist` is embedded at build time (`server/build.rs`); without a build a placeholder page is embedded in debug and test builds, so `cargo test` needs no Node; a release build fails when `web/dist/index.html` is missing unless `WGC_ALLOW_PLACEHOLDER=1` is set; after building the web app run `touch server/build.rs` once if a placeholder build came before |
| Routes | unknown GET/HEAD paths outside `/api`, `/auth`, `/healthz` serve `index.html` (SPA); a missing `/assets/...` is 404 |
| Cache | `/assets/*` immutable for a year; `index.html`, `sw.js`, `manifest.webmanifest` `no-cache`; API JSON `no-store` |
| Headers | strict CSP (no `unsafe-*`), `nosniff`, referrer, permissions, COOP, `X-Frame-Options: DENY` on every response; HSTS is the reverse proxy's job |
| Compression | gzip for text types (html, js, css, json, svg, webmanifest) of the embedded web files only, never API JSON or images/fonts |
| API doc | `GET /api/v1/openapi.json` = hand-kept `server/openapi.json`; the test `openapi_lists_all_routes` fails when a route or method is missing or extra |

## Admin commands
`wg-chores <command> --help` shows flags and examples. Commands other than `serve` read only `DATA_DIR` and `TZ` (plus the variables named below), not `PUBLIC_URL`.

| Command | What |
|---|---|
| `serve` | run the server (default) |
| `migrate-v1 --from v1.db --household <id\|name> --map old=user[,…] [--force]` | import ONE household from a copy of the v1 file (opened read-only, one transaction); `--map` is repeatable, matches v1 member names and history names case-insensitively, targets must be active v2 users (else nothing is written); unmapped names become former members; room → category (empty → "Other"); `DoneAt` without offset = UTC, day in `TZ`; a same-named v2 household refuses the import unless `--force` |
| `backup <file> [--force]` | online backup of `DATA_DIR/wg-chores.db` (safe while running); refuses to overwrite without `--force` |
| `create-user <username> [--display-name N] [--password-file F]` | local account, same rules as registration; password asked twice without echo, or from a file; works with `LOCAL_ACCOUNTS=false` but warns |
| `demo-reset` | needs `DEMO_MODE=true` and `DEMO_PASSWORD_FILE`; deletes ALL data, creates `reviewer`, household "Demo WG", 10 sample chores in 4 categories with history |

Shell completion: `server/completions/wg-chores.fish` (copy to `~/.config/fish/completions/`) and `wg-chores.bash` (source it).

## Operations
- Edit data through the app or API, not with a plain `sqlite3` client: the `categories` table has a unique index on `ulower(name)`, a function only the server registers, so writes to it fail elsewhere.
