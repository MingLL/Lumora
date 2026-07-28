package cn.minglli.lumora.event;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import cn.minglli.lumora.support.MySqlContainerTest;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class WechatEventRepositoryTest extends MySqlContainerTest {

    @Autowired
    private WechatEventRepository repository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private ObjectMapper objectMapper;

    @BeforeEach
    void clearEvents() {
        jdbcTemplate.update("DELETE FROM wechat_event");
    }

    @Test
    void createsEveryRequiredTableColumn() {
        assertThat(columns("wechat_event")).containsExactlyEntriesOf(mapOf(
                "id", "bigint unsigned|NO|auto_increment",
                "app_id", "varchar(64)|NO|",
                "open_id", "varchar(128)|NO|",
                "event_type", "varchar(32)|NO|",
                "raw_msg_type", "varchar(32)|NO|",
                "raw_event", "varchar(64)|YES|",
                "message_id", "bigint|YES|",
                "original_occurred_at", "timestamp(6)|NO|",
                "effective_occurred_at", "timestamp(6)|NO|",
                "received_at", "timestamp(6)|NO|",
                "anomalous_timestamp", "tinyint(1)|NO|DEFAULT 0",
                "deduplication_key", "varchar(71)|NO|",
                "raw_event_key", "varchar(2048)|YES|",
                "qr_scene", "varchar(512)|YES|",
                "ticket", "varchar(512)|YES|",
                "ticket_present", "tinyint(1)|NO|DEFAULT 0",
                "menu_key", "varchar(512)|YES|",
                "menu_url", "varchar(2048)|YES|",
                "latitude", "decimal(10,7)|YES|",
                "longitude", "decimal(10,7)|YES|",
                "location_precision", "decimal(12,6)|YES|",
                "composite_type", "varchar(32)|YES|",
                "composite_item_count", "int|YES|",
                "composite_sha256", "char(64)|YES|",
                "safe_summary", "json|NO|",
                "normalized_message_sha256", "char(64)|NO|",
                "created_at", "timestamp(6)|NO|DEFAULT_GENERATED DEFAULT CURRENT_TIMESTAMP(6)"));

        assertThat(columns("daily_report")).containsExactlyEntriesOf(mapOf(
                "id", "bigint unsigned|NO|auto_increment",
                "report_date", "date|NO|",
                "version", "int|NO|",
                "window_start", "timestamp(6)|NO|",
                "window_end", "timestamp(6)|NO|",
                "data_cutoff_at", "timestamp(6)|NO|",
                "snapshot_json", "json|NO|",
                "created_at", "timestamp(6)|NO|DEFAULT_GENERATED DEFAULT CURRENT_TIMESTAMP(6)"));

        assertThat(columns("report_delivery_attempt")).containsExactlyEntriesOf(mapOf(
                "id", "bigint unsigned|NO|auto_increment",
                "delivery_id", "char(36)|NO|",
                "report_id", "bigint unsigned|NO|",
                "trigger_type", "varchar(16)|NO|",
                "request_id", "varchar(128)|YES|",
                "auto_report_id", "bigint unsigned|YES|STORED GENERATED",
                "status", "varchar(16)|NO|",
                "recipient_masked", "varchar(1024)|NO|",
                "recipient_sha256", "char(64)|NO|",
                "attempt_count", "int|NO|DEFAULT 0",
                "claimed_at", "timestamp(6)|YES|",
                "lease_until", "timestamp(6)|YES|",
                "completed_at", "timestamp(6)|YES|",
                "last_error_class", "varchar(128)|YES|",
                "last_error_summary", "varchar(500)|YES|",
                "created_at", "timestamp(6)|NO|DEFAULT_GENERATED DEFAULT CURRENT_TIMESTAMP(6)",
                "updated_at", "timestamp(6)|NO|DEFAULT_GENERATED on update CURRENT_TIMESTAMP(6) DEFAULT CURRENT_TIMESTAMP(6)"));

        List<Map<String, Object>> tableOptions = jdbcTemplate.queryForList("""
                SELECT table_name, engine, table_collation
                FROM information_schema.tables
                WHERE table_schema = DATABASE()
                  AND table_name IN (
                      'wechat_event',
                      'daily_report',
                      'report_delivery_attempt'
                  )
                ORDER BY table_name
                """);
        assertThat(tableOptions).hasSize(3).allSatisfy(options -> {
            assertThat(options.get("engine")).isEqualTo("InnoDB");
            assertThat(options.get("table_collation").toString()).startsWith("utf8mb4_");
        });
    }

    @Test
    void createsRequiredIndexesGeneratedColumnAndForeignKey() {
        assertThat(indexes("wechat_event")).containsExactlyInAnyOrderEntriesOf(mapOf(
                "PRIMARY", "UNIQUE:id",
                "uq_event_dedup", "UNIQUE:app_id,deduplication_key",
                "ix_event_report", "NONUNIQUE:effective_occurred_at,event_type",
                "ix_event_user", "NONUNIQUE:open_id,effective_occurred_at",
                "ix_event_received_at", "NONUNIQUE:received_at"));
        assertThat(indexes("daily_report")).containsExactlyInAnyOrderEntriesOf(mapOf(
                "PRIMARY", "UNIQUE:id",
                "uq_report_version", "UNIQUE:report_date,version"));
        assertThat(indexes("report_delivery_attempt")).containsExactlyInAnyOrderEntriesOf(mapOf(
                "PRIMARY", "UNIQUE:id",
                "uq_delivery_id", "UNIQUE:delivery_id",
                "uq_auto_report", "UNIQUE:auto_report_id",
                "uq_manual_request", "UNIQUE:report_id,request_id"));

        Map<String, Object> generatedColumn = jdbcTemplate.queryForMap("""
                SELECT generation_expression, extra
                FROM information_schema.columns
                WHERE table_schema = DATABASE()
                  AND table_name = 'report_delivery_attempt'
                  AND column_name = 'auto_report_id'
                """);
        assertThat(String.valueOf(generatedColumn.get("generation_expression")).toLowerCase())
                .contains("case", "trigger_type", "auto", "report_id");
        assertThat(generatedColumn.get("extra")).isEqualTo("STORED GENERATED");

        Map<String, Object> foreignKey = jdbcTemplate.queryForMap("""
                SELECT constraint_name, column_name,
                       referenced_table_name, referenced_column_name
                FROM information_schema.key_column_usage
                WHERE table_schema = DATABASE()
                  AND table_name = 'report_delivery_attempt'
                  AND referenced_table_name IS NOT NULL
                """);
        assertThat(foreignKey).containsEntry("constraint_name", "fk_delivery_report")
                .containsEntry("column_name", "report_id")
                .containsEntry("referenced_table_name", "daily_report")
                .containsEntry("referenced_column_name", "id");
    }

    @Test
    void configuresEveryJdbcSessionForUtc() {
        assertThat(jdbcTemplate.queryForObject("SELECT @@session.time_zone", String.class))
                .isEqualTo("+00:00");
    }

    @Test
    void insertsAndReadsBackEveryEventFieldIncludingTicketAndMicroseconds() throws Exception {
        WechatEvent event = completeEvent("full-round-trip");

        assertThat(repository.insert(event))
                .isEqualTo(WechatEventRepository.InsertResult.INSERTED);

        WechatEvent stored = repository.findByAppIdAndDeduplicationKey(
                event.appId(), event.deduplicationKey()).orElseThrow();
        assertThat(stored.id()).isPositive();
        assertThat(stored.appId()).isEqualTo(event.appId());
        assertThat(stored.openId()).isEqualTo(event.openId());
        assertThat(stored.eventType()).isEqualTo(event.eventType());
        assertThat(stored.rawMsgType()).isEqualTo(event.rawMsgType());
        assertThat(stored.rawEvent()).isEqualTo(event.rawEvent());
        assertThat(stored.messageId()).isEqualTo(event.messageId());
        assertThat(stored.originalOccurredAt()).isEqualTo(event.originalOccurredAt());
        assertThat(stored.effectiveOccurredAt()).isEqualTo(event.effectiveOccurredAt());
        assertThat(stored.receivedAt()).isEqualTo(event.receivedAt());
        assertThat(stored.anomalousTimestamp()).isEqualTo(event.anomalousTimestamp());
        assertThat(stored.deduplicationKey()).isEqualTo(event.deduplicationKey());
        assertThat(stored.rawEventKey()).isEqualTo(event.rawEventKey());
        assertThat(stored.qrScene()).isEqualTo(event.qrScene());
        assertThat(stored.ticket()).isEqualTo("ticket-for-round-trip");
        assertThat(stored.ticketPresent()).isTrue();
        assertThat(stored.menuKey()).isEqualTo(event.menuKey());
        assertThat(stored.menuUrl()).isEqualTo(event.menuUrl());
        assertThat(stored.latitude()).isEqualByComparingTo(event.latitude());
        assertThat(stored.longitude()).isEqualByComparingTo(event.longitude());
        assertThat(stored.locationPrecision()).isEqualByComparingTo(event.locationPrecision());
        assertThat(stored.compositeType()).isEqualTo(event.compositeType());
        assertThat(stored.compositeItemCount()).isEqualTo(event.compositeItemCount());
        assertThat(stored.compositeSha256()).isEqualTo(event.compositeSha256());
        assertThat(objectMapper.readTree(stored.safeSummary()))
                .isEqualTo(objectMapper.readTree(event.safeSummary()));
        assertThat(stored.normalizedMessageSha256()).isEqualTo(event.normalizedMessageSha256());
        assertThat(stored.createdAt()).isNotNull();
    }

    @Test
    void reportsSequentialDuplicateAsNormalResult() {
        WechatEvent event = completeEvent("sequential-duplicate");

        assertThat(repository.insert(event))
                .isEqualTo(WechatEventRepository.InsertResult.INSERTED);
        assertThat(repository.insert(event))
                .isEqualTo(WechatEventRepository.InsertResult.DUPLICATE);
        assertThat(count(event)).isOne();
    }

    @Test
    void concurrentDuplicatesProduceExactlyOneRow() throws Exception {
        WechatEvent event = completeEvent("concurrent-duplicate");
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);

        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<WechatEventRepository.InsertResult> first =
                    executor.submit(() -> insertWhenReleased(event, ready, start));
            Future<WechatEventRepository.InsertResult> second =
                    executor.submit(() -> insertWhenReleased(event, ready, start));

            ready.await();
            start.countDown();

            assertThat(List.of(first.get(), second.get()))
                    .containsExactlyInAnyOrder(
                            WechatEventRepository.InsertResult.INSERTED,
                            WechatEventRepository.InsertResult.DUPLICATE);
        } finally {
            executor.shutdownNow();
        }

        assertThat(count(event)).isOne();
    }

    @Test
    void doesNotConvertUnrelatedDatabaseErrorsToDuplicate() {
        WechatEvent invalid = completeEvent("invalid-app-id");
        invalid = new WechatEvent(
                invalid.id(), "x".repeat(65), invalid.openId(), invalid.eventType(),
                invalid.rawMsgType(), invalid.rawEvent(), invalid.messageId(),
                invalid.originalOccurredAt(), invalid.effectiveOccurredAt(), invalid.receivedAt(),
                invalid.anomalousTimestamp(), invalid.deduplicationKey(), invalid.rawEventKey(),
                invalid.qrScene(), invalid.ticket(), invalid.ticketPresent(), invalid.menuKey(),
                invalid.menuUrl(), invalid.latitude(), invalid.longitude(),
                invalid.locationPrecision(), invalid.compositeType(), invalid.compositeItemCount(),
                invalid.compositeSha256(), invalid.safeSummary(),
                invalid.normalizedMessageSha256(), invalid.createdAt());

        WechatEvent finalInvalid = invalid;
        assertThatThrownBy(() -> repository.insert(finalInvalid))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    private WechatEventRepository.InsertResult insertWhenReleased(
            WechatEvent event, CountDownLatch ready, CountDownLatch start) throws InterruptedException {
        ready.countDown();
        start.await();
        return repository.insert(event);
    }

    private long count(WechatEvent event) {
        return jdbcTemplate.queryForObject("""
                SELECT COUNT(*)
                FROM wechat_event
                WHERE app_id = ? AND deduplication_key = ?
                """, Long.class, event.appId(), event.deduplicationKey());
    }

    private Map<String, String> columns(String tableName) {
        return jdbcTemplate.query("""
                SELECT column_name, column_type, is_nullable, column_default, extra
                FROM information_schema.columns
                WHERE table_schema = DATABASE() AND table_name = ?
                ORDER BY ordinal_position
                """, resultSet -> {
            Map<String, String> columns = new LinkedHashMap<>();
            while (resultSet.next()) {
                String defaultValue = resultSet.getString("column_default");
                String extra = resultSet.getString("extra");
                String suffix = "";
                if (defaultValue != null) {
                    suffix += "DEFAULT " + defaultValue;
                }
                if (extra != null && !extra.isBlank()) {
                    suffix = suffix.isBlank() ? extra : extra + " " + suffix;
                }
                columns.put(resultSet.getString("column_name"),
                        resultSet.getString("column_type")
                                + "|" + resultSet.getString("is_nullable")
                                + "|" + suffix);
            }
            return columns;
        }, tableName);
    }

    private Map<String, String> indexes(String tableName) {
        return jdbcTemplate.query("""
                SELECT index_name, non_unique, column_name
                FROM information_schema.statistics
                WHERE table_schema = DATABASE() AND table_name = ?
                ORDER BY index_name, seq_in_index
                """, resultSet -> {
            Map<String, String> indexes = new LinkedHashMap<>();
            while (resultSet.next()) {
                String name = resultSet.getString("index_name");
                String prefix = resultSet.getBoolean("non_unique") ? "NONUNIQUE:" : "UNIQUE:";
                indexes.merge(name, prefix + resultSet.getString("column_name"),
                        (current, next) -> current + "," + next.substring(next.indexOf(':') + 1));
            }
            return indexes;
        }, tableName);
    }

    private static Map<String, String> mapOf(String... values) {
        Map<String, String> result = new LinkedHashMap<>();
        for (int index = 0; index < values.length; index += 2) {
            result.put(values[index], values[index + 1]);
        }
        return result;
    }

    private static WechatEvent completeEvent(String deduplicationKey) {
        return new WechatEvent(
                null,
                "wx-app-id",
                "openid-123",
                EventType.SCAN,
                "event",
                "SCAN",
                987654321L,
                Instant.parse("2026-07-27T01:02:03.123456Z"),
                Instant.parse("2026-07-27T01:02:04.234567Z"),
                Instant.parse("2026-07-27T01:02:05.345678Z"),
                true,
                deduplicationKey,
                "raw-event-key",
                "qr-scene",
                "ticket-for-round-trip",
                true,
                "menu-key",
                "https://example.com/menu",
                new BigDecimal("31.2304167"),
                new BigDecimal("121.4737012"),
                new BigDecimal("12.345678"),
                "news",
                2,
                "a".repeat(64),
                "{\"kind\":\"event\",\"safe\":true}",
                "b".repeat(64),
                null);
    }
}
