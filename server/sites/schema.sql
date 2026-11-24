CREATE TABLE IF NOT EXISTS site_books (
  owner_id TEXT PRIMARY KEY,
  version INTEGER NOT NULL CHECK(version > 0),
  book_json TEXT NOT NULL,
  request_id TEXT NOT NULL,
  request_hash TEXT NOT NULL,
  updated_at TEXT NOT NULL
);
