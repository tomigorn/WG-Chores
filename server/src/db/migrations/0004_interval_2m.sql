-- adds the '2m' interval: SQLite cannot alter a CHECK, so chores is rebuilt (runner turns foreign keys off and checks them before commit)
CREATE TABLE new_chores (
    id INTEGER PRIMARY KEY,
    household_id INTEGER NOT NULL REFERENCES households(id) ON DELETE CASCADE,
    category_id INTEGER REFERENCES categories(id) ON DELETE SET NULL,
    name TEXT NOT NULL,
    interval TEXT CHECK (interval IN ('3d', '1w', '2w', '1m', '2m', '3m', '6m', '1y')),
    archived_at TEXT,
    created_at TEXT NOT NULL
);
INSERT INTO new_chores (id, household_id, category_id, name, interval, archived_at, created_at)
    SELECT id, household_id, category_id, name, interval, archived_at, created_at FROM chores;
DROP TABLE chores;
ALTER TABLE new_chores RENAME TO chores;
CREATE INDEX chores_household ON chores(household_id);
