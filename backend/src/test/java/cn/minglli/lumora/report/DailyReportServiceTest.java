package cn.minglli.lumora.report;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicReference;

import cn.minglli.lumora.config.LumoraProperties;
import cn.minglli.lumora.event.EventType;
import cn.minglli.lumora.event.WechatEvent;
import cn.minglli.lumora.event.WechatEventRepository;
import cn.minglli.lumora.support.MySqlContainerTest;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.assertj.core.api.Assertions.assertThat;

class DailyReportServiceTest extends MySqlContainerTest {

    private static final ZoneId SHANGHAI = ZoneId.of("Asia/Shanghai");
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-07-28T02:00:00Z"), SHANGHAI);
    private static final Instant IN_WINDOW = Instant.parse("2026-07-27T05:00:00Z");
    private static final Instant RECEIVED = Instant.parse("2026-07-27T06:00:00Z");
    private static final Instant AFTER_CUTOFF = Instant.parse("2026-07-28T03:00:00Z");

    @Autowired
    private DailyReportMapper mapper;

    @Autowired
    private WechatEventRepository repository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private LumoraProperties properties;

    private DailyReportService service;

    @BeforeEach
    void clear() {
        jdbcTemplate.update("DELETE FROM daily_report");
        jdbcTemplate.update("DELETE FROM wechat_event");
        service = new DailyReportService(mapper, CLOCK, properties, objectMapper);
    }

    @Test
    void aggregatesEveryDimensionIncludingUnknownAndAnomalous() {
        seedFixture();

        DailyReportSnapshot snapshot = service.getOrCreateAutoSnapshot();

        assertThat(snapshot.version()).isOne();
        assertThat(snapshot.totalEvents()).isEqualTo(14L);
        assertThat(snapshot.totalUniqueUsers()).isEqualTo(11L);

        assertThat(typeCount(snapshot, EventType.SUBSCRIBE)).isEqualTo(new DailyReportSnapshot.EventTypeCount(EventType.SUBSCRIBE, 3L, 3L));
        assertThat(typeCount(snapshot, EventType.UNSUBSCRIBE)).isEqualTo(new DailyReportSnapshot.EventTypeCount(EventType.UNSUBSCRIBE, 1L, 1L));
        assertThat(typeCount(snapshot, EventType.SCAN)).isEqualTo(new DailyReportSnapshot.EventTypeCount(EventType.SCAN, 3L, 3L));
        assertThat(typeCount(snapshot, EventType.LOCATION)).isEqualTo(new DailyReportSnapshot.EventTypeCount(EventType.LOCATION, 1L, 1L));
        assertThat(typeCount(snapshot, EventType.MENU_CLICK)).isEqualTo(new DailyReportSnapshot.EventTypeCount(EventType.MENU_CLICK, 2L, 1L));
        assertThat(typeCount(snapshot, EventType.MENU_VIEW)).isEqualTo(new DailyReportSnapshot.EventTypeCount(EventType.MENU_VIEW, 1L, 1L));
        assertThat(typeCount(snapshot, EventType.MENU_OTHER)).isEqualTo(new DailyReportSnapshot.EventTypeCount(EventType.MENU_OTHER, 2L, 1L));
        assertThat(typeCount(snapshot, EventType.UNKNOWN)).isEqualTo(new DailyReportSnapshot.EventTypeCount(EventType.UNKNOWN, 1L, 1L));

        assertThat(snapshot.subscribeEvents()).isEqualTo(3L);
        assertThat(snapshot.subscribeUniqueUsers()).isEqualTo(3L);
        assertThat(snapshot.unsubscribeEvents()).isEqualTo(1L);
        assertThat(snapshot.unsubscribeUniqueUsers()).isEqualTo(1L);
        assertThat(snapshot.netGrowth()).isEqualTo(2L);

        assertThat(snapshot.qrScenes())
                .containsExactlyInAnyOrder(
                        new DailyReportSnapshot.LabelCount("a", 3L, 3L),
                        new DailyReportSnapshot.LabelCount(null, 3L, 3L));
        assertThat(snapshot.menuClickKeys())
                .containsExactlyInAnyOrder(
                        new DailyReportSnapshot.LabelCount("click1", 1L, 1L),
                        new DailyReportSnapshot.LabelCount(null, 1L, 1L));
        assertThat(snapshot.menuViewUrls())
                .containsExactly(new DailyReportSnapshot.LabelCount("http://a.example", 1L, 1L));
        assertThat(snapshot.menuOther())
                .containsExactlyInAnyOrder(
                        new DailyReportSnapshot.MenuOtherCount("scancode_push", "scan1", 1L, 1L),
                        new DailyReportSnapshot.MenuOtherCount("pic_sysphoto", null, 1L, 1L));

        assertThat(snapshot.locationReports()).isOne();
        assertThat(snapshot.locationUniqueUsers()).isOne();
        assertThat(snapshot.anomalousTimestampCount()).isOne();
        assertThat(snapshot.emptyDay()).isFalse();
    }

    @Test
    void excludesRowsReceivedAfterDataCutoff() {
        seedFixture();
        insert(event("late", EventType.SUBSCRIBE, "o99", null, null, null, "subscribe", false, IN_WINDOW, AFTER_CUTOFF));

        DailyReportSnapshot snapshot = service.getOrCreateAutoSnapshot();

        assertThat(snapshot.totalEvents()).isEqualTo(14L);
    }

