package cn.minglli.lumora.wechat;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;

import cn.minglli.lumora.support.MySqlContainerTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

class JsapiSignatureErrorMapperTest extends MySqlContainerTest {

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

        jdbcTemplate.update(
                "INSERT INTO jsapi_signature_error (url, err_msg, received_at) VALUES (?, ?, ?)",
                "https://lumora.love/posts/old/", "old failure", old);
        jdbcTemplate.update(
                "INSERT INTO jsapi_signature_error (url, err_msg, received_at) VALUES (?, ?, ?)",
                "https://lumora.love/posts/recent/", "recent failure", now);

        int deleted = mapper.deleteOlderThan(now.minus(1, ChronoUnit.DAYS));

        assertThat(deleted).isEqualTo(1);
        List<String> remainingUrls = jdbcTemplate.queryForList(
                "SELECT url FROM jsapi_signature_error ORDER BY url", String.class);
        assertThat(remainingUrls).containsExactly("https://lumora.love/posts/recent/");
    }
}
