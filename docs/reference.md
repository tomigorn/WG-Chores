# WG Chores: developer reference

API, internals and development of server and web app. Running a server: [README](../README.md). Android app: [android/README.md](../android/README.md).

## Development
| Command | What |
|---|---|
| `cd server && cargo test` | Rust unit + API tests (no Node needed) |
| `cd server && cargo clippy --all-targets -- -D warnings` | lint |
| `cd web && npm ci && npm test && npm run check` | web unit tests, types |
| `cd web && npm run e2e:build && npm run e2e` | browser suite (Playwright); `WGC_BIN=<path>` runs it against another `wg-chores` binary, e.g. one copied out of the image |
| `cd server && PUBLIC_URL=http://localhost:8080 DATA_DIR=./data OPEN_REGISTRATION=true cargo run` | local server |

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
| Unsubscribe | `DELETE /api/v1/push/subscriptions {"endpoint": …}`: own only, 404 otherwise; `POST /api/v1/auth/logout {"push_endpoint": …}` drops it at sign-out (own only, body optional) |
| Toggles | `PATCH /api/v1/me {"notify_due": bool, "notify_done": bool}` (SSO accounts too) |
| Done by someone else | sent 10 s after a new completion to the other members' devices, only if it was not undone in the meantime (pending ones go out at once on shutdown); payload `{title, body, url, tag}`, tag `done-<completion id>` |
| Due | at each `NOTIFY_TIMES`: chores due or overdue, once per due period per device (a newer completion re-arms), one push per household, tag `due-<household id>`; also carries `names`, `household`, `lang` (left out when the message would get too big), so the web app extends a due notification still shown instead of replacing it, like the Android app |
| Delivery | TTL 12 h, urgency normal, aes128gcm, endpoint re-checked; 404/410 deletes the subscription, so do 5 failed deliveries in a row (other status codes, endpoint no longer allowed); network errors don't count |
| Body | due list cut at a name to 3000 bytes (JSON-escaped) with `, …`; the whole message JSON is cut to 3500 bytes, so it always fits one 4096-byte encrypted record (larger plaintext is never sent) |

## Android app API
| What | Value |
|---|---|
| Device token | `POST /api/v1/auth/login` or `/register` with `"client": "android"`, `"device_name"` (cleaned, ≤ 60 characters, default `Android`) → `{token, user}`, no cookie; send `Authorization: Bearer <token>` |
| SSO | Auth Tab (Chrome returns the `wgchores://` callback only to the app that opened it; on browsers without Auth Tab the app falls back to a Custom Tab, where another app claiming `wgchores://` could catch the code: residual risk, the code is still useless without the app's PKCE verifier) on `/auth/oidc/start?client=android&challenge=<base64url(SHA-256(verifier))>` (verifier 43–128 chars `[A-Za-z0-9-._~]`) → after the provider login a confirm page "Sign in to the WG Chores app?" (account name, en/de by `Accept-Language`, no script); its button posts `POST /auth/app/confirm` (same origin, form token bound to this browser's cookie, once, 10 min; Cancel: "You can close this tab."), so an app that starts a sign-in on its own gets no code without the user's tap → `wgchores://auth?code=<code>` (`&invite=<token>` when started from one; errors `wgchores://auth?error=<code>`); then `POST /api/v1/auth/app-token {code, verifier, device_name}` → `{token}`; code 60 s, single use (also burnt by a wrong verifier), stored hashed |
| Rules | bearer requests need no `X-WGC` header and never get cookies; cookie + bearer, or a browser sign-in with a bearer header = 400 `ambiguous_auth`; app tokens only as bearer, web tokens only as cookie; same lifetimes as web sessions; unauthenticated POSTs (login, app-token) still send `X-WGC: 1` |
| Background check | `GET /api/v1/notifications/poll[?since=<cursor>]` (bearer only, else 403 `app_only`) → `{cursor, done, due, more}`: `done` = others' completions since the cursor once past the 10 s undo window, oldest first, max 50 (`more: true` → poll again); first call without `since`: no backlog; `due` once per due period per device, acknowledged by sending the returned cursor (a lost answer is sent again); `notify_due` / `notify_done` respected; completions from `migrate-v1` and `demo-reset` create no done events |
| Devices | `GET /api/v1/me/devices` (own sessions: `id`, `kind`, `device_name`, `created_at`, `last_seen_at`, `current`), `DELETE /api/v1/me/devices/{id}` signs one out |

