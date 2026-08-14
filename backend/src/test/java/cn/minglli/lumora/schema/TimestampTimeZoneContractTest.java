package cn.minglli.lumora.schema;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.PreparedStatement;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.Calendar;
import java.util.List;
import java.util.Map;
import java.util.TimeZone;

import cn.minglli.lumora.support.PostgresContainerTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * 锁住「时间列必须带时区」这个不变量。
 *
 * <p>背景：时间列原本是 {@code TIMESTAMP(6)}，也就是 PG 的 timestamp without time
 * zone —— 列里只有墙上时钟读数，没有偏移量。于是同一张表被两条互不相干的写入路径
 * 填充，各自按各自的时区折算：
 *
 * <ul>
 *   <li>数据库默认值和触发器里的 {@code CURRENT_TIMESTAMP} 按<b>会话时区</b>折算，
 *       会话时区由 application.yml 的 connection-init-sql 钉成 UTC；</li>
 *   <li>应用经 MyBatis {@code InstantTypeHandler} 写入的列走
 *       {@code ps.setTimestamp(i, Timestamp.from(instant))} —— 不带 Calendar，
 *       pgjdbc 会用 <b>JVM 默认时区</b>把它格式化成本地墙钟。会话时区管不到这条路。</li>
 * </ul>
 *
 * <p>两条路以前一致，纯粹是因为 eclipse-temurin 基础镜像的默认时区恰好也是 UTC。
 * 给容器加一个 {@code TZ=Asia/Shanghai}（为了让日志显示北京时间，很自然的举动）
 * 就会让它们错开 8 小时：同一行里两套时间，保留任务按 cutoff 多删 8 小时数据，
 * 日报口径整体偏移。改成 TIMESTAMPTZ 之后偏移量存进列里，JVM 时区不再参与正确性。
 */
class TimestampTimeZoneContractTest extends PostgresContainerTest {

    @Autowired private JdbcTemplate jdbcTemplate;

    @Test
    void noApplicationTableStoresANaiveTimestamp() {
        // flyway_schema_history 是 Flyway 自己建的，它的 installed_on 就是 naive
        // timestamp，我们既管不着也不需要管，排掉。
        List<String> naive = jdbcTemplate.queryForList(
                """
                SELECT table_name || '.' || column_name
                FROM information_schema.columns
                WHERE table_schema = current_schema()
                  AND table_name <> 'flyway_schema_history'
                  AND data_type = 'timestamp without time zone'
                ORDER BY 1
                """,
                String.class);

        assertThat(naive)
                .as("这些列没有时区，落库结果会随写入方所在时区漂移；新增迁移时请用 TIMESTAMPTZ")
                .isEmpty();
    }

    @Test
    void applicationWrittenAndDatabaseGeneratedTimestampsAgreeOnTheSameMoment() {
        // wechat_event 一行里同时有两条写入路径：received_at 由应用写，created_at 由
        // DEFAULT CURRENT_TIMESTAMP 写。两者取的是同一瞬间，读回来必须仍是同一瞬间。
        Instant moment = Instant.now();
        String deduplicationKey = "tz-contract-" + moment.toEpochMilli();

        // 显式传 Asia/Shanghai 的 Calendar。pgjdbc 的 setTimestamp(i, t) 只是
        // setTimestamp(i, t, null)，null 时取 JVM 默认时区的 Calendar —— 所以这里
        // 传上海和「JVM 默认时区就是上海」产生的绑定值逐字节相同，是忠实模拟而非
        // 人造场景。不改 TimeZone.setDefault()，因为那会污染并发跑的其他测试，
        // 而且 pgjdbc 是否即时感知默认时区的变化属于实现细节，不该被依赖。
        Calendar shanghai = Calendar.getInstance(TimeZone.getTimeZone("Asia/Shanghai"));

        jdbcTemplate.execute((ConnectionCallback<Void>) connection -> {
            try (PreparedStatement statement = connection.prepareStatement(
                    """
                    INSERT INTO wechat_event (
                        app_id, open_id, event_type, raw_msg_type,
                        original_occurred_at, effective_occurred_at, received_at,
                        deduplication_key, safe_summary, normalized_message_sha256
                    ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb, ?)
                    """)) {
                statement.setString(1, "wx-app-id");
                statement.setString(2, "o-tz-contract");
                statement.setString(3, "SUBSCRIBE");
                statement.setString(4, "event");
                statement.setTimestamp(5, Timestamp.from(moment), shanghai);
                statement.setTimestamp(6, Timestamp.from(moment), shanghai);
                statement.setTimestamp(7, Timestamp.from(moment), shanghai);
                statement.setString(8, deduplicationKey);
                statement.setString(9, "{}");
                statement.setString(10, "0".repeat(64));
                statement.executeUpdate();
            }
            return null;
        });

        Map<String, Object> row = jdbcTemplate.queryForMap(
                "SELECT received_at, created_at FROM wechat_event WHERE deduplication_key = ?",
                deduplicationKey);
        // 两列用同一个默认 Calendar 读回，所以它们的差值与读取方所在时区无关。
        Instant receivedAt = ((Timestamp) row.get("received_at")).toInstant();
        Instant createdAt = ((Timestamp) row.get("created_at")).toInstant();

        assertThat(Duration.between(receivedAt, createdAt).abs())
                .as("应用写的 received_at 与数据库写的 created_at 相差一个整时区偏移，"
                        + "说明两条写入路径各按各的时区落库")
                .isLessThan(Duration.ofMinutes(1));
    }
}
