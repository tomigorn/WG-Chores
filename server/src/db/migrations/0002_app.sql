ALTER TABLE sessions ADD COLUMN device_name TEXT;

-- one-time SSO codes for the app: SHA-256 of the code, the app's S256 PKCE challenge, 60 s, deleted on first use
CREATE TABLE app_codes (
    code_hash TEXT PRIMARY KEY,
    user_id INTEGER NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    challenge TEXT NOT NULL,
    expires_at TEXT NOT NULL
);

-- due periods already announced to an app device session (the Web Push counterpart is notification_state)
CREATE TABLE device_notification_state (
    session_hash TEXT NOT NULL REFERENCES sessions(token_hash) ON DELETE CASCADE,
    chore_id INTEGER NOT NULL REFERENCES chores(id) ON DELETE CASCADE,
    last_due_notified_on TEXT NOT NULL,
    PRIMARY KEY (session_hash, chore_id)
);
