package cn.minglli.lumora.event;

import java.math.BigDecimal;
import java.time.Instant;

public record WechatEvent(
        Long id,
        String appId,
        String openId,
        EventType eventType,
        String rawMsgType,
        String rawEvent,
        Long messageId,
        Instant originalOccurredAt,
        Instant effectiveOccurredAt,
        Instant receivedAt,
        boolean anomalousTimestamp,
        String deduplicationKey,
        String rawEventKey,
        String qrScene,
        String ticket,
        boolean ticketPresent,
        String menuKey,
        String menuUrl,
        BigDecimal latitude,
        BigDecimal longitude,
        BigDecimal locationPrecision,
        String compositeType,
        Integer compositeItemCount,
        String compositeSha256,
        String safeSummary,
        String normalizedMessageSha256,
        Instant createdAt) {
}