## SSO (OIDC)
| What | Value |
|---|---|
| Redirect URI to register at the provider | `<PUBLIC_URL>/auth/oidc/callback` |
| Scopes requested | `openid profile email groups` |
| Client type | confidential, authorization code + PKCE (S256), ID token signed RS256 |
| Login start | `GET /auth/oidc/start` (optional `?invite=<token>`; app: `?client=android&challenge=…`, see Android app API) |
| Errors | redirect to `/login?error=not_allowed` (group missing), `sso_failed`, `sso_unavailable` (retried after 30 s); details in the server log; a login started from an invite adds `&invite=<token>` |

Authentik: the provider needs a signing key (RS256; without one it signs HS256, which is refused) and the `groups` claim in the ID token (scope mapping for `groups`, "Include claims in id_token" on).
Two login tabs at once: only the one started last can finish; the other returns `sso_failed`.

Users are matched by issuer + `sub`; name, picture (https only) and username (`preferred_username`, if free) are refreshed at every login.

Remove someone: take them out of the SSO group, then `wg-chores revoke-sessions <username or display name>` (or `--user-id <id>`) (otherwise their sessions run until they expire, at most 30 days).

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
| `npm run e2e:build` | web build + `cargo build --features test-util --bins` (server with the web app embedded, plus `fake-oidc`); on fastpi run it as `flock ~/.cache/claude-work/rust-build.lock npm run e2e:build` |
| `npm run e2e` | browser suite (Playwright, Chromium only, about 3 min on fastpi); not part of `npm test`; output in `E2E_OUT` (default `web/e2e-out/`, gitignored; fastpi: `E2E_OUT=~/.cache/claude-work/wgc-e2e`) |
| `npm run e2e:layout` | only the layout matrix |

Browser suite (`web/e2e/`):

