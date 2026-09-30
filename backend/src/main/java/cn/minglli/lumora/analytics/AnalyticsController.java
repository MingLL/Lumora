package cn.minglli.lumora.analytics;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;

import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

@RestController
public class AnalyticsController {
    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");
    // Collapse retries and strip query strings/fragments from historical URLs.
    private static final String VIEWS = """
            WITH views AS (
                SELECT DISTINCT ON (visit_id) id, client_ip, referrer_host, received_at,
                    regexp_replace(split_part(split_part(url, '?', 1), '#', 1),
                        '^https?://[^/]+', '') AS path
                FROM client_event
                WHERE type = 'PAGE_OPEN' AND received_at >= ? AND received_at < ?
                ORDER BY visit_id, received_at, id
            )
            """;
    private final JdbcTemplate jdbc;
    private final Clock clock;

    public AnalyticsController(JdbcTemplate jdbc, Clock clock) {
        this.jdbc = jdbc;
        this.clock = clock;
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    @GetMapping("/api/analytics/summary")
    public ResponseEntity<Map<String, Object>> summary(
            @RequestParam(required = false) LocalDate from,
            @RequestParam(required = false) LocalDate to) {
        Window window = window(from, to);
        Map<String, Object> totals = jdbc.queryForMap(VIEWS + """
                SELECT count(*) AS pv, count(DISTINCT client_ip) AS ips,
                    count(*) FILTER (WHERE path LIKE '/posts/%') AS article_views,
                    count(*) FILTER (WHERE client_ip IS NULL) AS unknown_ip_views
                FROM views
                """, window.start(), window.end());
        List<Map<String, Object>> trend = query(window, """
                SELECT to_char(received_at AT TIME ZONE 'Asia/Shanghai', 'YYYY-MM-DD') AS day,
                    count(*) AS pv, count(DISTINCT client_ip) AS ips
                FROM views GROUP BY day ORDER BY day
                """);
        List<Map<String, Object>> articles = query(window, """
                SELECT regexp_replace(path, '/$', '') AS path, count(*) AS pv,
                    count(DISTINCT client_ip) AS ips
                FROM views WHERE path LIKE '/posts/%'
                GROUP BY regexp_replace(path, '/$', '') ORDER BY pv DESC, path LIMIT 100
                """);
        List<Map<String, Object>> sources = query(window, """
                SELECT referrer_host AS source, count(*) AS pv FROM views
                GROUP BY referrer_host ORDER BY pv DESC, referrer_host NULLS LAST LIMIT 100
                """);
        List<Map<String, Object>> visitors = query(window, """
                SELECT client_ip AS ip, count(*) AS pv,
                    count(DISTINCT regexp_replace(path, '/$', '')) FILTER (WHERE path LIKE '/posts/%') AS articles,
                    to_char(max(received_at) AT TIME ZONE 'Asia/Shanghai', 'YYYY-MM-DD HH24:MI:SS') AS last_seen
                FROM views WHERE client_ip IS NOT NULL
                GROUP BY client_ip ORDER BY pv DESC, client_ip LIMIT 100
                """);
        return response(Map.of("from", window.from(), "to", window.to(), "totals", totals,
                "trend", trend, "articles", articles, "sources", sources, "visitors", visitors));
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    @GetMapping("/api/analytics/visits")
    public ResponseEntity<Map<String, Object>> visits(
            @RequestParam(required = false) LocalDate from,
            @RequestParam(required = false) LocalDate to,
            @RequestParam(defaultValue = "") String ip,
            @RequestParam(defaultValue = "0") int page) {
        if (page < 0 || page > 100000 || ip.length() > 45
                || (!ip.isEmpty() && !ip.matches("[0-9a-fA-F:.]+"))) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid IP or page");
        }
        Window window = window(from, to);
        String filter = " FROM views WHERE (? = '' OR client_ip = ?) ";
        Long total = jdbc.queryForObject(VIEWS + "SELECT count(*)" + filter,
                Long.class, window.start(), window.end(), ip, ip);
        List<Map<String, Object>> rows = jdbc.queryForList(VIEWS + """
                SELECT client_ip AS ip, path, referrer_host AS source,
                    to_char(received_at AT TIME ZONE 'Asia/Shanghai', 'YYYY-MM-DD HH24:MI:SS') AS visited_at
                """ + filter + "ORDER BY received_at DESC, id DESC LIMIT 50 OFFSET ?",
                window.start(), window.end(), ip, ip, page * 50);
        return response(Map.of("total", total, "page", page, "pageSize", 50, "rows", rows));
    }

    private List<Map<String, Object>> query(Window window, String sql) {
        return jdbc.queryForList(VIEWS + sql, window.start(), window.end());
    }

    private Window window(LocalDate from, LocalDate to) {
        LocalDate end = to == null ? LocalDate.now(clock.withZone(ZONE)) : to;
        LocalDate start = from == null ? end.minusDays(6) : from;
        if (start.isAfter(end) || ChronoUnit.DAYS.between(start, end) > 92
                || start.getYear() < 2000 || end.getYear() > 2100) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Date range must be 1–93 days");
        }
        return new Window(start, end, Timestamp.from(start.atStartOfDay(ZONE).toInstant()),
                Timestamp.from(end.plusDays(1).atStartOfDay(ZONE).toInstant()));
    }

    private static ResponseEntity<Map<String, Object>> response(Map<String, Object> body) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(body);
    }

    private record Window(LocalDate from, LocalDate to, Timestamp start, Timestamp end) { }
}
