package cn.minglli.lumora.event;

import java.time.Instant;

public record ClientEventRecord(
        Long id,
        String visitId,
        String type,
        String url,
        String propertiesJson,
        Instant receivedAt,
        Instant createdAt) {
}
