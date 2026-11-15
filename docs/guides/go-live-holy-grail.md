# Go live on Holy Grail (fastpi)

Puts WG Chores v2 on `https://wg-chores.holy-grail.ch` (SSO, group WG) and the demo on `https://wg-chores-demo.holy-grail.ch`, then switches from v1. The kit is in `deploy/holy-grail/` and is copied into HomeLab-FastPi (`~/Projects`) by `deploy/holy-grail/apply.sh`.

| Who | Steps |
|---|---|
| Owner (by hand) | 0 Docker Hub, 1 Cloudflare, 7 the logins, 8 Rafi |
| Claude, only after the owner's yes (SSO, edge and v1 changes are yes-first) | 2 to 6, 9, 10 |

Do steps 2 to 7 in one sitting: from step 2 on the customization checks (every 2 h at :11) fail until the containers run.

## 0. Prerequisites
- Image on Docker Hub: [docker-hub-release.md](docker-hub-release.md) steps 1 to 6 (`tomigorn/wg-chores:latest` with linux/arm64).
- Not published yet: `docker tag wg-chores-v2:local tomigorn/wg-chores:latest` (local image; Cup and the nightly update see it only after the first real pull).
- Cup needs no config: its fastpi agent lists every image on the host that has a registry digest, so `tomigorn/wg-chores` shows up once pulled.

## 1. Cloudflare (owner)
For each of `wg-chores` and `wg-chores-demo`:
1. Open https://one.dash.cloudflare.com → **Networks** → **Tunnels** → tunnel **fastpi** → **Edit**.
2. Tab **Public Hostname** (published application routes) → **Add a public hostname**.
3. **Subdomain** `wg-chores` (then `wg-chores-demo`), **Domain** `holy-grail.ch`, **Path** empty.
4. **Service**: Type `HTTPS`, URL `192.168.1.2:443`.
5. **Additional application settings** → **TLS** → **No TLS Verify** on (as `specs`, `wiki`, `activity`).
6. **Save hostname**.

## 2. Copy the kit into HomeLab-FastPi
```
cd ~/Documents/development/WG-Chores
source deploy/holy-grail/completions/apply.sh.bash   # tab completion (fish: source …/apply.sh.fish)
deploy/holy-grail/apply.sh --dry-run     # shows the diff, writes nothing
deploy/holy-grail/apply.sh --apply       # writes the files, secrets/ folders and .env; no commit
docker logs --since 1m traefik 2>&1 | grep -i error   # empty = route loaded
```
`--apply` prints the commit commands for `~/Projects`; run them once step 7 passes.

## 3. Authentik provisioning
```
cd ~/Projects/Docker/Authentik
docker compose exec -T -e WG_CHORES_OIDC_FILE=WG-Chores-v2/secrets/oidc_client_secret \
  -e WG_CHORES_MEMBERS="tomigorn <rafi-username>" server ak shell \
  < scripts/provision-wg-chores-sso.py 2>/dev/null | python3 scripts/apply-sso-env.py
```
`<rafi-username>`: his Holy Grail username (not written in this public repo). Expected: `set WG-Chores-v2/secrets/oidc_client_secret`, `CHANGE group WG created`, `CHANGE tomigorn added to WG`, `MISSING user <rafi-username>` (until step 8), `OK provisioned`. The secret only goes through the pipe into the file, never to the screen.

