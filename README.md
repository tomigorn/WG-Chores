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
