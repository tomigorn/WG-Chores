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
| `DATA_DIR` | `/data` (holds `wg-chores.db`, created if missing) |
| `TZ` | `UTC` |
| `LOCAL_ACCOUNTS`, `OPEN_REGISTRATION`, `MEMBER_DIRECTORY` | `true`, `false`, `false` (`true`/`false`/`1`/`0`) |
| `NOTIFY_TIMES` | `14:00,19:00` |
| `TRUSTED_PROXIES` | empty; comma-separated IPs/CIDRs of reverse proxies whose `X-Forwarded-For` is believed (rate limits use the real client IP) |
| `OIDC_ISSUER`, `OIDC_CLIENT_ID`, `OIDC_CLIENT_SECRET` or `_FILE` | all or none; secret must not be empty |
| `OIDC_NAME`, `OIDC_REQUIRED_GROUP`, `OIDC_ACCOUNT_URL` (http/https) | optional |
