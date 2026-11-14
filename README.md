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
| Errors | redirect to `/login?error=not_allowed` (group missing), `sso_failed`, `sso_unavailable` (retried after 30 s); details in the server log |

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

## Operations
- Edit data through the app or API, not with a plain `sqlite3` client: the `categories` table has a unique index on `ulower(name)`, a function only the server registers, so writes to it fail elsewhere.
