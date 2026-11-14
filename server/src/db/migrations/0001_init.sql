CREATE TABLE users (
    id INTEGER PRIMARY KEY,
    username TEXT COLLATE NOCASE UNIQUE,
    display_name TEXT NOT NULL,
    picture_url TEXT,
    former INTEGER NOT NULL DEFAULT 0 CHECK (former IN (0, 1)),
    notify_due INTEGER NOT NULL DEFAULT 1 CHECK (notify_due IN (0, 1)),
    notify_done INTEGER NOT NULL DEFAULT 1 CHECK (notify_done IN (0, 1)),
    last_household_id INTEGER REFERENCES households(id) ON DELETE SET NULL,
    created_at TEXT NOT NULL,
    deleted_at TEXT
);

CREATE TABLE identities (
    id INTEGER PRIMARY KEY,
    user_id INTEGER NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    kind TEXT NOT NULL CHECK (kind IN ('local', 'oidc')),
    issuer TEXT NOT NULL DEFAULT '',
    subject TEXT NOT NULL,
    password_hash TEXT,
    UNIQUE (issuer, subject)
);
CREATE INDEX identities_user ON identities(user_id);

CREATE TABLE sessions (
    token_hash TEXT PRIMARY KEY,
    user_id INTEGER NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    kind TEXT NOT NULL CHECK (kind IN ('web', 'android')),
    created_at TEXT NOT NULL,
    last_seen_at TEXT NOT NULL,
    expires_at TEXT NOT NULL
);
CREATE INDEX sessions_user ON sessions(user_id);

CREATE TABLE households (
    id INTEGER PRIMARY KEY,
    name TEXT NOT NULL,
    created_at TEXT NOT NULL
);

CREATE TABLE memberships (
    household_id INTEGER NOT NULL REFERENCES households(id) ON DELETE CASCADE,
    user_id INTEGER NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    joined_at TEXT NOT NULL,
    PRIMARY KEY (household_id, user_id)
);
CREATE INDEX memberships_user ON memberships(user_id);

CREATE TABLE categories (
    id INTEGER PRIMARY KEY,
    household_id INTEGER NOT NULL REFERENCES households(id) ON DELETE CASCADE,
    name TEXT NOT NULL COLLATE NOCASE,
    UNIQUE (household_id, name)
);

CREATE TABLE chores (
    id INTEGER PRIMARY KEY,
    household_id INTEGER NOT NULL REFERENCES households(id) ON DELETE CASCADE,
    category_id INTEGER REFERENCES categories(id) ON DELETE SET NULL,
    name TEXT NOT NULL,
    interval TEXT CHECK (interval IN ('3d', '1w', '2w', '1m', '3m', '6m', '1y')),
    archived_at TEXT,
    created_at TEXT NOT NULL
);
CREATE INDEX chores_household ON chores(household_id);

CREATE TABLE completions (
    id TEXT PRIMARY KEY,
    chore_id INTEGER NOT NULL REFERENCES chores(id) ON DELETE CASCADE,
    user_id INTEGER NOT NULL REFERENCES users(id),
    done_on TEXT NOT NULL,
    note TEXT,
    created_at TEXT NOT NULL,
    deleted_at TEXT
);
CREATE INDEX completions_chore ON completions(chore_id, done_on);

CREATE TABLE invites (
    id INTEGER PRIMARY KEY,
    token_hash TEXT NOT NULL UNIQUE,
    household_id INTEGER NOT NULL REFERENCES households(id) ON DELETE CASCADE,
    created_by INTEGER NOT NULL REFERENCES users(id),
    created_at TEXT NOT NULL,
    expires_at TEXT NOT NULL,
    used_by INTEGER REFERENCES users(id),
    used_at TEXT,
    cancelled_at TEXT
);

-- completion_cursor: created_at of the newest completion this device was already told about
CREATE TABLE push_subscriptions (
    id INTEGER PRIMARY KEY,
    user_id INTEGER NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    endpoint TEXT NOT NULL UNIQUE,
    p256dh TEXT NOT NULL,
    auth TEXT NOT NULL,
    lang TEXT NOT NULL DEFAULT 'en',
    completion_cursor TEXT,
    created_at TEXT NOT NULL
);
CREATE INDEX push_subscriptions_user ON push_subscriptions(user_id);

CREATE TABLE notification_state (
    subscription_id INTEGER NOT NULL REFERENCES push_subscriptions(id) ON DELETE CASCADE,
    chore_id INTEGER NOT NULL REFERENCES chores(id) ON DELETE CASCADE,
    last_due_notified_on TEXT NOT NULL,
    PRIMARY KEY (subscription_id, chore_id)
);

CREATE TABLE login_failures (
    key TEXT NOT NULL,
    at TEXT NOT NULL
);
CREATE INDEX login_failures_key ON login_failures(key, at);
