package cn.minglli.lumora.wechat;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Immutable protocol-neutral input to event normalization.
 *
 * <p>The generic payload is retained only long enough to calculate a stable
 * privacy-safe hash. It is never exposed by a normalized event.
 */
public final class WechatInboundMessage {

    private final String appId;
    private final String openId;
    private final String msgType;
    private final String event;
    private final Long createTimeEpochSeconds;
    private final Long msgId;
    private final String eventKey;
    private final String ticket;
    private final BigDecimal latitude;
    private final BigDecimal longitude;
    private final BigDecimal locationPrecision;
    private final CompositePayload composite;
    private final Map<String, Object> payload;

    private WechatInboundMessage(Builder builder) {
        this.appId = builder.appId;
        this.openId = builder.openId;
        this.msgType = builder.msgType;
        this.event = builder.event;
        this.createTimeEpochSeconds = builder.createTimeEpochSeconds;
        this.msgId = builder.msgId;
        this.eventKey = builder.eventKey;
        this.ticket = builder.ticket;
        this.latitude = builder.latitude;
        this.longitude = builder.longitude;
        this.locationPrecision = builder.locationPrecision;
        this.composite = builder.composite;
        this.payload = immutableMap(builder.payload);
    }

    public static Builder builder() {
        return new Builder();
    }

    public String appId() {
        return appId;
    }

    public String openId() {
        return openId;
    }

    public String msgType() {
        return msgType;
    }

    public String event() {
        return event;
    }

    public Long createTimeEpochSeconds() {
        return createTimeEpochSeconds;
    }

    public Long msgId() {
        return msgId;
    }

    public String eventKey() {
        return eventKey;
    }

    public String ticket() {
        return ticket;
    }

    public BigDecimal latitude() {
        return latitude;
    }

    public BigDecimal longitude() {
        return longitude;
    }

    public BigDecimal locationPrecision() {
        return locationPrecision;
    }

    public CompositePayload composite() {
        return composite;
    }

    public Map<String, Object> payload() {
        return payload;
    }

    public static final class Builder {

        private String appId;
        private String openId;
        private String msgType;
        private String event;
        private Long createTimeEpochSeconds;
        private Long msgId;
        private String eventKey;
        private String ticket;
        private BigDecimal latitude;
        private BigDecimal longitude;
        private BigDecimal locationPrecision;
        private CompositePayload composite;
        private Map<String, ?> payload = Map.of();

        private Builder() {}

        public Builder appId(String appId) {
            this.appId = appId;
            return this;
        }

        public Builder openId(String openId) {
            this.openId = openId;
            return this;
        }

        public Builder msgType(String msgType) {
            this.msgType = msgType;
            return this;
        }

        public Builder event(String event) {
            this.event = event;
            return this;
        }

        public Builder createTimeEpochSeconds(Long createTimeEpochSeconds) {
            this.createTimeEpochSeconds = createTimeEpochSeconds;
            return this;
        }

        public Builder msgId(Long msgId) {
            this.msgId = msgId;
            return this;
        }

        public Builder eventKey(String eventKey) {
            this.eventKey = eventKey;
            return this;
        }

        public Builder ticket(String ticket) {
            this.ticket = ticket;
            return this;
        }

        public Builder latitude(BigDecimal latitude) {
            this.latitude = latitude;
            return this;
        }

        public Builder longitude(BigDecimal longitude) {
            this.longitude = longitude;
            return this;
        }

        public Builder locationPrecision(BigDecimal locationPrecision) {
            this.locationPrecision = locationPrecision;
            return this;
        }

        public Builder composite(CompositePayload composite) {
            this.composite = composite;
            return this;
        }

        public Builder payload(Map<String, ?> payload) {
            this.payload = payload == null ? Map.of() : payload;
            return this;
        }

        public WechatInboundMessage build() {
            return new WechatInboundMessage(this);
        }
    }

    public static final class CompositePayload {

        private final String type;
        private final List<Map<String, Object>> items;

        public CompositePayload(String type, List<? extends Map<String, ?>> items) {
            this.type = type;
            this.items = immutableItems(items);
        }

        public String type() {
            return type;
        }

        public List<Map<String, Object>> items() {
            return items;
        }

        @Override
        public boolean equals(Object other) {
            if (this == other) {
                return true;
            }
            return other instanceof CompositePayload that
                    && Objects.equals(type, that.type)
                    && items.equals(that.items);
        }

        @Override
        public int hashCode() {
            return Objects.hash(type, items);
        }

        private static List<Map<String, Object>> immutableItems(
                List<? extends Map<String, ?>> items) {
            if (items == null || items.isEmpty()) {
                return List.of();
            }
            List<Map<String, Object>> copy = new ArrayList<>(items.size());
            for (Map<String, ?> item : items) {
                copy.add(immutableMap(item));
            }
            return List.copyOf(copy);
        }
    }

    private static Map<String, Object> immutableMap(Map<String, ?> source) {
        if (source == null || source.isEmpty()) {
            return Map.of();
        }
        Map<String, Object> copy = new LinkedHashMap<>();
        source.forEach((key, value) -> copy.put(key, immutableValue(value)));
        return Collections.unmodifiableMap(copy);
    }

    private static Object immutableValue(Object value) {
        if (value == null
                || value instanceof String
                || value instanceof Boolean
                || value instanceof Byte
                || value instanceof Short
                || value instanceof Integer
                || value instanceof Long
                || value instanceof Float
                || value instanceof Double
                || value instanceof BigInteger
                || value instanceof BigDecimal) {
            return value;
        }
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> copy = new LinkedHashMap<>();
            map.forEach((key, nestedValue) -> {
                if (!(key instanceof String stringKey)) {
                    throw new IllegalArgumentException("Payload map keys must be strings");
                }
                copy.put(stringKey, immutableValue(nestedValue));
            });
            return Collections.unmodifiableMap(copy);
        }
        if (value instanceof List<?> list) {
            return list.stream().map(WechatInboundMessage::immutableValue).toList();
        }
        throw new IllegalArgumentException(
                "Unsupported payload value type: " + value.getClass().getName());
    }
}
