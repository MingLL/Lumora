package cn.minglli.lumora.wechat;

import java.math.BigDecimal;
import java.util.Optional;
import java.util.Set;

import cn.minglli.lumora.event.EventType;

public final class WechatEventNormalizer {

    private static final String QR_SCENE_PREFIX = "qrscene_";
    private static final Set<String> MENU_OTHER_EVENTS = Set.of(
            "scancode_push",
            "scancode_waitmsg",
            "pic_sysphoto",
            "pic_photo_or_album",
            "pic_weixin",
            "location_select");

    public NormalizationResult normalize(WechatInboundMessage message) {
        if (!"event".equals(message.msgType())) {
            return NormalizationResult.ignored();
        }

        EventType eventType = mapEventType(message.event());
        String eventKey = absentWhenBlank(message.eventKey());
        String ticket = absentWhenBlank(message.ticket());
        EventDeduplicationKey.CompositeFingerprint composite =
                EventDeduplicationKey.compositeFingerprint(message.composite()).orElse(null);

        NormalizedEvent event = new NormalizedEvent(
                message.appId(),
                message.openId(),
                eventType,
                message.msgType(),
                message.event(),
                message.msgId(),
                message.createTimeEpochSeconds(),
                EventDeduplicationKey.forMessage(message),
                eventKey,
                isQrEvent(eventType) ? canonicalQrScene(eventKey) : null,
                ticket,
                ticket != null,
                eventType == EventType.MENU_CLICK ? eventKey : null,
                eventType == EventType.MENU_VIEW ? eventKey : null,
                eventType == EventType.LOCATION ? message.latitude() : null,
                eventType == EventType.LOCATION ? message.longitude() : null,
                eventType == EventType.LOCATION ? message.locationPrecision() : null,
                composite == null ? null : composite.type(),
                composite == null ? null : composite.itemCount(),
                composite == null ? null : composite.contentSha256(),
                SafeMessageSummary.from(message),
                SafeMessageSummary.normalizedMessageSha256(message));
        return NormalizationResult.event(event);
    }

    private static EventType mapEventType(String rawEvent) {
        if (rawEvent == null) {
            return EventType.UNKNOWN;
        }
        return switch (rawEvent) {
            case "subscribe" -> EventType.SUBSCRIBE;
            case "unsubscribe" -> EventType.UNSUBSCRIBE;
            case "SCAN" -> EventType.SCAN;
            case "LOCATION" -> EventType.LOCATION;
            case "CLICK" -> EventType.MENU_CLICK;
            case "VIEW" -> EventType.MENU_VIEW;
            default -> MENU_OTHER_EVENTS.contains(rawEvent)
                    ? EventType.MENU_OTHER
                    : EventType.UNKNOWN;
        };
    }

    private static boolean isQrEvent(EventType eventType) {
        return eventType == EventType.SUBSCRIBE || eventType == EventType.SCAN;
    }

    private static String canonicalQrScene(String eventKey) {
        if (eventKey == null) {
            return null;
        }
        String scene = eventKey.startsWith(QR_SCENE_PREFIX)
                ? eventKey.substring(QR_SCENE_PREFIX.length())
                : eventKey;
        return absentWhenBlank(scene);
    }

    private static String absentWhenBlank(String value) {
        return value == null || value.isBlank() ? null : value;
    }

    public enum Outcome {
        EVENT,
        IGNORED
    }

    public record NormalizationResult(Outcome outcome, Optional<NormalizedEvent> event) {

        private static NormalizationResult event(NormalizedEvent event) {
            return new NormalizationResult(Outcome.EVENT, Optional.of(event));
        }

        private static NormalizationResult ignored() {
            return new NormalizationResult(Outcome.IGNORED, Optional.empty());
        }
    }

    public record NormalizedEvent(
            String appId,
            String openId,
            EventType eventType,
            String rawMsgType,
            String rawEvent,
            Long messageId,
            Long createTimeEpochSeconds,
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
            String normalizedMessageSha256) {}
}
