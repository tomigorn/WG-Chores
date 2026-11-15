
## WG-Chores-v2

| Customization | Where | Reapply | Verify | Update can break |
|---|---|---|---|---|
| Image `tomigorn/wg-chores:latest` from Docker Hub (code: `~/Documents/development/WG-Chores`); static binary on `scratch`, uid 65035:65037, read-only, no capabilities, 128 MB RAM; migrates its SQLite one way at start | `docker-compose.yaml` | `docker compose up -d` | `wgc.hardening`: user, read-only, cap_drop, health → 65035:65037 true [ALL] healthy; `wgc.healthz`: → ok | a release that changes the uid or drops the `healthcheck` command |
| Own OIDC login (no forward auth: the Android app needs the API), group WG only, local accounts off | `../Authentik/scripts/provision-wg-chores-sso.py`, `secrets/oidc_client_secret` (65035:65037 0400), compose env | provisioning (Authentik README) | `wgc.config`: `/api/v1/config` → local=False oidc=Holy Grail; `wgc.oidc-issuer`: discovery → issuer + RS256 | a provider without signing key (HS256 is refused), the env names in a new release |
| Route without `authentik@file`, HSTS only (the app sends its own CSP and other headers) | `../Traefik/traefik/dynamic/wg-chores-v2.yml` | file reload | `wgc.route-headers`: public host on the LAN path → 200 csp hsts; `wgc.no-forward-auth`: `/api/v1/config` → 200 | a headers middleware that overrides the app's CSP, a forward-auth gate added by mistake (the app's `/auth/*` and the Android API break) |

## WG-Chores-Demo

| Customization | Where | Reapply | Verify | Update can break |
|---|---|---|---|---|
| Same image as WG-Chores-v2, public demo: local accounts, no registration, `DEMO_MODE`; hardened like it | `docker-compose.yaml`, `secrets/demo_password` (65035:65037 0400) | `docker compose up -d` | `wgcdemo.hardening`: → 65035:65037 true [ALL] healthy; `wgcdemo.healthz`: → ok; `wgcdemo.config`: → local=True registration=False oidc=None | a release that changes the env names |
| Nightly reset 03:17 (system timer) | `/etc/systemd/system/wg-chores-demo-reset.*` | `sudo cp ~/Projects/Docker/WG-Chores-Demo/systemd/wg-chores-demo-reset.* /etc/systemd/system/ && sudo systemctl daemon-reload && sudo systemctl enable --now wg-chores-demo-reset.timer` | `wgcdemo.reset-timer`: → active enabled | the container renamed (the unit runs `docker exec wg-chores-demo`) |
| Route without forward auth, HSTS only | `../Traefik/traefik/dynamic/wg-chores-v2.yml` router `wg-chores-demo` | file reload | `wgcdemo.route-headers`: → 200 csp hsts | as WG-Chores-v2 |
