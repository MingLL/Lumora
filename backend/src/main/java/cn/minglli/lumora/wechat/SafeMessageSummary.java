package cn.minglli.lumora.wechat;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

public final class SafeMessageSummary {

    private SafeMessageSummary() {}

    public static String from(WechatInboundMessage message) {
        EventDeduplicationKey.CompositeFingerprint composite =
                EventDeduplicationKey.compositeFingerprint(message.composite()).orElse(null);

        StringBuilder json = new StringBuilder("{");
        append(json, "MsgType", message.msgType());
        append(json, "Event", message.event());
        append(json, "CreateTime", message.createTimeEpochSeconds());
        append(json, "EventKeyPresent", present(message.eventKey()));
        append(json, "TicketPresent", present(message.ticket()));
        append(json, "CompositeType", composite == null ? null : composite.type());
        append(json, "CompositeItemCount", composite == null ? null : composite.itemCount());
        return json.append('}').toString();
    }

    public static String normalizedMessageSha256(WechatInboundMessage message) {
        Map<String, Object> normalized = new LinkedHashMap<>();
        normalized.put("AppId", message.appId());
        normalized.put("OpenId", message.openId());
        normalized.put("MsgType", message.msgType());
        normalized.put("Event", message.event());
        normalized.put("CreateTime", message.createTimeEpochSeconds());
        normalized.put("MsgId", message.msgId());
        normalized.put("EventKey", message.eventKey());
        normalized.put("Ticket", message.ticket());
        normalized.put(
                "Latitude", EventDeduplicationKey.canonicalLatitude(message.latitude()));
        normalized.put(
                "Longitude", EventDeduplicationKey.canonicalLongitude(message.longitude()));
        normalized.put(
                "Precision",
                EventDeduplicationKey.canonicalPrecision(message.locationPrecision()));
        if (message.composite() == null) {
            normalized.put("Composite", null);
        } else {
            Map<String, Object> composite = new LinkedHashMap<>();
            composite.put("Type", message.composite().type());
            if (message.composite().declaredItemCount() != null) {
                composite.put("Count", message.composite().declaredItemCount());
            }
            composite.put("Items", message.composite().items());
            normalized.put("Composite", composite);
        }
        normalized.put("Payload", message.payload());
        return EventDeduplicationKey.sha256Hex(
                EventDeduplicationKey.canonicalBytes(normalized));
    }

    private static boolean present(String value) {
        return value != null && !value.isBlank();
    }

    private static void append(StringBuilder json, String key, Object value) {
        if (json.length() > 1) {
            json.append(',');
        }
        json.append('"').append(key).append("\":");
        if (value == null) {
            json.append("null");
        } else if (value instanceof Number || value instanceof Boolean) {
            json.append(value);
        } else {
            appendEscapedString(json, value.toString());
        }
    }

    private static void appendEscapedString(StringBuilder json, String value) {
        json.append('"');
        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
            switch (character) {
                case '"' -> json.append("\\\"");
                case '\\' -> json.append("\\\\");
                case '\b' -> json.append("\\b");
                case '\f' -> json.append("\\f");
                case '\n' -> json.append("\\n");
                case '\r' -> json.append("\\r");
                case '\t' -> json.append("\\t");
                default -> {
                    if (character < 0x20) {
                        json.append(String.format(Locale.ROOT, "\\u%04x", (int) character));
                    } else {
                        json.append(character);
                    }
                }
            }
        }
        json.append('"');
    }
}
