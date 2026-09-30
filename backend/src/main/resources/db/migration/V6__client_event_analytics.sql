-- NULL denotes historical events where this field was not collected.
ALTER TABLE client_event ADD COLUMN client_ip VARCHAR(45);
ALTER TABLE client_event ADD COLUMN referrer_host VARCHAR(253);
CREATE INDEX ix_client_event_ip_time ON client_event (client_ip, received_at DESC)
    WHERE type = 'PAGE_OPEN';

CREATE TABLE ip_location_cache (
    ip VARCHAR(45) PRIMARY KEY,
    payload TEXT NOT NULL,
    expires_at TIMESTAMPTZ NOT NULL
);
CREATE INDEX ix_ip_location_cache_expires ON ip_location_cache (expires_at);
