CREATE TABLE Households (
    Id INTEGER NOT NULL CONSTRAINT PK_Households PRIMARY KEY AUTOINCREMENT,
    Name TEXT NOT NULL,
    Code TEXT NOT NULL,
    CreatedAt TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE TABLE Members (
    Id INTEGER NOT NULL CONSTRAINT PK_Members PRIMARY KEY AUTOINCREMENT,
    Username TEXT NOT NULL,
    HouseholdId INTEGER NOT NULL,
    CreatedAt TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE TABLE Chores (
    Id INTEGER NOT NULL CONSTRAINT PK_Chores PRIMARY KEY AUTOINCREMENT,
    Title TEXT NOT NULL,
    Description TEXT NULL,
    Room TEXT NULL DEFAULT '',
    IsDone INTEGER NOT NULL DEFAULT 0,
    HouseholdId INTEGER NOT NULL,
    CreatedAt TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE TABLE ChoreHistories (
    Id INTEGER NOT NULL CONSTRAINT PK_ChoreHistories PRIMARY KEY AUTOINCREMENT,
    ChoreId INTEGER NOT NULL,
    MemberId INTEGER NULL,
    MemberName TEXT NULL DEFAULT '',
    DoneAt TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP,
    Notes TEXT NULL
);

INSERT INTO Households (Id, Name, Code, CreatedAt) VALUES
    (1, 'Flat Alpha', 'AAA111', '2026-01-25 12:30:18'),
    (2, 'Flat Beta', 'BBB222', '2026-01-26 09:00:00');

INSERT INTO Members (Id, Username, HouseholdId, CreatedAt) VALUES
    (1, 'anna', 1, '2026-01-25 12:31:00'),
    (2, 'ben', 1, '2026-01-25 12:32:00'),
    (3, 'carl', 1, '2026-01-25 12:33:00'),
    (4, 'dora', 2, '2026-01-26 09:01:00');

INSERT INTO Chores (Id, Title, Description, Room, IsDone, HouseholdId, CreatedAt) VALUES
    (1, 'Vacuum', NULL, 'Living room', 0, 1, '2026-01-25 13:00:00'),
    (2, 'Dishes', 'after dinner', 'Kitchen', 1, 1, '2026-01-25 13:01:00'),
    (3, 'Bins', NULL, NULL, 0, 1, '2026-01-25 13:02:00'),
    (4, 'Mop floor', NULL, '', 0, 1, '2026-01-25 13:03:00'),
    (5, 'Dust shelves', NULL, ' living room ', 0, 1, '2026-01-25 13:04:00'),
    (6, 'Water plants', NULL, 'Balcony', 0, 2, '2026-01-26 09:10:00');

INSERT INTO ChoreHistories (Id, ChoreId, MemberId, MemberName, DoneAt, Notes) VALUES
    (1, 1, 1, 'anna', '2026-03-05 23:30:00.0000000', 'thorough'),
    (2, 1, NULL, 'Ben', '2026-03-10 08:00:00', NULL),
    (3, 2, 3, '', '2026-03-12 12:00:00.1234567', ''),
    (4, 2, NULL, 'Old Flatmate', '2026-02-01 10:00:00', 'before my time'),
    (5, 99, 1, 'anna', '2026-03-01 10:00:00', 'chore was deleted'),
    (6, 6, 4, 'dora', '2026-03-02 10:00:00', NULL);
