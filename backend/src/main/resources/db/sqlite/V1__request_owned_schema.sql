CREATE TABLE application_settings (
    id INTEGER PRIMARY KEY CHECK (id = 1),
    username TEXT NOT NULL UNIQUE,
    password_hash TEXT NOT NULL,
    locale TEXT NOT NULL DEFAULT 'en',
    default_provider TEXT CHECK (default_provider IN ('TORBOX', 'ALL_DEBRID', 'REAL_DEBRID')),
    max_concurrent_downloads INTEGER NOT NULL DEFAULT 2 CHECK (max_concurrent_downloads BETWEEN 1 AND 32),
    organization_revision INTEGER NOT NULL DEFAULT 1 CHECK (organization_revision > 0),
    organization_json TEXT NOT NULL,
    transfer_options_json TEXT NOT NULL,
    onboarding_completed INTEGER NOT NULL DEFAULT 0 CHECK (onboarding_completed IN (0, 1)),
    jellyfin_enabled INTEGER NOT NULL DEFAULT 0 CHECK (jellyfin_enabled IN (0, 1)),
    jellyfin_url TEXT,
    jellyfin_token TEXT,
    created_at TEXT NOT NULL,
    updated_at TEXT NOT NULL
);

CREATE TABLE debrid_config (
    provider TEXT PRIMARY KEY CHECK (provider IN ('TORBOX', 'ALL_DEBRID', 'REAL_DEBRID')),
    api_key TEXT NOT NULL CHECK (length(trim(api_key)) > 0),
    created_at TEXT NOT NULL,
    updated_at TEXT NOT NULL
);

CREATE TABLE metadata_addon (
    singleton INTEGER PRIMARY KEY CHECK (singleton = 1),
    id TEXT NOT NULL UNIQUE,
    revision INTEGER NOT NULL CHECK (revision > 0),
    name TEXT NOT NULL,
    manifest_url TEXT NOT NULL,
    manifest_json TEXT NOT NULL
);

CREATE TABLE search_addon (
    id TEXT PRIMARY KEY,
    revision INTEGER NOT NULL CHECK (revision > 0),
    name TEXT NOT NULL,
    manifest_url TEXT NOT NULL,
    manifest_json TEXT NOT NULL,
    enabled INTEGER NOT NULL CHECK (enabled IN (0, 1)),
    display_order INTEGER NOT NULL CHECK (display_order >= 0),
    bound_provider TEXT CHECK (bound_provider IN ('TORBOX', 'ALL_DEBRID', 'REAL_DEBRID'))
);

CREATE TABLE download_request (
    id TEXT PRIMARY KEY,
    content_json TEXT NOT NULL,
    release_json TEXT NOT NULL,
    provider TEXT NOT NULL CHECK (provider IN ('TORBOX', 'ALL_DEBRID', 'REAL_DEBRID')),
    organization_json TEXT NOT NULL,
    state TEXT NOT NULL CHECK (state IN ('QUEUED', 'PREPARING', 'AWAITING_FILE_SELECTION', 'TRANSFERRING', 'FINALIZING', 'PAUSED', 'COMPLETED', 'FAILED', 'CANCELLED')),
    generation INTEGER NOT NULL DEFAULT 0 CHECK (generation >= 0),
    owner TEXT,
    execution_json TEXT NOT NULL,
    progress_json TEXT NOT NULL,
    error TEXT,
    created_at TEXT NOT NULL,
    updated_at TEXT NOT NULL,
    completed_at TEXT,
    CHECK ((state IN ('PREPARING', 'TRANSFERRING', 'FINALIZING') AND owner IS NOT NULL) OR
           (state NOT IN ('PREPARING', 'TRANSFERRING', 'FINALIZING') AND owner IS NULL)),
    CHECK ((state = 'COMPLETED' AND completed_at IS NOT NULL) OR (state <> 'COMPLETED' AND completed_at IS NULL))
);

CREATE INDEX download_request_queue ON download_request(state, created_at, id);
CREATE INDEX download_request_provider ON download_request(provider, state);

CREATE TABLE download_event (
    id TEXT PRIMARY KEY,
    request_id TEXT NOT NULL REFERENCES download_request(id) ON DELETE CASCADE,
    generation INTEGER NOT NULL CHECK (generation >= 0),
    state TEXT NOT NULL,
    occurred_at TEXT NOT NULL,
    message TEXT
);
CREATE INDEX download_event_history ON download_event(request_id, occurred_at, id);

CREATE TABLE jellyfin_notification (
    request_id TEXT PRIMARY KEY REFERENCES download_request(id) ON DELETE CASCADE,
    state TEXT NOT NULL CHECK (state IN ('PENDING', 'SENT', 'FAILED', 'SKIPPED')),
    attempts INTEGER NOT NULL DEFAULT 0 CHECK (attempts >= 0),
    attempted_at TEXT,
    error TEXT
);
CREATE INDEX jellyfin_notification_pending ON jellyfin_notification(state);
