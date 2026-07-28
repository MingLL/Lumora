package cn.minglli.lumora.report;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ReportWindowTest {

    private static final ZoneId SHANGHAI = ZoneId.of("Asia/Shanghai");

    @Test
    void shanghaiYesterdayMapsToUtcHalfOpenRange() {
        Clock clock = Clock.fixed(Instant.parse("2026-07-28T02:00:00Z"), SHANGHAI);

        ReportWindow window = ReportWindow.forYesterday(clock, SHANGHAI);

        assertThat(window.reportDate()).isEqualTo(LocalDate.of(2026, 7, 27));
        assertThat(window.windowStart()).isEqualTo(Instant.parse("2026-07-26T16:00:00Z"));
        assertThat(window.windowEnd()).isEqualTo(Instant.parse("2026-07-27T16:00:00Z"));
    }

    @Test
    void lateNightShanghaiStillReportsPreviousUtcDay() {
        Clock clock = Clock.fixed(Instant.parse("2026-07-28T17:30:00Z"), SHANGHAI);

        ReportWindow window = ReportWindow.forYesterday(clock, SHANGHAI);

        assertThat(window.reportDate()).isEqualTo(LocalDate.of(2026, 7, 28));
        assertThat(window.windowStart()).isEqualTo(Instant.parse("2026-07-27T16:00:00Z"));
        assertThat(window.windowEnd()).isEqualTo(Instant.parse("2026-07-28T16:00:00Z"));
    }
}
