package cn.minglli.lumora.event;

import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import cn.minglli.lumora.support.PostgresContainerTest;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class WechatEventRepositoryTest extends PostgresContainerTest {

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
                "id", "bigint|NO|IDENTITY BY DEFAULT",
                "app_id", "varchar(64)|NO|",
                "open_id", "varchar(128)|NO|",
                "event_type", "varchar(32)|NO|",
                "raw_msg_type", "varchar(32)|NO|",
                "raw_event", "varchar(64)|YES|",
                "message_id", "bigint|YES|",
                "original_occurred_at", "timestamp(6)|NO|",
                "effective_occurred_at", "timestamp(6)|NO|",
                "received_at", "timestamp(6)|NO|",
                "anomalous_timestamp", "boolean|NO|DEFAULT false",
                "deduplication_key", "varchar(71)|NO|",
                "raw_event_key", "varchar(2048)|YES|",
                "qr_scene", "varchar(512)|YES|",
                "ticket", "varchar(512)|YES|",
                "ticket_present", "boolean|NO|DEFAULT false",
                "menu_key", "varchar(512)|YES|",
                "menu_url", "varchar(2048)|YES|",
                "latitude", "numeric(10,7)|YES|",
                "longitude", "numeric(10,7)|YES|",
                "location_precision", "numeric(12,6)|YES|",
                "composite_type", "varchar(32)|YES|",
                "composite_item_count", "integer|YES|",
                "composite_sha256", "char(64)|YES|",
                "safe_summary", "jsonb|NO|",
                "normalized_message_sha256", "char(64)|NO|",
                "created_at", "timestamp(6)|NO|DEFAULT CURRENT_TIMESTAMP"));

        assertThat(columns("daily_report")).containsExactlyEntriesOf(mapOf(
                "id", "bigint|NO|IDENTITY BY DEFAULT",
                "report_date", "date|NO|",
                "version", "integer|NO|",
                "window_start", "timestamp(6)|NO|",
                "window_end", "timestamp(6)|NO|",
                "data_cutoff_at", "timestamp(6)|NO|",
                "snapshot_json", "jsonb|NO|",
                "created_at", "timestamp(6)|NO|DEFAULT CURRENT_TIMESTAMP"));

        assertThat(columns("report_delivery_attempt")).containsExactlyEntriesOf(mapOf(
                "id", "bigint|NO|IDENTITY BY DEFAULT",
                "delivery_id", "char(36)|NO|",
                "report_id", "bigint|NO|",
                "trigger_type", "varchar(16)|NO|",
                "request_id", "varchar(128)|YES|",
                "auto_report_id", "bigint|YES|GENERATED ALWAYS",
                "status", "varchar(16)|NO|",
                "recipient_masked", "varchar(1024)|NO|",
                "recipient_sha256", "char(64)|NO|",
                "attempt_count", "integer|NO|DEFAULT 0",
                "claimed_at", "timestamp(6)|YES|",
                "lease_until", "timestamp(6)|YES|",
                "completed_at", "timestamp(6)|YES|",
                "last_error_class", "varchar(128)|YES|",
                "last_error_summary", "varchar(500)|YES|",
                "created_at", "timestamp(6)|NO|DEFAULT CURRENT_TIMESTAMP",
                "updated_at", "timestamp(6)|NO|DEFAULT CURRENT_TIMESTAMP"));

        assertThat(columns("jsapi_signature_error")).containsExactlyEntriesOf(mapOf(
                "id", "bigint|NO|IDENTITY BY DEFAULT",
                "url", "varchar(2048)|NO|",
                "err_msg", "varchar(1024)|NO|",
                "received_at", "timestamp(6)|NO|DEFAULT CURRENT_TIMESTAMP",
                "created_at", "timestamp(6)|NO|DEFAULT CURRENT_TIMESTAMP"));

        // MySQL's "ENGINE=InnoDB" + per-table utf8mb4_* collation don't have a PostgreSQL
        // analogue: there is one storage engine, and collation is a database/column property,
        // not a table option. The properties actually worth guarding here are that the four
        // tables exist in the expected schema and that the database stores full Unicode
        // (the reason the old test cared about utf8mb4 in the first place).
        List<String> tables = jdbcTemplate.queryForList("""
                SELECT tablename
                FROM pg_tables
                WHERE schemaname = 'public'
                  AND tablename IN (
                      'wechat_event',
                      'daily_report',
                      'report_delivery_attempt',
                      'jsapi_signature_error'
                  )
                ORDER BY tablename
                """, String.class);
        assertThat(tables).containsExactly(
                "daily_report", "jsapi_signature_error", "report_delivery_attempt", "wechat_event");

        String encoding = jdbcTemplate.queryForObject("""
                SELECT pg_encoding_to_char(encoding)
                FROM pg_database
                WHERE datname = current_database()
                """, String.class);
        assertThat(encoding).isEqualTo("UTF8");
    }

    @Test
    void createsRequiredIndexesGeneratedColumnAndForeignKey() {
        assertThat(indexes("wechat_event")).containsExactlyInAnyOrderEntriesOf(mapOf(
                "wechat_event_pkey", "UNIQUE:id",
                "uq_event_dedup", "UNIQUE:app_id,deduplication_key",
                "ix_event_report", "NONUNIQUE:effective_occurred_at,event_type",
                "ix_event_user", "NONUNIQUE:open_id,effective_occurred_at",
                "ix_event_received_at", "NONUNIQUE:received_at"));
        assertThat(indexes("daily_report")).containsExactlyInAnyOrderEntriesOf(mapOf(
                "daily_report_pkey", "UNIQUE:id",
                "uq_report_version", "UNIQUE:report_date,version"));
        assertThat(indexes("report_delivery_attempt")).containsExactlyInAnyOrderEntriesOf(mapOf(
                "report_delivery_attempt_pkey", "UNIQUE:id",
                "uq_delivery_id", "UNIQUE:delivery_id",
                "uq_auto_report", "UNIQUE:auto_report_id",
                "uq_manual_request", "UNIQUE:report_id,request_id"));
        // ix_jsapi_error_url 不在这里：indexes() 靠 pg_attribute 解析索引列，而表达式
        // 索引的 indkey 是 0，没有对应的属性行，所以它根本不会出现在结果里。它的
        // 定义单独在下面断言。
        assertThat(indexes("jsapi_signature_error")).containsExactlyInAnyOrderEntriesOf(mapOf(
                "jsapi_signature_error_pkey", "UNIQUE:id",
                "ix_jsapi_error_received_at", "NONUNIQUE:received_at"));

        // MySQL 的前缀索引 url(255) 在 PostgreSQL 只能用表达式索引表达。断言它确实是
        // left(url, 255) 而不是整列 —— 整列 btree 对高熵多字节值会在 INSERT 时报
        // "index row size exceeds btree version 4 maximum"，而这张表恰恰是用来记录
        // 错误的，索引拒绝写入会让错误上报接口自己返回 500。
        String urlIndexDef = jdbcTemplate.queryForObject("""
                SELECT indexdef FROM pg_indexes
                WHERE schemaname = 'public' AND indexname = 'ix_jsapi_error_url'
                """, String.class);
        assertThat(urlIndexDef).contains("\"left\"((url)::text, 255)");

        // PostgreSQL exposes generated-column metadata through the same standard
        // information_schema.columns view as MySQL, just without MySQL's non-standard
        // "extra" column; is_generated/generation_expression cover the same ground.
        Map<String, Object> generatedColumn = jdbcTemplate.queryForMap("""
                SELECT generation_expression, is_generated
                FROM information_schema.columns
                WHERE table_schema = 'public'
                  AND table_name = 'report_delivery_attempt'
                  AND column_name = 'auto_report_id'
                """);
        assertThat(String.valueOf(generatedColumn.get("generation_expression")).toLowerCase())
                .contains("case", "trigger_type", "auto", "report_id");
        assertThat(generatedColumn.get("is_generated")).isEqualTo("ALWAYS");

        // information_schema.key_column_usage has no referenced_table_name/referenced_column_name
        // in PostgreSQL (that's a MySQL extension) — pg_constraint is the portable way to read
        // a single-column foreign key back out.
        Map<String, Object> foreignKey = jdbcTemplate.queryForMap("""
                SELECT con.conname AS constraint_name,
                       att2.attname AS column_name,
                       cl.relname AS referenced_table_name,
                       att.attname AS referenced_column_name
                FROM pg_constraint con
                JOIN pg_class cl ON cl.oid = con.confrelid
                JOIN pg_attribute att ON att.attrelid = con.confrelid AND att.attnum = con.confkey[1]
                JOIN pg_attribute att2 ON att2.attrelid = con.conrelid AND att2.attnum = con.conkey[1]
                WHERE con.conrelid = 'report_delivery_attempt'::regclass
                  AND con.contype = 'f'
                """);
        assertThat(foreignKey).containsEntry("constraint_name", "fk_delivery_report")
                .containsEntry("column_name", "report_id")
                .containsEntry("referenced_table_name", "daily_report")
                .containsEntry("referenced_column_name", "id");
    }

    @Test
    void configuresEveryJdbcSessionForUtc() {
        assertThat(jdbcTemplate.queryForObject("SHOW TIME ZONE", String.class))
                .isEqualTo("UTC");
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
                SELECT column_name, data_type, character_maximum_length,
                       numeric_precision, numeric_scale, datetime_precision,
                       is_nullable, column_default, is_identity, identity_generation,
                       is_generated
                FROM information_schema.columns
                WHERE table_schema = 'public' AND table_name = ?
                ORDER BY ordinal_position
                """, resultSet -> {
            Map<String, String> columns = new LinkedHashMap<>();
            while (resultSet.next()) {
                columns.put(resultSet.getString("column_name"), describeColumn(resultSet));
            }
            return columns;
        }, tableName);
    }

    private String describeColumn(ResultSet resultSet) throws SQLException {
        String suffix;
        if ("YES".equals(resultSet.getString("is_identity"))) {
            suffix = "IDENTITY " + resultSet.getString("identity_generation");
        } else if ("ALWAYS".equals(resultSet.getString("is_generated"))) {
            suffix = "GENERATED ALWAYS";
        } else {
            String defaultValue = resultSet.getString("column_default");
            suffix = defaultValue == null ? "" : "DEFAULT " + defaultValue;
        }
        return formatType(resultSet) + "|" + resultSet.getString("is_nullable") + "|" + suffix;
    }

    private String formatType(ResultSet resultSet) throws SQLException {
        String dataType = resultSet.getString("data_type");
        return switch (dataType) {
            case "character varying" -> "varchar(" + resultSet.getInt("character_maximum_length") + ")";
            case "character" -> "char(" + resultSet.getInt("character_maximum_length") + ")";
            case "numeric" -> "numeric(" + resultSet.getInt("numeric_precision")
                    + "," + resultSet.getInt("numeric_scale") + ")";
            case "timestamp without time zone" -> "timestamp(" + resultSet.getInt("datetime_precision") + ")";
            default -> dataType;
        };
    }

    private Map<String, String> indexes(String tableName) {
        return jdbcTemplate.query("""
                SELECT i.relname AS index_name, ix.indisunique AS is_unique, a.attname AS column_name
                FROM pg_class t
                JOIN pg_index ix ON t.oid = ix.indrelid
                JOIN pg_class i ON i.oid = ix.indexrelid
                JOIN pg_attribute a ON a.attrelid = t.oid
                JOIN LATERAL unnest(ix.indkey) WITH ORDINALITY AS k(attnum, ord) ON a.attnum = k.attnum
                WHERE t.relname = ?
                ORDER BY i.relname, k.ord
                """, resultSet -> {
            Map<String, String> indexes = new LinkedHashMap<>();
            while (resultSet.next()) {
                String name = resultSet.getString("index_name");
                String prefix = resultSet.getBoolean("is_unique") ? "UNIQUE:" : "NONUNIQUE:";
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
