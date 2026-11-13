ALTER TABLE download_request ADD COLUMN history_removed INTEGER NOT NULL DEFAULT 0 CHECK (history_removed IN (0, 1));
