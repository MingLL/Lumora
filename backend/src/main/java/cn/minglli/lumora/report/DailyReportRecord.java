package cn.minglli.lumora.report;

import java.time.Instant;
import java.time.LocalDate;

public record DailyReportRecord(
        Long id,
        LocalDate reportDate,
        int version,
        Instant windowStart,
        Instant windowEnd,
        Instant dataCutoffAt,
        String snapshotJson,
        Instant createdAt) {
}
