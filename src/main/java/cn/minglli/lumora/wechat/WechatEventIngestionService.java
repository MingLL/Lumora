package cn.minglli.lumora.wechat;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;

import cn.minglli.lumora.event.EventType;
import cn.minglli.lumora.event.WechatEvent;
import cn.minglli.lumora.event.WechatEventRepository;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Service;

@Service
public final class WechatEventIngestionService {

    private static final Duration MAX_TIMESTAMP_SKEW = Duration.ofDays(30);

    private final WechatEventRepository repository;
    private final Clock clock;
    private final WechatEventNormalizer normalizer;
    private final Counter anomalousTimestampCounter;
    private final Counter unknownEventCounter;

    public WechatEventIngestionService(
            WechatEventRepository repository,
            Clock clock,
            MeterRegistry meterRegistry) {
        this.repository = repository;
        this.clock = clock;
        this.normalizer = new WechatEventNormalizer();
        this.anomalousTimestampCounter =
                meterRegistry.counter("lumora.wechat.timestamp.anomalous");
        this.unknownEventCounter =
                meterRegistry.counter("lumora.wechat.event.unknown");
    }

    public IngestionResult ingest(WechatInboundMessage message) {
        WechatEventNormalizer.NormalizationResult result = normalizer.normalize(message);
        if (result.outcome() == WechatEventNormalizer.Outcome.IGNORED) {
            return IngestionResult.IGNORED;
        }

        WechatEventNormalizer.NormalizedEvent normalized = result.event().orElseThrow();
        Instant receivedAt = clock.instant();
        Instant originalOccurredAt = Instant.ofEpochSecond(
                requiredCreateTime(normalized.createTimeEpochSeconds()));
        boolean anomalous = originalOccurredAt.isBefore(receivedAt.minus(MAX_TIMESTAMP_SKEW))
                || originalOccurredAt.isAfter(receivedAt.plus(MAX_TIMESTAMP_SKEW));
        Instant effectiveOccurredAt = anomalous ? receivedAt : originalOccurredAt;

        if (anomalous) {
            anomalousTimestampCounter.increment();
        }
        if (normalized.eventType() == EventType.UNKNOWN) {
            unknownEventCounter.increment();
        }

        WechatEvent event = new WechatEvent(
                null,
                normalized.appId(),
                normalized.openId(),
                normalized.eventType(),
                normalized.rawMsgType(),
                normalized.rawEvent(),
                normalized.messageId(),
                originalOccurredAt,
                effectiveOccurredAt,
                receivedAt,
                anomalous,
                normalized.deduplicationKey(),
                normalized.rawEventKey(),
                normalized.qrScene(),
                normalized.ticket(),
                normalized.ticketPresent(),
                normalized.menuKey(),
                normalized.menuUrl(),
                normalized.latitude(),
                normalized.longitude(),
                normalized.locationPrecision(),
                normalized.compositeType(),
                normalized.compositeItemCount(),
                normalized.compositeSha256(),
                normalized.safeSummary(),
                normalized.normalizedMessageSha256(),
                null);

        return switch (repository.insert(event)) {
            case INSERTED -> IngestionResult.INSERTED;
            case DUPLICATE -> IngestionResult.DUPLICATE;
        };
    }

    private static long requiredCreateTime(Long createTime) {
        if (createTime == null) {
            throw new IllegalArgumentException("CreateTime is required");
        }
        return createTime;
    }

    public enum IngestionResult {
        INSERTED,
        DUPLICATE,
        IGNORED
    }
}
