# WG Chores: privacy

WG Chores has no central service. Your data lives on the WG Chores server you connect to, run by whoever operates it (for `wg-chores.holy-grail.ch` and `wg-chores-demo.holy-grail.ch`: the app's author).

## What the server stores
| Data | Why |
|---|---|
| Username, display name | sign in, show who did a chore |
| Password hash (local accounts only, argon2id) | sign in |
| SSO identity (provider + account id), name and picture URL from the provider | sign in with SSO |
| Households, members, chores, categories | the app itself |
| Completions: chore, who, date, optional note | "who did what, how long ago" |
| Invite links (stored hashed), who created them | joining a household |
| Sessions and devices (stored hashed), last seen | staying signed in |
| Push subscriptions (browser push address + keys), notification settings | notifications |
| Error logs (no IP addresses) | debugging |
| Client IP (IPv6: its /64) with the time of sign-in and registration attempts, and the username tried at a failed sign-in, dropped after about an hour; the reverse proxy in front may log IPs too | rate limits |

## What it does not do
- No ads, no tracking, no analytics, no third-party scripts.
- Nothing is sold or shared. Push messages go through your browser's push service (Google, Mozilla, Apple, Microsoft), encrypted end-to-end.
- Theme and language stay in your browser.

## Who sees what
Members of a household see its chores, completions and the names of its members. With `MEMBER_DIRECTORY` on, signed-in users of the same server can find your display name when adding members.

## Your rights
Delete your account at any time: [account-deletion.md](account-deletion.md). Households where you are the only member are deleted with it. Questions about the demo or Holy Grail servers: open an issue at https://github.com/tomigorn/WG-Chores/issues. For any other server, ask its operator.
