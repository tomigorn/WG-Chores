ALTER TABLE sessions ADD COLUMN device_name TEXT;
-- poll_seq: number of the app's last notification poll, carried in its cursor to acknowledge offered due notices
ALTER TABLE sessions ADD COLUMN poll_seq INTEGER NOT NULL DEFAULT 0;

-- one-time SSO codes for the app: SHA-256 of the code, the app's S256 PKCE challenge, 60 s, deleted on first use
CREATE TABLE app_codes (
    code_hash TEXT PRIMARY KEY,
    user_id INTEGER NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    challenge TEXT NOT NULL,
    expires_at TEXT NOT NULL
);

-- one row per completion created through the API (not imports or demo data); seq is the app poll cursor, never reused
CREATE TABLE done_events (
    seq INTEGER PRIMARY KEY AUTOINCREMENT,
    completion_id TEXT NOT NULL REFERENCES completions(id) ON DELETE CASCADE
);
CREATE INDEX done_events_completion ON done_events(completion_id);

-- due notices per app device session: told_on = period acknowledged, pending_on/pending_poll = offered by that poll, not yet acknowledged
CREATE TABLE device_notification_state (
    session_hash TEXT NOT NULL REFERENCES sessions(token_hash) ON DELETE CASCADE,
    chore_id INTEGER NOT NULL REFERENCES chores(id) ON DELETE CASCADE,
    told_on TEXT,
    pending_on TEXT,
    pending_poll INTEGER,
    PRIMARY KEY (session_hash, chore_id)
);
