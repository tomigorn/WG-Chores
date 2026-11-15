# First release on Google Play

From nothing to WG Chores in the Play Store. Menu names as in Google's help pages (November 2026); the console may name things slightly differently. Play Console: https://play.google.com/console

| Who | Steps |
|---|---|
| You (by hand, in the browser) | everything below |
| Already prepared in the repo | texts and answers in `android/store/`, signing in [android/README.md](../../android/README.md#signing-fastpi-only), key in [android-signing-key.md](android-signing-key.md) |

Time: about 1 hour for steps 1–5, then at least 14 days of closed test, then up to 7 days for production access.

## 0. Before you start
1. Play Console → **Home**: account "tomigorn" opens, no red policy banner.
2. Trader status (EU, due 1 February 2027): click the task on the Home page → declare **not a trader** (a hobby app, no money). Consequence: the listing tells EU users that EU consumer protection does not apply to the deal with you.
3. The 12-testers rule: personal accounts created after 13 November 2023 must run a **closed test with at least 12 testers, opted in for 14 days in a row**, before they may publish (https://support.google.com/googleplay/android-developer/answer/14151465). If your account is older, Play Console shows **Production** unlocked under Test and release: then step 6 is optional.
4. Upload key exists and is backed up: [android-signing-key.md](android-signing-key.md).
5. Signed AAB ready: built on beefy ([android/README.md](../../android/README.md#release-build)) and signed on fastpi ([Signing](../../android/README.md#signing-fastpi-only)), file `~/.cache/claude-work/wgc-android/release/wg-chores-<version>.aab` on fastpi. Copy it to your computer (`scp`).
6. Demo server live with the reviewer account ([go-live-holy-grail.md](go-live-holy-grail.md) steps 4–7), demo password in KeePassXC.
7. Demo server serves `/privacy` and `/account-deletion` (every server does, rendered from `docs/`); repo pushed to GitHub as the fallback for the links.
8. A contact email for the listing. It is **public**: use an address you don't mind showing (e.g. an alias).

## 1. Create the app
1. Play Console → **Home** → **Create app**.
2. **App name**: `WG Chores`.
3. **Package name** (if asked here): `ch.tomigorn.wgchores`. Permanent, can never be changed or reused.
4. **Default language**: English (United Kingdom) – en-GB.
5. **App or game**: App. **Free or paid**: Free (can't be changed to paid later).
6. **Contact email**: the address from step 0.8.
7. **Declarations**: tick Developer Program Policies, US export laws, Play App Signing terms.
8. **Create app**. You land on the app's **Dashboard**: it lists the remaining setup tasks; the steps below cover them.

## 2. App signing
- Play App Signing = Google holds the key that signs what phones install; you only sign uploads with your **upload key**. Nothing to click: it is switched on with the first upload (step 5).
- Upload key: `~/secrets/wg-chores/upload.jks` on fastpi, backed up in KeePassXC. Lost → reset via Play (see [android-signing-key.md](android-signing-key.md)); never lose it without a backup.
- After the first upload: copy the app signing SHA-256 into the server's `ANDROID_CERT_SHA256` ([android-signing-key.md](android-signing-key.md#fingerprints-for-the-server-app-links)).

## 3. Store listing
Left menu **Grow users** → **Store presence** → **Store listings** (main listing).
1. App name, short and full description: copy from [android/store/listing-en.md](../../android/store/listing-en.md).
2. **App icon**: `android/store/icon-512.png` (512×512 PNG).
3. **Feature graphic**: `android/store/feature-graphic.png` (1024×500).
4. **Phone screenshots**: the 6 PNGs in `android/store/screenshots/` (1080×2400, in file name order).
5. Tablet, Chromebook, video: leave empty.
6. **Save**.
7. German: **Manage translations** / **Add language** → Deutsch (de-DE) → texts from [listing-de.md](../../android/store/listing-de.md) → **Save**.
8. **Store settings** (same Store presence menu): category **House & Home** (or Productivity), tags as offered; contact email; website `https://github.com/tomigorn/WG-Chores` → **Save**.

## 4. App content
Left menu **Monitor and improve** → **Policy and programs** → **App content**. Each item: **Start declaration** → answer → **Save**. Tab "Needs attention" shows what is left.

| Item | Answer |
|---|---|
| Privacy policy | `https://wg-chores-demo.holy-grail.ch/privacy` (fallback: `https://github.com/tomigorn/WG-Chores/blob/main/docs/privacy.md`) |
| Sign-in details (App access) | All or some functionality is restricted → **+ Add new instructions** → text from [reviewer-access.md](../../android/store/reviewer-access.md), real demo password from KeePassXC |
| Ads | No, my app does not contain ads |
| Content rating | questionnaire: [content-rating.md](../../android/store/content-rating.md) → **Submit** |
| Target audience and content | age groups: **18 and over** only; appeal to children: No |
| News apps | No |
| COVID-19 contact tracing and status apps (if shown) | My app is not a publicly available COVID-19 contact tracing or status app |
| Data safety | [data-safety.md](../../android/store/data-safety.md) → at the end **Submit** |
| Government apps | No |
| Financial features | My app doesn't provide any financial features |
| Health apps | My app does not have any health features |
| Advertising ID | No (the app doesn't use it) |

18+ keeps the app out of the Families policy (extra rules for apps aimed at children). WG Chores is for flatmates.

## 5. Internal test (you + Rafi)
No review, live in minutes. Left menu **Test and release** → **Testing** → **Internal testing**.
1. Tab **Testers** → **Create email list** → name `WG` → your Google account email and Rafi's (the account each of you uses in the Play Store) → **Save changes** → **Create**.
2. Tick the list `WG`. Feedback URL or email: `https://github.com/tomigorn/WG-Chores/issues` → **Save changes**.
3. **Create new release** → upload `wg-chores-<version>.aab` → Play App Signing switches on.
4. Release name: leave the default (`1 (1.0.0)` style). Release notes: the text from [listing-en.md](../../android/store/listing-en.md) between `<en-GB>` and `</en-GB>`, the German from listing-de.md between `<de-DE>` and `</de-DE>` (the box shows this format).
5. **Next** → warnings are fine, errors must be fixed → **Save and publish**.
6. Tab **Testers** → **Copy link** (opt-in link) → send it to Rafi. Each of you: open the link on the phone → **Become a tester** → **Download it on Google Play**.
7. Test: server `wg-chores.holy-grail.ch` → Sign in with Holy Grail → your household; tap a chore; turn on notifications.

## 6. Closed test (12+ testers, 14 days)
Only needed if Production is locked (step 0.3). Needs all of step 3 and 4 done.
1. Recruit at least 12, better 15 (some drop out): family, friends, Holy Grail users. Each needs an Android phone and a Google account.
2. **Test and release** → **Testing** → **Closed testing** → track **Closed testing - Alpha** → **Manage track**.
3. Tab **Testers** → **Create email list** `Closed test` with all their Google account emails → tick it; feedback channel as in step 5 → **Save changes**.
4. **Create new release** → **Add from library** (the AAB from step 5) → release notes → **Next** → **Save** → **Send for review** (left menu **Publishing overview** if asked). Review: hours to a few days.
5. When the track shows "Published": tab **Testers** → **Copy link** → send it with this note:
   - Open on your Android phone, **Become a tester**, install from Play.
   - **Stay opted in for 14 days**; don't leave the test.
   - Use it a few times: server `wg-chores-demo.holy-grail.ch`, user `reviewer` and the demo password (data resets every night), or a Holy Grail account if you gave them one.
   - Feedback: GitHub issues, or tell me.
6. Count: **Testers** tab shows how many opted in. The 14 days start when the 12th stays; leaving and rejoining restarts that tester's count.
7. Collect feedback (Play: **Monitor and improve** → **Ratings and reviews** → **Testing feedback**, plus what they tell you): you need a summary in step 7.

## 7. Apply for production access
1. After 14 days with 12+ opted in: **Dashboard** → **Apply for production**.
2. Part "About your closed test": how easy recruiting was; did testers use all features (mark done, undo, other date, households, invites, notifications); summary of feedback and how you collected it.
3. Part "About your app": audience (flatmates and households who share chores, self-hosters); value (shows when a chore was last done and by whom, no blame or scores, self-hosted, no ads or tracking); install estimate in year one (lowest range).
4. Part "Production readiness": what you changed after feedback; how you know it's ready (tests, closed test, you and Rafi use it daily).
5. **Apply**. Answer by email, usually within 7 days. "Needs more testing" → keep the closed test running, fix what they name, apply again.

## 8. Production release
1. **Test and release** → **Production** → **Countries / regions** → **Add countries/regions** → pick (e.g. Switzerland, Germany, Austria, or all) → **Save**.
2. **Create new release** → **Add from library** (the tested AAB) → release notes → **Next** → **Save** → **Send for review**.
3. First production release: staged rollout is not available (only for updates); it goes to 100 % once approved. Optional: **Publishing overview** → **Managed publishing** on, to choose the moment yourself.
4. Review: usually 1–7 days. Then the app is public at `https://play.google.com/store/apps/details?id=ch.tomigorn.wgchores` (link used by "Please update the app").

## 9. Updates
1. Bump the version (`server/Cargo.toml`; versionCode follows), commit.
2. Build on beefy and sign on fastpi ([android/README.md](../../android/README.md)).
3. Internal testing → **Create new release** → upload → release notes → publish; check on your phone.
4. **Production** → **Create new release** → **Add from library** → release notes → **Next**.
5. Staged rollout: **20 %** → **Save** → **Send for review**. After 2–3 calm days (no crashes in **Monitor and improve** → **Android vitals**): **Releases** tab → **Manage rollout** → **Update rollout** → **100 %**. Problem: **Manage rollout** → **Halt rollout**, fix, upload a new version.
6. A server change that old apps can't handle: raise `MIN_APP_VERSION` only after the new app is at 100 %.

## 10. Common rejections
| Rejection | Fix |
|---|---|
| Reviewer could not sign in / "app access" | demo server down, password changed or reset failed: check https://wg-chores-demo.holy-grail.ch with `reviewer`, update Sign-in details, resubmit (**Publishing overview** → **Send for review**) |
| Broken functionality / minimal functionality | reviewer didn't know about the server address: make the instructions in reviewer-access.md clearer; the demo must have data ("Demo WG") |
| Privacy policy link broken or not about this app | link must open without login and name "WG Chores"; fix the URL in App content |
| Data safety mismatch | what the app sends differs from the form: update data-safety.md and the form |
| Account deletion | in-app path and the deletion URL must both work without contacting you first |
| Metadata (title, screenshots) | no "best", "#1", emoji or keyword lists in texts; screenshots show the real app |
| Production access refused | fewer than 12 testers for 14 days, or too little use: keep testing, ask testers to really use it, reapply |

Appeal or question: Play Console → **Help** (top right) → contact support.
