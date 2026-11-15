-- app SSO sign-ins waiting for the user's confirmation in the browser: SHA-256 of the id in the browser's cookie, 10 min, deleted when confirmed or cancelled
CREATE TABLE app_confirms (
    id_hash TEXT PRIMARY KEY,
    user_id INTEGER NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    challenge TEXT NOT NULL,
    invite TEXT,
    expires_at TEXT NOT NULL
);
