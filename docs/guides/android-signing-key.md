# Android upload key

## What it is
| Key | Who holds it | Used for |
|---|---|---|
| Upload key | you, on fastpi | signs every AAB you upload; Google checks it is you |
| App signing key | Google (Play App Signing) | signs the APKs phones install from Play |

Losing the upload key is fixable (Google resets it, takes a few days). Losing it to someone else lets them upload as you until the reset: keep it private.

## Where it is
| File | Content |
|---|---|
| `~/secrets/wg-chores/upload.jks` | keystore (PKCS12), alias `upload`, RSA 4096, valid 30 years |
| `~/secrets/wg-chores/upload.pass` | its password (store and key share it) |

Folder mode 700, files 600, fastpi only. Never in the repo, never on beefy (`beefy-build.sh` refuses untracked `*.jks`). Signing: [android/README.md](../../android/README.md#signing-fastpi-only).

## Create it (once)
Skip if `ls ~/secrets/wg-chores/upload.jks` already shows the file. fastpi has no JDK, so `keytool` runs in the pinned JDK container:
```
mkdir -p ~/secrets/wg-chores && chmod 700 ~/secrets/wg-chores
(umask 077; openssl rand -base64 24 | tr -d '\n' > ~/secrets/wg-chores/upload.pass)
J=eclipse-temurin:21-jdk@sha256:0a3f329821dcadf54b2a3033a15e2e9f007069a9db43d7f51897e6b80e726b07
docker run --rm --network none --user $(id -u):$(id -g) -v $HOME/secrets/wg-chores:/keys $J \
  keytool -genkeypair -keystore /keys/upload.jks -storetype PKCS12 -storepass:file /keys/upload.pass \
  -alias upload -keyalg RSA -keysize 4096 -validity 10950 -dname "CN=WG Chores upload"
chmod 600 ~/secrets/wg-chores/upload.jks
ls -l ~/secrets/wg-chores/      # both -rw-------
```

## Back it up (now, before the first upload)
1. Open KeePassXC → new entry **WG Chores upload key**.
2. Password field: paste the content of `upload.pass` (`cat ~/secrets/wg-chores/upload.pass`, then `clear && printf '\e[3J'`).
3. Tab **Advanced** → **Attachments** → **Add** → `upload.jks` (copy it to your computer first, e.g. `scp fastpi:secrets/wg-chores/upload.jks .`, delete the copy afterwards).
4. Notes: `alias upload, PKCS12, fastpi ~/secrets/wg-chores/`.
5. Save the database; check that your KeePassXC backup includes it.

Restore: copy the attachment back to `~/secrets/wg-chores/upload.jks`, write the password into `upload.pass`, `chmod 600` both.

## Lost or leaked
1. Create a new key with the commands above (move the old files away first).
2. Export its certificate:
```
docker run --rm --network none --user $(id -u):$(id -g) -v $HOME/secrets/wg-chores:/keys $J \
  keytool -exportcert -rfc -keystore /keys/upload.jks -storepass:file /keys/upload.pass -alias upload -file /keys/upload_certificate.pem
```
3. Play Console → your app → **Protected with Play** → **Play Store protection** → **Manage Play app signing** (older name: Test and release → Setup → App signing) → section "Upload key certificate" → **Request upload key reset** → reason → upload `upload_certificate.pem` → **Request**.
4. Wait for Google's email; then upload with the new key. Update the KeePassXC entry.
