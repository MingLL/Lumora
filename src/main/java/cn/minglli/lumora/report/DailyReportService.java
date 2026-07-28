package cn.minglli.lumora.report;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import cn.minglli.lumora.config.LumoraProperties;
import cn.minglli.lumora.event.EventType;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class DailyReportService {

    private final DailyReportMapper mapper;
    private final Clock clock;
    private final LumoraProperties properties;
    private final ObjectMapper objectMapper;

    public DailyReportService(
            DailyReportMapper mapper,
            Clock clock,
            LumoraProperties properties,
            ObjectMapper objectMapper) {
        this.mapper = mapper;
        this.clock = clock;
        this.properties = properties;
        this.objectMapper = objectMapper;
    }

    @Transactional
    public DailyReportSnapshot getOrCreateAutoSnapshot() {
        ReportWindow window = ReportWindow.forYesterday(clock, properties.getZone());
        DailyReportRecord existing = mapper.findByDateAndVersion(window.reportDate(), 1);
        if (existing != null) {
            return deserialize(existing);
        }
        Instant cutoff = clock.instant();
        DailyReportSnapshot snapshot = aggregate(window, cutoff, 1);
        try {
            mapper.insertSnapshot(toRecord(snapshot, cutoff));
            return snapshot;
        } catch (DuplicateKeyException exception) {
            DailyReportRecord winner = mapper.findByDateAndVersion(window.reportDate(), 1);
            if (winner == null) {
                throw exception;
            }
            return deserialize(winner);
        }
    }

    @Transactional
    public DailyReportSnapshot regenerateSnapshot() {
        ReportWindow window = ReportWindow.forYesterday(clock, properties.getZone());
        Integer maxVersion = mapper.findMaxVersion(window.reportDate());
        int nextVersion = (maxVersion == null ? 0 : maxVersion) + 1;
        Instant cutoff = clock.instant();
        DailyReportSnapshot snapshot = aggregate(window, cutoff, nextVersion);
        mapper.insertSnapshot(toRecord(snapshot, cutoff));
        return snapshot;
    }

    public DailyReportSnapshot loadLatest(LocalDate reportDate) {
        DailyReportRecord record = mapper.findLatestVersion(reportDate);
        return record == null ? null : deserialize(record);
    }

    private DailyReportSnapshot aggregate(ReportWindow window, Instant cutoff, int version) {
        Instant s = window.windowStart();
        Instant e = window.windowEnd();

        long totalEvents = mapper.countTotalEvents(s, e, cutoff);
        long totalUniqueUsers = mapper.countTotalUniqueUsers(s, e, cutoff);
        List<DailyReportSnapshot.EventTypeCount> rawByType = mapper.countByEventType(s, e, cutoff);
        Map<EventType, DailyReportSnapshot.EventTypeCount> byType = new EnumMap<>(EventType.class);
        for (DailyReportSnapshot.EventTypeCount count : rawByType) {
            byType.put(count.eventType(), count);
        }
        List<DailyReportSnapshot.EventTypeCount> byEventType = new ArrayList<>();
        for (EventType type : EventType.values()) {
            byEventType.add(byType.getOrDefault(type,
                    new DailyReportSnapshot.EventTypeCount(type, 0L, 0L)));
        }

        DailyReportSnapshot.EventTypeCount subscribe = byType.getOrDefault(EventType.SUBSCRIBE,
                new DailyReportSnapshot.EventTypeCount(EventType.SUBSCRIBE, 0L, 0L));
        DailyReportSnapshot.EventTypeCount unsubscribe = byType.getOrDefault(EventType.UNSUBSCRIBE,
                new DailyReportSnapshot.EventTypeCount(EventType.UNSUBSCRIBE, 0L, 0L));
        long subscribeEvents = subscribe.events();
        long subscribeUniqueUsers = subscribe.uniqueUsers();
        long unsubscribeEvents = unsubscribe.events();
        long unsubscribeUniqueUsers = unsubscribe.uniqueUsers();
        long netGrowth = subscribeEvents - unsubscribeEvents;

        List<DailyReportSnapshot.LabelCount> qrScenes = mapper.countQrScenes(s, e, cutoff);
        List<DailyReportSnapshot.LabelCount> menuClickKeys = mapper.countMenuClickKeys(s, e, cutoff);
        List<DailyReportSnapshot.LabelCount> menuViewUrls = mapper.countMenuViewUrls(s, e, cutoff);
        List<DailyReportSnapshot.MenuOtherCount> menuOther = mapper.countMenuOther(s, e, cutoff);
        long locationReports = mapper.countLocationReports(s, e, cutoff);
        long locationUniqueUsers = mapper.countLocationUniqueUsers(s, e, cutoff);
        long anomalous = mapper.countAnomalousTimestamps(s, e, cutoff);

        return new DailyReportSnapshot(
                window.reportDate(),
                version,
                window.windowStart(),
                window.windowEnd(),
                cutoff,
                totalEvents,
                totalUniqueUsers,
                byEventType,
                subscribeEvents,
                subscribeUniqueUsers,
                unsubscribeEvents,
                unsubscribeUniqueUsers,
                netGrowth,
                qrScenes,
                menuClickKeys,
                menuViewUrls,
                menuOther,
                locationReports,
                locationUniqueUsers,
                anomalous,
                totalEvents == 0);
    }

    private DailyReportRecord toRecord(DailyReportSnapshot snapshot, Instant cutoff) {
        return new DailyReportRecord(
                null,
                snapshot.reportDate(),
                snapshot.version(),
                snapshot.windowStart(),
                snapshot.windowEnd(),
                cutoff,
                serialize(snapshot),
                null);
    }

    private DailyReportSnapshot deserialize(DailyReportRecord record) {
        DailyReportSnapshot snapshot = deserialize(record.snapshotJson());
        return new DailyReportSnapshot(
                snapshot.reportDate(),
                record.version(),
                snapshot.windowStart(),
                snapshot.windowEnd(),
                record.dataCutoffAt(),
                snapshot.totalEvents(),
                snapshot.totalUniqueUsers(),
                snapshot.byEventType(),
                snapshot.subscribeEvents(),
                snapshot.subscribeUniqueUsers(),
                snapshot.unsubscribeEvents(),
                snapshot.unsubscribeUniqueUsers(),
                snapshot.netGrowth(),
                snapshot.qrScenes(),
                snapshot.menuClickKeys(),
                snapshot.menuViewUrls(),
                snapshot.menuOther(),
                snapshot.locationReports(),
                snapshot.locationUniqueUsers(),
                snapshot.anomalousTimestampCount(),
                snapshot.emptyDay());
    }

    private String serialize(DailyReportSnapshot snapshot) {
        try {
            return objectMapper.writeValueAsString(snapshot);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Failed to serialize daily report snapshot", exception);
        }
    }

    private DailyReportSnapshot deserialize(String json) {
        try {
            return objectMapper.readValue(json, DailyReportSnapshot.class);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Failed to deserialize daily report snapshot", exception);
        }
    }
}
