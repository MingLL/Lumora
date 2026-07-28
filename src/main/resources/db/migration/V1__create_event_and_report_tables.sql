CREATE TABLE wechat_event (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    app_id VARCHAR(64) NOT NULL,
    open_id VARCHAR(128) NOT NULL,
    event_type VARCHAR(32) NOT NULL,
    raw_msg_type VARCHAR(32) NOT NULL,
    raw_event VARCHAR(64) NULL,
    message_id BIGINT NULL,
    original_occurred_at TIMESTAMP(6) NOT NULL,
    effective_occurred_at TIMESTAMP(6) NOT NULL,
    received_at TIMESTAMP(6) NOT NULL,
    anomalous_timestamp BOOLEAN NOT NULL DEFAULT FALSE,
    deduplication_key VARCHAR(71) NOT NULL,
    raw_event_key VARCHAR(512) NULL,
    qr_scene VARCHAR(512) NULL,
    ticket VARCHAR(512) NULL,
    ticket_present BOOLEAN NOT NULL DEFAULT FALSE,
    menu_key VARCHAR(512) NULL,
    menu_url VARCHAR(2048) NULL,
    latitude DECIMAL(10, 7) NULL,
    longitude DECIMAL(10, 7) NULL,
    location_precision DECIMAL(12, 6) NULL,
    composite_type VARCHAR(32) NULL,
    composite_item_count INT NULL,
    composite_sha256 CHAR(64) NULL,
    safe_summary JSON NOT NULL,
    normalized_message_sha256 CHAR(64) NOT NULL,
    created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    CONSTRAINT uq_wechat_event_app_deduplication
        UNIQUE (app_id, deduplication_key),
    INDEX idx_wechat_event_effective_type (effective_occurred_at, event_type),
    INDEX idx_wechat_event_open_effective (open_id, effective_occurred_at)
) ENGINE = InnoDB
  DEFAULT CHARACTER SET = utf8mb4;

CREATE TABLE daily_report (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    report_date DATE NOT NULL,
    version INT NOT NULL,
    window_start TIMESTAMP(6) NOT NULL,
    window_end TIMESTAMP(6) NOT NULL,
    data_cutoff_at TIMESTAMP(6) NOT NULL,
    snapshot_json JSON NOT NULL,
    created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    CONSTRAINT uq_daily_report_date_version UNIQUE (report_date, version)
) ENGINE = InnoDB
  DEFAULT CHARACTER SET = utf8mb4;

CREATE TABLE report_delivery_attempt (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    delivery_id CHAR(36) NOT NULL,
    report_id BIGINT UNSIGNED NOT NULL,
    trigger_type VARCHAR(16) NOT NULL,
    request_id VARCHAR(128) NULL,
    auto_report_id BIGINT UNSIGNED
        GENERATED ALWAYS AS (
            CASE WHEN trigger_type = 'AUTO' THEN report_id ELSE NULL END
        ) STORED,
    status VARCHAR(16) NOT NULL,
    recipient_masked VARCHAR(1024) NOT NULL,
    recipient_sha256 CHAR(64) NOT NULL,
    attempt_count INT NOT NULL DEFAULT 0,
    claimed_at TIMESTAMP(6) NULL,
    lease_until TIMESTAMP(6) NULL,
    completed_at TIMESTAMP(6) NULL,
    last_error_class VARCHAR(128) NULL,
    last_error_summary VARCHAR(500) NULL,
    created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6)
        ON UPDATE CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    CONSTRAINT uq_delivery_attempt_delivery_id UNIQUE (delivery_id),
    CONSTRAINT uq_delivery_attempt_auto_report UNIQUE (auto_report_id),
    CONSTRAINT uq_delivery_attempt_report_request UNIQUE (report_id, request_id),
    CONSTRAINT fk_delivery_attempt_report
        FOREIGN KEY (report_id) REFERENCES daily_report (id)
) ENGINE = InnoDB
  DEFAULT CHARACTER SET = utf8mb4;
