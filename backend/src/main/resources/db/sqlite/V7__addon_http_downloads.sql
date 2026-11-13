DROP TABLE debrid_config;
ALTER TABLE application_settings DROP COLUMN default_provider;
ALTER TABLE search_addon DROP COLUMN bound_provider;

DROP INDEX download_request_provider;
ALTER TABLE download_request ADD COLUMN service TEXT CHECK (service IN ('TORBOX', 'ALL_DEBRID', 'REAL_DEBRID'));
UPDATE download_request SET service = provider;
ALTER TABLE download_request DROP COLUMN provider;
ALTER TABLE download_request RENAME COLUMN service TO provider;
CREATE INDEX download_request_provider ON download_request(provider, state);

UPDATE download_request
SET state = 'FAILED', owner = NULL, worker_started = 0, generation = generation + 1,
    error = 'KZ.ADDON_HTTP_REQUIRED', updated_at = strftime('%Y-%m-%dT%H:%M:%fZ', 'now')
WHERE state NOT IN ('COMPLETED', 'CANCELLED')
  AND json_extract(release_json, '$.reference.type') IN ('MAGNET', 'INFO_HASH');