Rerun (e.g. step 8): updates group, members, provider and binding; the client secret and its file stay as they are (`OK client credential unchanged`). New secret: add `-e WG_CHORES_OIDC_ROTATE=1`, then `docker compose --project-directory ~/Projects/Docker/WG-Chores-v2 up -d --force-recreate`. Secret file lost or never written (e.g. the first run's pipe failed): only `WG_CHORES_OIDC_ROTATE=1` writes it again, and it rotates the client secret.

## 4. Secrets
| File (in `~/Projects/Docker/`) | Owner, mode | How |
|---|---|---|
| `WG-Chores-v2/secrets/oidc_client_secret` | 65035:65037 0400 | step 3 |
| `WG-Chores-Demo/secrets/demo_password` | 65035:65037 0400 | below |
```
openssl rand -base64 18 | tr -d '\n' | sudo install -o 65035 -g 65037 -m 0400 /dev/stdin ~/Projects/Docker/WG-Chores-Demo/secrets/demo_password
sudo stat -c '%u:%g %a %n' ~/Projects/Docker/WG-Chores-*/secrets/*      # both 65035:65037 400
```
Save the demo password in KeePassXC (Play reviewers need it): `sudo cat ~/Projects/Docker/WG-Chores-Demo/secrets/demo_password`, then clear the terminal scrollback: `clear && printf '\e[3J'`.

## 5. Start the containers
Used the temporary local tag in step 0 and Docker Hub has the image now: first `docker rmi tomigorn/wg-chores:latest` (removes only the tag), then the commands below pull the real image.
```
docker compose --project-directory ~/Projects/Docker/WG-Chores-v2 pull
docker compose --project-directory ~/Projects/Docker/WG-Chores-Demo pull
docker compose --project-directory ~/Projects/Docker/WG-Chores-v2 up -d      # project wg-chores-v2
docker compose --project-directory ~/Projects/Docker/WG-Chores-Demo up -d    # project wg-chores-demo
docker ps --filter name=wg-chores- --format '{{.Names}} {{.Status}}'        # both "(healthy)" after ~30 s
```

## 6. Demo data and nightly reset
```
docker exec wg-chores-demo /wg-chores demo-reset
sudo cp ~/Projects/Docker/WG-Chores-Demo/systemd/wg-chores-demo-reset.* /etc/systemd/system/
sudo systemctl daemon-reload && sudo systemctl enable --now wg-chores-demo-reset.timer
systemctl list-timers wg-chores-demo-reset.timer      # next run 03:17
```

## 7. Verify
1. Phone on mobile data (not Wi-Fi): https://wg-chores.holy-grail.ch → **Sign in with Holy Grail** → log in as `tomigorn` → the app opens.
2. Create a household `Test`, add a chore, mark it done, see it in the history; delete the household.
3. https://wg-chores-demo.holy-grail.ch → log in as `reviewer` with the demo password → household "Demo WG" → mark a chore done.
4. Checks and probes:
```
~/Projects/Docker/_global/scripts/post-update-check.sh WG-Chores-v2      # 0 failed
~/Projects/Docker/_global/scripts/post-update-check.sh WG-Chores-Demo    # 0 failed
docker kill -s HUP prometheus                                            # probes for both sites go live
```
5. Commit in `~/Projects` with the commands `apply.sh --apply` printed.

## 8. Rafi
1. Rafi finishes his Holy Grail signup (his invite link).
2. Rerun step 3: now `CHANGE <rafi-username> added to WG`.
3. Rafi opens https://wg-chores.holy-grail.ch → **Sign in with Holy Grail**.

## 9. Switch from v1
Both of you must have signed in once (step 7 and 8): the import maps to existing users.
1. Tell the WG; stop v1 (containers stay, data stays):
```
docker stop wg-chores-app wg-chores-sqliteweb
```
2. Copy the v1 database and look at its names:
```
sudo sqlite3 -readonly /var/lib/docker/volumes/wg-chores_wgchores-data/_data/wgchores.db ".backup /home/pi/.cache/claude-work/wg-v1.db"
sudo chown 65035:65037 /home/pi/.cache/claude-work/wg-v1.db && sudo chmod 400 /home/pi/.cache/claude-work/wg-v1.db
sudo sqlite3 -readonly /home/pi/.cache/claude-work/wg-v1.db "SELECT Id, Name FROM Households; SELECT HouseholdId, Username FROM Members;"
```
3. Back up v2, then import (fill in the household and the two v1 member names from step 2):
```
docker exec wg-chores-v2 /wg-chores backup /data/before-v1-import.db
docker compose --project-directory ~/Projects/Docker/WG-Chores-v2 run --rm --no-deps --name wg-chores-v2-import \
  -v /home/pi/.cache/claude-work/wg-v1.db:/import/v1.db:ro wg-chores-v2 \
  migrate-v1 --from /import/v1.db --household '<household>' --map '<v1 name>=tomigorn,<v1 name>=<rafi-username>'
sudo rm /home/pi/.cache/claude-work/wg-v1.db
```
4. Open the app: the household, chores and history are there.
5. Old URL `wg-aemtli.ddns.net`, pick one:

| Option | What | Effort |
|---|---|---|
| A (recommended for a few weeks) | redirect to `https://wg-chores.holy-grail.ch/`: in `~/Projects/Docker/Traefik/traefik/dynamic/wg-chores.yml` a `redirectRegex` middleware (regex `.*`, replacement the new URL, permanent) instead of the service | one file, hot reload |
| B | retire: delete `wg-chores.yml` (the WAN 443 → 8443 forward stays for LanguageTool), drop `wg-aemtli.ddns.net` from the DDNS updater and let the No-IP host lapse | small |
| C | keep v1 read-only next to v2 | v1 stays running and needs updates |

## 10. Rollback
| Problem | Do |
|---|---|
| v2 broken after the switch | `docker compose --project-directory ~/Projects/Docker/WG-Chores-v2 stop`, then `docker start wg-chores-app wg-chores-sqliteweb` (v1 data unchanged) |
| bad import | `docker compose --project-directory ~/Projects/Docker/WG-Chores-v2 stop`, restore `/data/before-v1-import.db` as in the README "Backup and restore" (volume `wg-chores-v2_wg-chores-v2-data`), start |
| new image broken | [docker-hub-release.md](docker-hub-release.md) "Roll back" |
| take it all back | stop both projects; `git -C ~/Projects status` → `git -C ~/Projects restore <changed files>` and delete the new files (or `git revert` once committed); Authentik: delete application `wg-chores`, provider `wg-chores`, mapping `WG Chores groups` (admin UI); Cloudflare: delete the two public hostnames |
