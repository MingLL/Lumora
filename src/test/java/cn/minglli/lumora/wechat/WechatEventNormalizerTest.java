package cn.minglli.lumora.wechat;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import cn.minglli.lumora.event.EventType;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

class WechatEventNormalizerTest {

    private final WechatEventNormalizer normalizer = new WechatEventNormalizer();
    private final ObjectMapper objectMapper = new ObjectMapper();

    @ParameterizedTest
    @MethodSource("eventMappings")
    void mapsSupportedWechatEvents(String rawEvent, EventType expectedType) {
        var result = normalizer.normalize(eventMessage().event(rawEvent).build());

        assertThat(result.outcome()).isEqualTo(WechatEventNormalizer.Outcome.EVENT);
        assertThat(result.event()).isPresent();
        assertThat(result.event().orElseThrow().eventType()).isEqualTo(expectedType);
        assertThat(result.event().orElseThrow().rawEvent()).isEqualTo(rawEvent);
    }

    static Stream<Arguments> eventMappings() {
        return Stream.of(
                Arguments.of("subscribe", EventType.SUBSCRIBE),
                Arguments.of("unsubscribe", EventType.UNSUBSCRIBE),
                Arguments.of("SCAN", EventType.SCAN),
                Arguments.of("LOCATION", EventType.LOCATION),
                Arguments.of("CLICK", EventType.MENU_CLICK),
                Arguments.of("VIEW", EventType.MENU_VIEW),
                Arguments.of("scancode_push", EventType.MENU_OTHER),
                Arguments.of("scancode_waitmsg", EventType.MENU_OTHER),
                Arguments.of("pic_sysphoto", EventType.MENU_OTHER),
                Arguments.of("pic_photo_or_album", EventType.MENU_OTHER),
                Arguments.of("pic_weixin", EventType.MENU_OTHER),
                Arguments.of("location_select", EventType.MENU_OTHER),
                Arguments.of("future_wechat_event", EventType.UNKNOWN));
    }

    @Test
    void usesExactMenuOtherWhitelist() {
        var result = normalizer.normalize(eventMessage().event("SCANCODE_PUSH").build());

        assertThat(result.event().orElseThrow().eventType()).isEqualTo(EventType.UNKNOWN);
    }

    @Test
    void qrSubscribeAndScanShareCanonicalSceneWhileRetainingRawEventKey() {
        var subscribe = normalizer.normalize(eventMessage()
                        .event("subscribe")
                        .eventKey("qrscene_campaign-42")
                        .ticket("ticket-secret")
                        .build())
                .event()
                .orElseThrow();
        var scan = normalizer.normalize(eventMessage()
                        .event("SCAN")
                        .eventKey("qrscene_campaign-42")
                        .ticket("ticket-secret")
                        .build())
                .event()
                .orElseThrow();

        assertThat(subscribe.eventType()).isEqualTo(EventType.SUBSCRIBE);
        assertThat(scan.eventType()).isEqualTo(EventType.SCAN);
        assertThat(subscribe.rawEventKey()).isEqualTo("qrscene_campaign-42");
        assertThat(scan.rawEventKey()).isEqualTo("qrscene_campaign-42");
        assertThat(subscribe.qrScene()).isEqualTo("campaign-42");
        assertThat(scan.qrScene()).isEqualTo("campaign-42");
        assertThat(subscribe.ticketPresent()).isTrue();
    }

    @Test
    void stripsExactlyOneQrScenePrefix() {
        var event = normalizer.normalize(eventMessage()
                        .event("subscribe")
                        .eventKey("qrscene_qrscene_nested")
                        .build())
                .event()
                .orElseThrow();

        assertThat(event.qrScene()).isEqualTo("qrscene_nested");
    }

    @Test
    void treatsBlankOptionalFieldsAsAbsent() {
        var event = normalizer.normalize(eventMessage()
                        .event("SCAN")
                        .eventKey(" \t")
                        .ticket("\n")
                        .build())
                .event()
                .orElseThrow();

        assertThat(event.rawEventKey()).isNull();
        assertThat(event.qrScene()).isNull();
        assertThat(event.ticket()).isNull();
        assertThat(event.ticketPresent()).isFalse();
    }