    @Test
    void autoSnapshotIsImmutableVersionOneAndReuseReturnsSameSnapshot() {
        seedFixture();

        DailyReportSnapshot first = service.getOrCreateAutoSnapshot();
        DailyReportSnapshot second = service.getOrCreateAutoSnapshot();

        assertThat(first.version()).isOne();
        assertThat(second.version()).isOne();
        assertThat(rowVersions()).containsExactly(1);
        assertThat(second.totalEvents()).isEqualTo(first.totalEvents());
    }

    @Test
    void regenerateCreatesNextVersionWithoutChangingPrior() {
        seedFixture();
        service.getOrCreateAutoSnapshot();

        insert(event("late2", EventType.SUBSCRIBE, "o50", null, null, null, "subscribe", false, IN_WINDOW, Instant.parse("2026-07-27T07:00:00Z")));

        DailyReportSnapshot regenerated = service.regenerateSnapshot();

        assertThat(regenerated.version()).isEqualTo(2);
        assertThat(rowVersions()).containsExactlyInAnyOrder(1, 2);
        assertThat(loadVersion(1).totalEvents()).isEqualTo(14L);
        assertThat(regenerated.totalEvents()).isEqualTo(15L);
    }

    @Test
    void concurrentAutoCreatorsConvergeOnOneStoredSnapshot() throws Exception {
        seedFixture();
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        AtomicReference<DailyReportSnapshot> first = new AtomicReference<>();
        AtomicReference<DailyReportSnapshot> second = new AtomicReference<>();
        try {
            Future<?> f1 = executor.submit(() -> first.set(createWhenReleased(ready, start)));
            Future<?> f2 = executor.submit(() -> second.set(createWhenReleased(ready, start)));
            ready.await();
            start.countDown();
            f1.get();
            f2.get();
        } finally {
            executor.shutdownNow();
        }

        assertThat(first.get().version()).isOne();
        assertThat(second.get().version()).isOne();
        assertThat(rowVersions()).containsExactly(1);
        assertThat(first.get().totalEvents()).isEqualTo(second.get().totalEvents());
    }

    private DailyReportSnapshot createWhenReleased(CountDownLatch ready, CountDownLatch start) {
        try {
            ready.countDown();
            start.await();
            return service.getOrCreateAutoSnapshot();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }

    private void seedFixture() {
        insert(event("s1", EventType.SUBSCRIBE, "o1", "a", null, null, "subscribe", false, IN_WINDOW, RECEIVED));
        insert(event("s2", EventType.SUBSCRIBE, "o2", "a", null, null, "subscribe", false, IN_WINDOW, RECEIVED));
        insert(event("sc1", EventType.SCAN, "o3", "a", null, null, "SCAN", false, IN_WINDOW, RECEIVED));
        insert(event("sc2", EventType.SCAN, "o4", null, null, null, "SCAN", false, IN_WINDOW, RECEIVED));
        insert(event("u1", EventType.UNSUBSCRIBE, "o1", null, null, null, "unsubscribe", false, IN_WINDOW, RECEIVED));
        insert(event("s3", EventType.SUBSCRIBE, "o5", null, null, null, "subscribe", false, IN_WINDOW, RECEIVED));
        insert(event("c1", EventType.MENU_CLICK, "o6", null, "click1", null, "CLICK", false, IN_WINDOW, RECEIVED));
        insert(event("c2", EventType.MENU_CLICK, "o6", null, null, null, "CLICK", false, IN_WINDOW, RECEIVED));
        insert(event("v1", EventType.MENU_VIEW, "o7", null, null, "http://a.example", "VIEW", false, IN_WINDOW, RECEIVED));
        insert(event("mo1", EventType.MENU_OTHER, "o8", null, "scan1", null, "scancode_push", false, IN_WINDOW, RECEIVED));
        insert(event("mo2", EventType.MENU_OTHER, "o8", null, null, null, "pic_sysphoto", false, IN_WINDOW, RECEIVED));
        insert(event("loc1", EventType.LOCATION, "o9", null, null, null, "LOCATION", false, IN_WINDOW, RECEIVED));
        insert(event("unk1", EventType.UNKNOWN, "o10", null, null, null, "mystery", false, IN_WINDOW, RECEIVED));
        insert(event("anom1", EventType.SCAN, "o11", null, null, null, "SCAN", true, IN_WINDOW, IN_WINDOW));
    }

    private void insert(WechatEvent event) {
        repository.insert(event);
    }

    private DailyReportSnapshot.EventTypeCount typeCount(DailyReportSnapshot snapshot, EventType type) {
        return snapshot.byEventType().stream()
                .filter(count -> count.eventType() == type)
                .findFirst().orElseThrow();
    }

    private List<Integer> rowVersions() {
        return jdbcTemplate.queryForList(
                "SELECT version FROM daily_report ORDER BY version", Integer.class);
    }

    private DailyReportSnapshot loadVersion(int version) {
        DailyReportRecord record = mapper.findByDateAndVersion(java.time.LocalDate.of(2026, 7, 27), version);
        try {
            return objectMapper.readValue(record.snapshotJson(), DailyReportSnapshot.class);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private WechatEvent event(String suffix, EventType type, String openId,
            String qrScene, String menuKey, String menuUrl, String rawEvent,
            boolean anomalous, Instant effective, Instant received) {
        return new WechatEvent(
                null,
                "wx-app-id",
                openId,
                type,
                "event",
                rawEvent,
                null,
                effective,
                effective,
                received,
                anomalous,
                "dk-" + suffix + "-" + UUID.randomUUID(),
                rawEvent,
                qrScene,
                null,
                qrScene != null,
                menuKey,
                menuUrl,
                null,
                null,
                null,
                null,
                null,
                null,
                "{\"k\":\"v\"}",
                "n-" + suffix,
                null);
    }
}
