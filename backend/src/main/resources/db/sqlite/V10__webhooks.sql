CREATE TABLE webhook (
    id TEXT PRIMARY KEY,
    name TEXT NOT NULL UNIQUE CHECK (length(trim(name)) > 0),
    method TEXT NOT NULL CHECK (method IN ('GET', 'POST', 'PUT', 'PATCH', 'DELETE')),
    url_template TEXT NOT NULL,
    headers_json TEXT NOT NULL DEFAULT '[]',
    body_mode TEXT NOT NULL CHECK (body_mode IN ('NONE', 'TEMPLATE')),
    body_template TEXT,
    events_json TEXT NOT NULL DEFAULT '[]',
    manual INTEGER NOT NULL DEFAULT 0 CHECK (manual IN (0, 1)),
    enabled INTEGER NOT NULL DEFAULT 1 CHECK (enabled IN (0, 1)),
    display_order INTEGER NOT NULL DEFAULT 0 CHECK (display_order >= 0),
    created_at TEXT NOT NULL,
    updated_at TEXT NOT NULL
);

CREATE TABLE webhook_delivery (
    id TEXT PRIMARY KEY,
    request_id TEXT NOT NULL REFERENCES download_request(id) ON DELETE CASCADE,
    webhook_id TEXT REFERENCES webhook(id) ON DELETE SET NULL,
    webhook_name TEXT NOT NULL,
    trigger_type TEXT NOT NULL CHECK (trigger_type IN ('AUTOMATIC', 'MANUAL')),
    event TEXT,
    download_event_id TEXT REFERENCES download_event(id) ON DELETE SET NULL,
    state TEXT NOT NULL CHECK (state IN ('PENDING', 'SENT', 'FAILED')),
    in_progress INTEGER NOT NULL DEFAULT 0 CHECK (in_progress IN (0, 1)),
    attempts INTEGER NOT NULL DEFAULT 0 CHECK (attempts >= 0),
    created_at TEXT NOT NULL,
    attempted_at TEXT,
    http_status INTEGER,
    error TEXT,
    CHECK ((trigger_type = 'AUTOMATIC' AND event IS NOT NULL) OR (trigger_type = 'MANUAL' AND event IS NULL))
);
CREATE INDEX webhook_delivery_request ON webhook_delivery(request_id);
CREATE INDEX webhook_delivery_pending ON webhook_delivery(state, in_progress);

-- The dedicated Jellyfin integration becomes an ordinary webhook equivalent to the Jellyfin preset.
INSERT INTO webhook (id, name, method, url_template, headers_json, body_mode, body_template, events_json, manual, enabled,
                     display_order, created_at, updated_at)
SELECT lower(hex(randomblob(4))) || '-' || lower(hex(randomblob(2))) || '-4' || substr(lower(hex(randomblob(2))), 2) || '-' ||
       substr('89ab', 1 + (abs(random()) % 4), 1) || substr(lower(hex(randomblob(2))), 2) || '-' || lower(hex(randomblob(6))),
       'Jellyfin',
       'POST',
       rtrim(trim(jellyfin_url), '/') || '/Library/Refresh',
       json_array(json_object(
           'name', 'Authorization',
           'value', 'MediaBrowser Client="dmdl", Device="dmdl", DeviceId="dmdl", Version="1", Token="' ||
                    replace(trim(jellyfin_token), '"', '') || '"'
       )),
       'NONE',
       NULL,
       '["download.completed"]',
       1,
       jellyfin_enabled,
       0,
       strftime('%Y-%m-%dT%H:%M:%fZ', 'now'),
       strftime('%Y-%m-%dT%H:%M:%fZ', 'now')
FROM application_settings
WHERE id = 1 AND trim(coalesce(jellyfin_url, '')) <> '' AND trim(coalesce(jellyfin_token, '')) <> '';

-- SKIPPED notifications sent nothing, so they have no delivery to keep.
INSERT INTO webhook_delivery (id, request_id, webhook_id, webhook_name, trigger_type, event, download_event_id, state,
                              in_progress, attempts, created_at, attempted_at, http_status, error)
SELECT lower(hex(randomblob(4))) || '-' || lower(hex(randomblob(2))) || '-4' || substr(lower(hex(randomblob(2))), 2) || '-' ||
       substr('89ab', 1 + (abs(random()) % 4), 1) || substr(lower(hex(randomblob(2))), 2) || '-' || lower(hex(randomblob(6))),
       notification.request_id,
       webhook.id,
       webhook.name,
       'AUTOMATIC',
       'download.completed',
       (SELECT event.id FROM download_event event
        WHERE event.request_id = notification.request_id AND event.state = 'COMPLETED'
        ORDER BY event.rowid DESC LIMIT 1),
       notification.state,
       0,
       notification.attempts,
       coalesce(request.completed_at, request.updated_at),
       notification.attempted_at,
       NULL,
       notification.error
FROM jellyfin_notification notification
JOIN download_request request ON request.id = notification.request_id
JOIN webhook ON webhook.name = 'Jellyfin'
WHERE notification.state IN ('PENDING', 'SENT', 'FAILED')
ORDER BY julianday(coalesce(request.completed_at, request.updated_at)), request.id;

DROP INDEX jellyfin_notification_pending;
DROP TABLE jellyfin_notification;
ALTER TABLE application_settings DROP COLUMN jellyfin_enabled;
ALTER TABLE application_settings DROP COLUMN jellyfin_url;
ALTER TABLE application_settings DROP COLUMN jellyfin_token;
