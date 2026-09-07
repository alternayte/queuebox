-- The application's own table. QueueBox does not own it and never creates it.
CREATE TABLE IF NOT EXISTS orders (
    id    TEXT PRIMARY KEY,
    total INTEGER NOT NULL
);
