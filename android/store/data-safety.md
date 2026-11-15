# Data safety answers

Play Console → Monitor and improve → Policy and programs → App content → **Data safety**. "Collected" in Google's sense = the app sends it off the phone (here: to the WG Chores server the user chose). Nothing goes to the developer or any third party.

## Overview questions
| Question | Answer |
|---|---|
| Does your app collect or share any of the required user data types? | Yes |
| Is all of the user data collected by your app encrypted in transit? | Yes (the app talks https only) |
| Which account creation methods does your app support? | Username and password; OAuth (SSO), depending on the server |
| Delete account URL | `https://wg-chores-demo.holy-grail.ch/account-deletion` (fallback: `https://github.com/tomigorn/WG-Chores/blob/main/docs/account-deletion.md`) |
| Do you provide a way for users to request that some or all of their data is deleted, without requiring them to delete their account? | No (leaving a household removes you from it; everything else goes with the account) |

## Data types
| Category → type | What exactly | Collected | Shared | Ephemeral | Required or optional | Purposes |
|---|---|---|---|---|---|---|
| Personal info → Name | display name | Yes | No | No | Required | App functionality, Account management |
| Personal info → User IDs | username, account id | Yes | No | No | Required | App functionality, Account management |
| App activity → Other user-generated content | households, categories, chores, notes on completions | Yes | No | No | Required | App functionality |
| App activity → Other actions | marking chores done (who, which date) | Yes | No | No | Required | App functionality |

All other types: not collected. Notes:
- No email address: local accounts have none; SSO sign-in runs in the browser, not in the app.
- Password: sent once to sign in, stored only as a hash on the server; covered by "Account management", no own data type.
- Device model name (e.g. "Pixel 8") is sent so the user can tell their devices apart in Account → Devices; it is not a unique identifier, so "Device or other IDs" stays No.
- No location, contacts, photos, files, crash logs, diagnostics, advertising ID, analytics.
- "Shared" stays No: the server belongs to the user's own operator, and Google counts transfers the user initiates as not shared.

## Security practices
| Question | Answer |
|---|---|
| Data encrypted in transit | Yes |
| Users can request data deletion | Yes (in app: Account → Delete account; plus the URL above) |
| Committed to follow the Families policy | not applicable (target audience 18+) |
| Independent security review | No |
