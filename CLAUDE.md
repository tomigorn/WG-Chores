# WG Chores v2

Rust server (axum, SQLite) plus web app and Android app. Spec: `docs/superpowers/specs/2026-11-14-wg-chores-v2-design.md` (not committed). v1 (.NET) lives at tag `v1-dotnet-blazor-server`.

## Layout
| Folder | Content |
|---|---|
| `server/` | Rust server (`src/config.rs` env config incl. `LISTEN`, `src/app.rs` router, `src/auth/` sessions, CSRF, local accounts, OIDC, `src/fake_oidc.rs` + `src/bin/fake-oidc.rs` fake provider (feature `test-util`), `src/secret.rs` cookie secret, `src/web.rs` embedded web app + SPA fallback (`build.rs`), `src/headers.rs` security headers, `src/openapi.rs` + `openapi.json` (edit by hand with every route change), `src/api/` endpoints, `src/notify/` Web Push (VAPID key, encryption, due scheduler, texts), `src/db/` SQLite + migrations, `src/cli.rs` argument parser + admin commands, `src/migrate_v1.rs` v1 import, `src/demo.rs` demo data, `completions/` fish + bash; fixture `tests/fixtures/v1.sql`) |
| `web/` | Svelte 5 web app (`src/lib/` api client, i18n, theme, router, due rules mirror, push, install banner logic; `src/screens/`, `src/components/`; `src/app.css` design tokens; `src/sw.ts` + `src/sw/core.ts` service worker built by `sw-plugin.ts`; `public/` manifest + icons from `scripts/icons.mjs`; `e2e/` Playwright browser and layout suite) |
| `android/` | Kotlin app (later) |
| `deploy/` | `compose.example.yaml` (self-hosters), `compose.holy-grail.yaml` (project `wg-chores-v2`), `compose.demo.yaml` (project `wg-chores-demo`), `.env.example`, `systemd/` demo-reset timer (not installed) |
| `Dockerfile` | stages `web` (npm ci/test/build) → `build` (cargo test + static musl release) → `test` target → `scratch` (uid 65035:65037, `/data`, `/tmp` 1777, health check `wg-chores healthcheck`); bases pinned by digest |

## Build and test
- `cd server && cargo test`
- `cd web && npm test && npm run check && npm run build` (then `touch server/build.rs` to embed)
- Browser suite: `cd web && npm run e2e:build && npm run e2e` (Playwright, `web/e2e/`, not in `npm test`)
- `cd server && cargo clippy --all-targets -- -D warnings`
- Run: `PUBLIC_URL=http://localhost:8080 cargo run`, then `curl localhost:8080/healthz`
- Release builds need `web/dist/index.html` or `WGC_ALLOW_PLACEHOLDER=1`
- On fastpi: `CARGO_BUILD_JOBS=2`, one build at a time (`flock ~/.cache/claude-work/rust-build.lock`)
- Image: `flock ~/.cache/claude-work/rust-build.lock docker build -t wg-chores-v2:local .`; never `docker compose` for project `wg-chores` (live v1)

## Commits
Prefix `WG-Chores:`.

## Version
- file: server/Cargo.toml (`version`)
- tag: vX.Y.Z
- bumps: code changes only
- changelog: none
- extra steps: none
