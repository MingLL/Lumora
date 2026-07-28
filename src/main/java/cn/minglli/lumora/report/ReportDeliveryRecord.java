package cn.minglli.lumora.report;

import java.time.Instant;

public record ReportDeliveryRecord(
        Long id,
        String deliveryId,
        Long reportId,
        DeliveryTriggerType triggerType,
        String requestId,
        DeliveryStatus status,
        String recipientMasked,
        String recipientSha256,
        int attemptCount,
        Instant claimedAt,
        Instant leaseUntil,
        Instant completedAt,
        String lastErrorClass,
        String lastErrorSummary,
        Instant createdAt,
        Instant updatedAt) {
}
