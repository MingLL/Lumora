package cn.minglli.lumora.report;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;

public record ReportWindow(LocalDate reportDate, Instant windowStart, Instant windowEnd) {

    public static ReportWindow forYesterday(Clock clock, ZoneId zone) {
        ZonedDateTime now = ZonedDateTime.now(clock).withZoneSameInstant(zone);
        LocalDate reportDate = now.toLocalDate().minusDays(1);
        return forDate(reportDate, zone);
    }

    public static ReportWindow forDate(LocalDate reportDate, ZoneId zone) {
        Instant windowStart = reportDate.atStartOfDay(zone).toInstant();
        Instant windowEnd = reportDate.plusDays(1).atStartOfDay(zone).toInstant();
        return new ReportWindow(reportDate, windowStart, windowEnd);
    }
}
