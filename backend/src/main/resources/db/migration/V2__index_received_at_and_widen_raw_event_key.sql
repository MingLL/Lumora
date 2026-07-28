-- Expand-only. Both the current and the candidate application version run
-- against this schema unchanged: an added index is transparent, and widening a
-- VARCHAR keeps the column's meaning and its existing 2-byte length prefix.

-- Retention filters and deletes by received_at on every run; V1 only indexed
-- effective_occurred_at and open_id, so those statements were full table scans.
CREATE INDEX ix_event_received_at ON wechat_event (received_at);

-- raw_event_key holds the same value as menu_url for VIEW events, where WeChat
-- allows a link of up to 1024 bytes. At VARCHAR(512) a long menu link made the
-- insert fail, which surfaced as a 503 and an endless WeChat retry loop.
ALTER TABLE wechat_event MODIFY COLUMN raw_event_key VARCHAR(2048) NULL;
