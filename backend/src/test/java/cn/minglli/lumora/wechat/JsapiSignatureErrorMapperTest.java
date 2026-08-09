package cn.minglli.lumora.wechat;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;

import cn.minglli.lumora.support.PostgresContainerTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

class JsapiSignatureErrorMapperTest extends PostgresContainerTest {

    @Autowired
    private JsapiSignatureErrorMapper mapper;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void clearErrors() {
        jdbcTemplate.update("DELETE FROM jsapi_signature_error");
    }

    @Test
    void insertStoresUrlAndErrMsgWithDatabaseGeneratedTimestamps() {
        mapper.insert(new JsapiSignatureErrorRecord(
                null,
                "https://lumora.love/posts/twenty-eight/",
                "invalid signature",
                null,
                null));

        Map<String, Object> row = jdbcTemplate.queryForMap("""
                SELECT url, err_msg, received_at, created_at
                FROM jsapi_signature_error
                WHERE url = ?
                """, "https://lumora.love/posts/twenty-eight/");

        assertThat(row.get("url")).isEqualTo("https://lumora.love/posts/twenty-eight/");
        assertThat(row.get("err_msg")).isEqualTo("invalid signature");
        assertThat(row.get("received_at")).isNotNull();
        assertThat(row.get("created_at")).isNotNull();
    }

    @Test
    void deleteOlderThanRemovesOnlyRowsBeforeCutoff() {
        Instant now = Instant.parse("2026-08-06T12:00:00Z");
        Instant old = now.minus(500, ChronoUnit.DAYS);

        // 这两条夹具走的是裸 JDBC，不是 MyBatis，所以要自己把 Instant 转成驱动认识的
        // 类型：pgjdbc 对 java.time.Instant 直接报 "Can't infer the SQL type"，而
        // MySQL Connector/J 会替你推断 —— 换库后这里才暴露。列是 TIMESTAMP(6)（无时区），
        // 用 LocalDateTime + ZoneOffset.UTC 明确对齐，不依赖 JVM 默认时区。
        // 生产路径不受影响：mapper 走 MyBatis，Instant 由它的类型处理器负责。
        jdbcTemplate.update(
                "INSERT INTO jsapi_signature_error (url, err_msg, received_at) VALUES (?, ?, ?)",
                "https://lumora.love/posts/old/", "old failure",
                LocalDateTime.ofInstant(old, ZoneOffset.UTC));
        jdbcTemplate.update(
                "INSERT INTO jsapi_signature_error (url, err_msg, received_at) VALUES (?, ?, ?)",
                "https://lumora.love/posts/recent/", "recent failure",
                LocalDateTime.ofInstant(now, ZoneOffset.UTC));

        int deleted = mapper.deleteOlderThan(now.minus(1, ChronoUnit.DAYS));

        assertThat(deleted).isEqualTo(1);
        List<String> remainingUrls = jdbcTemplate.queryForList(
                "SELECT url FROM jsapi_signature_error ORDER BY url", String.class);
        assertThat(remainingUrls).containsExactly("https://lumora.love/posts/recent/");
    }
}
