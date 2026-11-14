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
| `DATA_DIR` | `/data` (holds `wg-chores.db` and `secret.key`, both created if missing; keep `secret.key` private) |
| `TZ` | `UTC` |
| `LOCAL_ACCOUNTS`, `OPEN_REGISTRATION`, `MEMBER_DIRECTORY` | `true`, `false`, `false` (`true`/`false`/`1`/`0`) |
| `NOTIFY_TIMES` | `14:00,19:00` |
| `TRUSTED_PROXIES` | empty; comma-separated IPs/CIDRs of reverse proxies whose `X-Forwarded-For` is believed (rate limits use the real client IP) |
| `OIDC_ISSUER`, `OIDC_CLIENT_ID`, `OIDC_CLIENT_SECRET` or `_FILE` | all or none; secret must not be empty |
| `OIDC_NAME`, `OIDC_REQUIRED_GROUP`, `OIDC_ACCOUNT_URL` (http/https) | optional |

## SSO (OIDC)
| What | Value |
|---|---|
| Redirect URI to register at the provider | `<PUBLIC_URL>/auth/oidc/callback` |
| Scopes requested | `openid profile email groups` |
| Client type | confidential, authorization code + PKCE (S256), ID token signed RS256 |
| Login start | `GET /auth/oidc/start` (optional `?invite=<token>`) |
| Errors | redirect to `/login?error=not_allowed` (group missing), `sso_failed`, `sso_unavailable` |

Users are matched by issuer + `sub`; name, picture (https only) and username (`preferred_username`, if free) are refreshed at every login.

Fake provider for tests and the browser suite:
```
cargo run --features test-util --bin fake-oidc -- 8081
curl -X POST -H 'content-type: application/json' localhost:8081/_test/user \
  -d '{"sub":"u1","name":"Jane Doe","preferred_username":"jane","picture":null,"email":null,"groups":["WG"]}'
```
It prints the `OIDC_*` values to use.
