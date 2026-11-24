# Install button in Settings

Status: ✅ done 2026-11-24 (v2.1.0).

## Why
Closing the install banner hides it for 30 days (`DISMISS_DAYS`, `web/src/lib/install.ts`); after that only the browser menu installs the app.

## What
New **App** section at the top of Settings, independent of the banner's dismiss:

| Device / state | Settings shows |
|---|---|
| Android/desktop, browser offers install (`beforeinstallprompt`) | **Install** button: same system dialog as the banner (`promptInstall`) |
| Android, no install offer | how-to: **⋮ → Install and create shortcut**, plus the fix: allow the browser "Home screen shortcuts" (Brave/Xiaomi/Oppo/Huawei block it) |
| iPhone/iPad Safari | **How to install** button: opens the existing 3-step guide |
| iPhone, other browser | "Open this page in Safari first" |
| Desktop, no install offer | "Install from the browser menu" |
| Installed (standalone) | "✓ Installed" |

After ✕ on the banner, a short note (snackbar, ~3 s): "You can install later in Settings".

German and English; banner keeps its 30-day hide; docs (`docs/reference.md`, README "Install the web app on a phone") and tests updated; minor bump 2.0.4 → 2.1.0 (a new feature).

## Decisions
- 2026-11-24 Placement: own **App** section at the top of Settings.
- 2026-11-24 Installed: show "✓ Installed".
- 2026-11-24 After ✕ on the banner: short note pointing to Settings.
- 2026-11-24 Desktop without install offer: a browser-menu hint (not asked, follows the Android row).
- 2026-11-24 Version 2.1.0 instead of 2.0.5: features bump the minor version.

## Done-list
- [x] Settings shows the right content in all 6 states (verifier screenshots, phone viewport)
- [ ] Android: the button opens the real install dialog (checked: the button calls the browser's `prompt()`; the real dialog needs a phone, see roadmap By hand)
- [x] Banner behaviour unchanged (30-day dismiss, unit tests)
- [x] en + de strings, tests, docs updated; version 2.1.0