    @Test
    void mapsEventSpecificFieldsOnly() {
        var location = normalizer.normalize(eventMessage()
                        .event("LOCATION")
                        .latitude(new BigDecimal("31.2304000"))
                        .longitude(new BigDecimal("121.4737000"))
                        .locationPrecision(new BigDecimal("12.5000"))
                        .build())
                .event()
                .orElseThrow();
        var click = normalizer.normalize(eventMessage().event("CLICK").eventKey("MENU_NEWS").build())
                .event()
                .orElseThrow();
        var view = normalizer.normalize(eventMessage().event("VIEW").eventKey("https://example.test/x").build())
                .event()
                .orElseThrow();

        assertThat(location.latitude()).isEqualByComparingTo("31.2304000");
        assertThat(location.longitude()).isEqualByComparingTo("121.4737000");
        assertThat(location.locationPrecision()).isEqualByComparingTo("12.5000");
        assertThat(click.menuKey()).isEqualTo("MENU_NEWS");
        assertThat(click.menuUrl()).isNull();
        assertThat(view.menuUrl()).isEqualTo("https://example.test/x");
        assertThat(view.menuKey()).isNull();
    }

    @Test
    void ignoresOrdinaryMessagesWithoutCreatingAnEvent() {
        for (String msgType : List.of("text", "image", "voice", "video", "shortvideo", "link")) {
            var result = normalizer.normalize(eventMessage().msgType(msgType).event(null).build());

            assertThat(result.outcome()).isEqualTo(WechatEventNormalizer.Outcome.IGNORED);
            assertThat(result.event()).isEmpty();
        }
    }

    @Test
    void retainsOnlyPrivacySafeCompositeMetadata() {
        String scanResult = "private-scan-result";
        String photoUrl = "https://private.example/photo.jpg";
        String selectedLocation = "private-selected-location";
        var composite = new WechatInboundMessage.CompositePayload(
                "ScanCodeInfo",
                List.of(Map.of(
                        "ScanType", "qrcode",
                        "ScanResult", scanResult,
                        "Nested", Map.of("PhotoUrl", photoUrl, "Location", selectedLocation))));

        var event = normalizer.normalize(eventMessage()
                        .event("scancode_push")
                        .eventKey("menu-private-key")
                        .composite(composite)
                        .build())
                .event()
                .orElseThrow();

        assertThat(event.eventType()).isEqualTo(EventType.MENU_OTHER);
        assertThat(event.rawEvent()).isEqualTo("scancode_push");
        assertThat(event.rawEventKey()).isEqualTo("menu-private-key");
        assertThat(event.compositeType()).isEqualTo("ScanCodeInfo");
        assertThat(event.compositeItemCount()).isEqualTo(1);
        assertThat(event.compositeSha256()).matches("[0-9a-f]{64}");
        assertThat(event.toString())
                .doesNotContain(scanResult, photoUrl, selectedLocation);
        assertThat(event.safeSummary())
                .doesNotContain(scanResult, photoUrl, selectedLocation, "menu-private-key");
    }

    @Test
    void safeSummaryContainsOnlyWhitelistedMetadata() throws Exception {
        String openId = "openid-private";
        String eventKey = "event-key-private";
        String ticket = "ticket-private";
        String messageText = "message-text-private";
        var event = normalizer.normalize(eventMessage()
                        .openId(openId)
                        .event("CLICK")
                        .eventKey(eventKey)
                        .ticket(ticket)
                        .payload(Map.of("Content", messageText, "PicUrl", "https://private.example/image"))
                        .build())
                .event()
                .orElseThrow();

        Map<String, Object> summary = objectMapper.readValue(
                event.safeSummary(), new TypeReference<>() {});

        assertThat(summary.keySet()).containsExactly(
                "MsgType",
                "Event",
                "CreateTime",
                "EventKeyPresent",
                "TicketPresent",
                "CompositeType",
                "CompositeItemCount");
        assertThat(summary)
                .containsEntry("MsgType", "event")
                .containsEntry("Event", "CLICK")
                .containsEntry("CreateTime", 1_700_000_000)
                .containsEntry("EventKeyPresent", true)
                .containsEntry("TicketPresent", true);
        assertThat(event.safeSummary())
                .doesNotContain(openId, eventKey, ticket, messageText, "Latitude", "Longitude");
        assertThat(event.normalizedMessageSha256()).matches("[0-9a-f]{64}");
    }

    private static WechatInboundMessage.Builder eventMessage() {
        return WechatInboundMessage.builder()
                .appId("wx-app")
                .openId("openid-1")
                .msgType("event")
                .createTimeEpochSeconds(1_700_000_000L);
    }
}
