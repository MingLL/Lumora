-- PostgreSQL 方言，随 V1 一同改写（见该文件头部说明）。保留为独立迁移而不是并入
-- V1，是为了让 schema 的演进历史继续可读：这两条改动当初各有各的线上事故背景。

-- Retention filters and deletes by received_at on every run; V1 only indexed
-- effective_occurred_at and open_id, so those statements were full table scans.
CREATE INDEX ix_event_received_at ON wechat_event (received_at);

-- raw_event_key holds the same value as menu_url for VIEW events, where WeChat
-- allows a link of up to 1024 bytes. At VARCHAR(512) a long menu link made the
-- insert fail, which surfaced as a 503 and an endless WeChat retry loop.
ALTER TABLE wechat_event ALTER COLUMN raw_event_key TYPE VARCHAR(2048);