| What | Detail |
|---|---|
| Projects | `desktop`, `iphone` (iPhone 13 emulated in Chromium), `pixel` (Pixel 7), `layout`; WebKit is not installed, so real iOS Safari still needs a phone test |
| Servers | every spec file starts its own `wg-chores` (temp `DATA_DIR` under `$E2E_OUT/servers/`, free port, `PUBLIC_URL=http://localhost:<port>`) and, for SSO, `fake-oidc`; data is seeded through the API |
| Specs | `auth`, `households`, `chores`, `invites` (sign-up via invite with `OPEN_REGISTRATION=false`, SSO from an invite), `offline` (queue, cached list, `resume_after_idle_offline_and_server_restart`), `push` (fake `PushManager`) |
| Layout | every screen at 320, 375, 414, 768, 1280 px (1280 with a desktop user agent), light + dark, root font 100 % and 200 % (plus German at 320 px), fresh identical data per width; screenshots in `$E2E_OUT/layout/` |
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
| Chore page | last done (days, date, who, "N days overdue"), "Usually done every ~N days" (server median, hidden below 3 completions), history 20 per page, more on scroll; tap an entry = change date/note or delete (confirm); footer Done today / calendar icon button (Other date…); read-only from the IndexedDB cache when offline |
| Add / edit chore | name, category (existing ones offered, or a new one), interval chips (tap again = none = every month); renaming a category for all its chores is a checkbox when the category text changes; archive with confirm, restore in household settings |
| Household settings | rename, members (add by exact username, or search when `MEMBER_DIRECTORY=true`: detected from `GET /api/v1/users` answering 404), remove (confirm), invite links (create → Copy / Share, list with creator and expiry, cancel), archived chores (Restore), leave (confirm; last member: deletes the household) |
| Invite page | public preview (household, inviter, expiry or why invalid); signed out: SSO (`/auth/oidc/start?invite=…`), sign in, create account when the server allows it (joins at once); signed in: Join → household |
| Account / settings | SSO: picture, name, link to `OIDC_ACCOUNT_URL`; local: change name and password; delete account (local: current password, SSO: type display name; households where you are the only member go too); theme, language, notification toggles (`PATCH /api/v1/me`) |
| Chore list | grouped by category, categories and chores A–Z with `Intl.Collator` (accent/case-insensitive), no category last ("Other"); "today" = server `TZ` from `/api/v1/config`; refetch on tab visible and every 60 s while visible |
| Marking done | ✓ = done today at once (optimistic), bar 6 s with Undo / Other date; a second ✓ on the same chore while its mark is sent or within 1 s is ignored; long press 500 ms (≤ 10 px movement), right click, context-menu key or Shift+F10 = date sheet (today, yesterday, 2 days ago, pick date ≤ today, note) |
| Offline | done marks go to an IndexedDB outbox (`wgc` → `outbox`, per user id) with a client UUID, sent at app start, on `online` and after each successful load; 4xx (not 401/429) drops the item with a message and reloads the list; items older than 30 days are dropped the same way (an expired queued Undo says "Couldn't undo …"); Undo removes a mark never sent, otherwise queues a delete (`kind: "delete"`, a 404 counts as done); a queued mark the server answers with 410 `deleted` (undone in another tab or device) counts as sent, so it survives offline, a lost reply and closing the app; sign out tries to send first and warns when marks would be lost, then clears that user's outbox and cache; top bar shows the waiting count; deleting a history entry offline is queued the same way (hidden at once); an edit of a waiting mark is rewritten locally only if it was never sent, otherwise (like other edits) it needs a connection and goes by PATCH ("Connect to the internet to save this"); last list per user and household cached in IndexedDB (`cache`) and shown offline |
| API client | `src/lib/api.ts`: always `X-WGC: 1`, same-origin cookie; `401 unauthenticated` → `/login` (back to the intended page after sign-in), network or gateway failure or no answer within 15 s → `OfflineError`; never a "session expired" dialog |
| Language | setting in `localStorage` `wgc.lang`, else browser language (`de*` → German); texts in `src/lib/i18n/{en,de}.json` (same keys, Swiss "ss") |
| Theme | `localStorage` `wgc.theme` (`light`/`dark`, none = system); tokens incl. due colours in `src/app.css` |
| Icons | Lucide, copied paths only; licences in `web/THIRD_PARTY.md` |
| PWA | `public/manifest.webmanifest`; icons in `public/icons/` from `NODE_PATH=<dir with playwright> node scripts/icons.mjs` (192, 512, maskable 512, Apple touch 180, monochrome badge 96) |
| Service worker | `src/sw.ts` + `src/sw/core.ts`, built by `sw-plugin.ts` to `/sw.js` (no hash, build fails if it would import another chunk) with the precache list and a content version injected; precaches the shell (index, assets, icons, manifest); navigation network-first, 3 s timeout or 5xx → cached `index.html`; never touches `/api`, `/auth`, `/healthz`; old `wgc-shell-*` caches dropped on activate; a new version waits for "Update available · Reload" (Reload sends skip-waiting to that waiting worker; already activated by another tab → the bar goes away, Reload just reloads; the first-visit claim is ignored); push → notification (`tag`, click focuses an open window and routes to `url`, else opens it) |
| Offline start | last `config` + `me` in `localStorage` `wgc.session` (cleared at sign-out and on any 401; a 401 also drops that user's cached lists but keeps waiting done marks), so a reload without network opens the cached list; refreshed on `online` |
| Sign out | drops this device's push subscription (browser + server, endpoint also sent as `{push_endpoint}` with the logout); offline: `localStorage` `wgc.logout-pending` keeps the login screen up and the server logout is finished at the next start or on `online`, before any `/me`; signing in (form or SSO link) first sends that waiting logout with the old cookie and never with the new one |
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

## Security
| Topic | Detail |
|---|---|
| Rate limits | login: 5 failures per account and client IP (others can still sign in to that account) and 20 per client IP in 15 min; registration: 20 per client IP in 15 min (own bucket); app code exchange: 20 failures per client IP in 15 min (own bucket); IPv6 clients count per /64; 429 + `Retry-After` |
| Sessions | browser cookie and app device tokens alike: 30 days idle; at most 90 days (local accounts) or 30 days (SSO accounts) after sign-in; `wg-chores revoke-sessions <username>` ends them all at once |
| Add member | 20 attempts per hour per acting user (429); unknown user, already a member, and a user id without `MEMBER_DIRECTORY` all answer 404 `cannot_add` (no username probing); the added user is not asked first (consent step: open owner question) |
| Names | display, household, category and chore names lose control characters and bidi overrides (U+202A–U+202E, U+2066–U+2069) on input |
| Password hashing | at most 4 at once, 16 waiting; more → 503 `busy` + `Retry-After: 5` |

## Operations
- Schema migrations (`server/src/db/migrations/`) run at start, each in one transaction, with foreign keys off (a table rebuild such as `0004` would otherwise cascade); `PRAGMA foreign_key_check` runs before each commit, any violation rolls that migration back and the server refuses to start. Take a `backup` before updating.
- Edit data through the app or API, not with a plain `sqlite3` client: the `categories` table has a unique index on `ulower(name)`, a function only the server registers, so writes to it fail elsewhere.
