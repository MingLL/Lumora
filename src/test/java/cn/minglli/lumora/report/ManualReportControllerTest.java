package cn.minglli.lumora.report;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;

import cn.minglli.lumora.config.LumoraProperties;
import cn.minglli.lumora.operations.AdminKeyInterceptor;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class ManualReportControllerTest {

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-07-28T02:00:00Z"), ZoneId.of("Asia/Shanghai"));
    private static final String ADMIN_KEY = "admin-secret";

    private DailyReportService dailyReportService;
    private DailyReportMapper dailyReportMapper;
    private ReportDeliveryService deliveryService;
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        dailyReportService = mock(DailyReportService.class);
        dailyReportMapper = mock(DailyReportMapper.class);
        deliveryService = mock(ReportDeliveryService.class);
        LumoraProperties properties = new LumoraProperties();
        properties.setReportAdminKey(ADMIN_KEY);
        properties.setZone(ZoneId.of("Asia/Shanghai"));
        ManualReportController controller = new ManualReportController(
                dailyReportService, dailyReportMapper, deliveryService, properties, CLOCK);
        AdminKeyInterceptor interceptor = new AdminKeyInterceptor(properties);
        mvc = MockMvcBuilders.standaloneSetup(controller)
                .addInterceptors(interceptor)
                .build();
    }

    @Test
    void validSentReportReturnsOk() throws Exception {
        LocalDate date = LocalDate.of(2026, 7, 27);
        when(dailyReportService.getOrCreateSnapshotForDate(eq(date), anyBoolean())).thenReturn(snapshot(date));
        when(dailyReportMapper.findByDateAndVersion(eq(date), anyInt())).thenReturn(record(date));
        when(deliveryService.sendManual(anyLong(), eq("req-1"), anyBoolean()))
                .thenReturn(sent("delivery-1"));

        mvc.perform(post("/internal/reports/{date}/send", date)
                        .header("X-Lumora-Admin-Key", ADMIN_KEY)
                        .header("X-Request-Id", "req-1")
                        .contentType("application/json")
                        .content("{}"))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("SENT")));
    }

    @Test
    void regenerateFlagCreatesNewSnapshotVersion() throws Exception {
        LocalDate date = LocalDate.of(2026, 7, 27);
        when(dailyReportService.getOrCreateSnapshotForDate(eq(date), eq(true))).thenReturn(snapshot(date));
        when(dailyReportMapper.findByDateAndVersion(eq(date), anyInt())).thenReturn(record(date));
        when(deliveryService.sendManual(anyLong(), eq("req-2"), anyBoolean()))
                .thenReturn(sent("delivery-2"));

        mvc.perform(post("/internal/reports/{date}/send", date)
                        .header("X-Lumora-Admin-Key", ADMIN_KEY)
                        .header("X-Request-Id", "req-2")
                        .contentType("application/json")
                        .content("{\"regenerate\":true}"))
                .andExpect(status().isOk());
    }

    @Test
    void todayOrFutureDateIsRejected() throws Exception {
        mvc.perform(post("/internal/reports/{date}/send", LocalDate.of(2026, 7, 28))
                        .header("X-Lumora-Admin-Key", ADMIN_KEY)
                        .header("X-Request-Id", "req-3")
                        .contentType("application/json")
                        .content("{}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void missingAdminKeyIsUnauthorized() throws Exception {
        mvc.perform(post("/internal/reports/{date}/send", LocalDate.of(2026, 7, 27))
                        .header("X-Request-Id", "req-4")
                        .contentType("application/json")
                        .content("{}"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void missingRequestIdIsBadRequest() throws Exception {
        mvc.perform(post("/internal/reports/{date}/send", LocalDate.of(2026, 7, 27))
                        .header("X-Lumora-Admin-Key", ADMIN_KEY)
                        .contentType("application/json")
                        .content("{}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void activeDeliveryConflictReturns409() throws Exception {
        LocalDate date = LocalDate.of(2026, 7, 27);
        when(dailyReportService.getOrCreateSnapshotForDate(eq(date), anyBoolean())).thenReturn(snapshot(date));
        when(dailyReportMapper.findByDateAndVersion(eq(date), anyInt())).thenReturn(record(date));
        when(deliveryService.sendManual(anyLong(), eq("req-5"), anyBoolean()))
                .thenReturn(new ReportDeliveryService.DeliveryOutcome(
                        ReportDeliveryService.DeliveryOutcome.Result.CONFLICT, null, null));

        mvc.perform(post("/internal/reports/{date}/send", date)
                        .header("X-Lumora-Admin-Key", ADMIN_KEY)
                        .header("X-Request-Id", "req-5")
                        .contentType("application/json")
                        .content("{}"))
                .andExpect(status().isConflict());
    }

    private ReportDeliveryService.DeliveryOutcome sent(String deliveryId) {
        return new ReportDeliveryService.DeliveryOutcome(
                ReportDeliveryService.DeliveryOutcome.Result.SENT, deliveryId, null);
    }

    private DailyReportSnapshot snapshot(LocalDate date) {
        return new DailyReportSnapshot(
                date, 1, Instant.parse("2026-07-26T16:00:00Z"), Instant.parse("2026-07-27T16:00:00Z"),
                Instant.parse("2026-07-28T02:00:00Z"), 0L, 0L, java.util.List.of(),
                0L, 0L, 0L, 0L, 0L, java.util.List.of(), java.util.List.of(),
                java.util.List.of(), java.util.List.of(), 0L, 0L, 0L, true);
    }

    private DailyReportRecord record(LocalDate date) {
        return new DailyReportRecord(1L, date, 1,
                Instant.parse("2026-07-26T16:00:00Z"), Instant.parse("2026-07-27T16:00:00Z"),
                Instant.parse("2026-07-28T02:00:00Z"), "{}", null);
    }
}
