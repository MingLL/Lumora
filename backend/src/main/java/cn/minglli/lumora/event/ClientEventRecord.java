package cn.minglli.lumora.event;

import java.time.Instant;

public record ClientEventRecord(
        Long id,
        String visitId,
        String type,
        String url,
        String propertiesJson,
        Instant receivedAt,
        Instant createdAt,
        String clientIp,
        String referrerHost) {
    public ClientEventRecord(Long id, String visitId, String type, String url,
            String propertiesJson, Instant receivedAt, Instant createdAt) {
        this(id, visitId, type, url, propertiesJson, receivedAt, createdAt, null, null);
    }
}
