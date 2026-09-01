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
    void previousCompletedWeekMapsToShanghaiMondayThroughSundayRange() {
        Clock clock = Clock.fixed(Instant.parse("2026-07-28T02:00:00Z"), SHANGHAI);

        ReportWindow window = ReportWindow.forPreviousCompletedWeek(clock, SHANGHAI);

        assertThat(window.reportDate()).isEqualTo(LocalDate.of(2026, 7, 26));
        assertThat(window.windowStart()).isEqualTo(Instant.parse("2026-07-19T16:00:00Z"));
        assertThat(window.windowEnd()).isEqualTo(Instant.parse("2026-07-26T16:00:00Z"));
    }

    @Test
    void mondayMorningReportsTheSundayThatJustEnded() {
        Clock clock = Clock.fixed(Instant.parse("2026-08-03T01:30:00Z"), SHANGHAI);

        ReportWindow window = ReportWindow.forPreviousCompletedWeek(clock, SHANGHAI);

        assertThat(window.reportDate()).isEqualTo(LocalDate.of(2026, 8, 2));
        assertThat(window.windowStart()).isEqualTo(Instant.parse("2026-07-26T16:00:00Z"));
        assertThat(window.windowEnd()).isEqualTo(Instant.parse("2026-08-02T16:00:00Z"));
    }
}
