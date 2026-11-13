ALTER TABLE application_settings ADD COLUMN configuration_revision INTEGER NOT NULL DEFAULT 0;
ALTER TABLE application_settings ADD COLUMN setup_submission_id TEXT;
ALTER TABLE application_settings ADD COLUMN setup_fingerprint TEXT;
