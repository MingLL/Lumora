package cn.minglli.lumora.analytics;

import cn.minglli.lumora.support.PostgresContainerTest;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import static org.assertj.core.api.Assertions.assertThat;

@Transactional
class AnalyticsIntegrationTest extends PostgresContainerTest {
    @Autowired JdbcTemplate jdbc;
    @Autowired AnalyticsController controller;

    @Test void aggregatesPageOpensDeduplicatesRetriesAndUsesShanghaiDayBoundaries() {
        jdbc.update("DELETE FROM client_event");
        insert("a", "PAGE_OPEN", "/posts/one?utm=test", "1.2.3.4", "2026-09-28T16:00:00Z");
        insert("a", "PAGE_OPEN", "/posts/one?utm=test", "1.2.3.4", "2026-09-28T16:00:01Z");
        insert("a", "NETWORK_TYPE", "/posts/one", "1.2.3.4", "2026-09-28T16:00:02Z");
        insert("b", "PAGE_OPEN", "/posts/two/", "1.2.3.4", "2026-09-29T02:00:00Z");
        insert("c", "PAGE_OPEN", "/posts/one", "2.3.4.5", "2026-09-29T03:00:00Z");
        insert("old", "PAGE_OPEN", "/", null, "2026-09-29T04:00:00Z");
        insert("outside", "PAGE_OPEN", "/posts/one", "1.2.3.4", "2026-09-29T16:00:00Z");
        var day = LocalDate.of(2026, 9, 29);
        var summary = controller.summary(day, day).getBody();
        var totals = (Map<?, ?>) summary.get("totals");
        assertThat(totals.get("pv")).isEqualTo(4L);
        assertThat(totals.get("ips")).isEqualTo(2L);
        assertThat(totals.get("article_views")).isEqualTo(3L);
        assertThat(totals.get("unknown_ip_views")).isEqualTo(1L);
        var articles = (List<Map<String, Object>>) summary.get("articles");
        assertThat(articles.get(0).get("path")).isEqualTo("/posts/one");
        assertThat(articles.get(0).get("pv")).isEqualTo(2L);
        var visits = controller.visits(day, day, "1.2.3.4", 0).getBody();
        assertThat(visits.get("total")).isEqualTo(2L);
        var rows = (List<Map<String, Object>>) visits.get("rows");
        assertThat(rows.get(0).get("path")).isEqualTo("/posts/two/");
        assertThat(rows.get(1).get("visited_at")).isEqualTo("2026-09-29 00:00:00");
        assertThat((List<?>) controller.visits(day, day, "1.2.3.4", 1).getBody().get("rows")).isEmpty();
    }
    private void insert(String visit, String type, String path, String ip, String timestamp) {
        jdbc.update("INSERT INTO client_event (visit_id,type,url,client_ip,received_at) VALUES (?,?,?,?,CAST(? AS timestamptz))",
                visit, type, "https://lumora.love" + path, ip, timestamp);
    }
}
