CREATE TABLE IF NOT EXISTS uploads (
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  install TEXT NOT NULL,
  version TEXT,
  model TEXT,
  android INTEGER,
  received INTEGER NOT NULL,
  text TEXT NOT NULL
);
CREATE INDEX IF NOT EXISTS uploads_install ON uploads (install, received);
CREATE INDEX IF NOT EXISTS uploads_received ON uploads (received);
