ALTER TABLE download_request ADD COLUMN worker_started INTEGER NOT NULL DEFAULT 0 CHECK (worker_started IN (0, 1));
