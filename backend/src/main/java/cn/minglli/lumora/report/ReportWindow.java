package cn.minglli.lumora.report;

import java.time.Clock;
import java.time.Instant;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;

public record ReportWindow(LocalDate reportDate, Instant windowStart, Instant windowEnd) {

    /** Returns the previous fully completed Monday-through-Sunday reporting week. */
    public static ReportWindow forPreviousCompletedWeek(Clock clock, ZoneId zone) {
        ZonedDateTime now = ZonedDateTime.now(clock).withZoneSameInstant(zone);
        LocalDate reportDate = now.toLocalDate().minusDays(1)
                .with(java.time.temporal.TemporalAdjusters.previousOrSame(DayOfWeek.SUNDAY));
        return forWeekEnding(reportDate, zone);
    }

    /**
     * Returns the Monday-through-Sunday week containing {@code date}; the stored
     * report date is always that week's Sunday.
     */
    public static ReportWindow forWeekContaining(LocalDate date, ZoneId zone) {
        LocalDate reportDate = date.with(java.time.temporal.TemporalAdjusters.nextOrSame(DayOfWeek.SUNDAY));
        return forWeekEnding(reportDate, zone);
    }

    private static ReportWindow forWeekEnding(LocalDate reportDate, ZoneId zone) {
        Instant windowStart = reportDate.minusDays(6).atStartOfDay(zone).toInstant();
        Instant windowEnd = reportDate.plusDays(1).atStartOfDay(zone).toInstant();
        return new ReportWindow(reportDate, windowStart, windowEnd);
    }
}
