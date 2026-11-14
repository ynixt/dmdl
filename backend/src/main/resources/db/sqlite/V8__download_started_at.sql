ALTER TABLE download_request ADD COLUMN started_at TEXT;

-- Order by time value, not text: Instant.toString omits a zero fraction, so "...:00Z" sorts after "...:00.25Z" as text.
UPDATE download_request
SET started_at = (
    SELECT event.occurred_at FROM download_event event
    WHERE event.request_id = download_request.id AND event.state = 'TRANSFERRING'
    ORDER BY julianday(event.occurred_at), event.occurred_at
    LIMIT 1
);
