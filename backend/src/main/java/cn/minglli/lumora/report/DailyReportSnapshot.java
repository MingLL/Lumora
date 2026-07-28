package cn.minglli.lumora.report;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

import cn.minglli.lumora.event.EventType;

public record DailyReportSnapshot(
        LocalDate reportDate,
        int version,
        Instant windowStart,
        Instant windowEnd,
        Instant generatedAt,
        long totalEvents,
        long totalUniqueUsers,
        List<EventTypeCount> byEventType,
        long subscribeEvents,
        long subscribeUniqueUsers,
        long unsubscribeEvents,
        long unsubscribeUniqueUsers,
        long netGrowth,
        List<LabelCount> qrScenes,
        List<LabelCount> menuClickKeys,
        List<LabelCount> menuViewUrls,
        List<MenuOtherCount> menuOther,
        long locationReports,
        long locationUniqueUsers,
        long anomalousTimestampCount,
        boolean emptyDay) {

    public record EventTypeCount(EventType eventType, long events, long uniqueUsers) {
    }

    public record LabelCount(String label, long events, long uniqueUsers) {
    }

    public record MenuOtherCount(String rawEvent, String eventKey, long events, long uniqueUsers) {
    }
}
