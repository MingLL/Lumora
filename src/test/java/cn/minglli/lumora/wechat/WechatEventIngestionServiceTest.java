package cn.minglli.lumora.wechat;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.stream.Stream;

import cn.minglli.lumora.event.EventType;
import cn.minglli.lumora.event.WechatEvent;
import cn.minglli.lumora.event.WechatEventRepository;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

class WechatEventIngestionServiceTest {

    private static final Instant RECEIVED_AT = Instant.parse("2026-07-28T12:00:00Z");

    private CapturingRepository repository;
    private SimpleMeterRegistry meterRegistry;
    private WechatEventIngestionService service;

    @BeforeEach
    void setUp() {
        repository = new CapturingRepository();
        meterRegistry = new SimpleMeterRegistry();
        service = new WechatEventIngestionService(
                repository,
                Clock.fixed(RECEIVED_AT, ZoneOffset.UTC),
                meterRegistry);
    }

    @Test
    void persistsEveryNormalizedFieldAndReturnsRepositoryOutcome() {
        repository.result = WechatEventRepository.InsertResult.INSERTED;
        WechatInboundMessage message = baseEvent(RECEIVED_AT.minusSeconds(60))
                .event("CLICK")
                .eventKey("MENU_REPORT")
                .ticket("private-ticket")
                .build();

        assertThat(service.ingest(message))
                .isEqualTo(WechatEventIngestionService.IngestionResult.INSERTED);

        WechatEvent event = repository.event;
        assertThat(event.id()).isNull();
        assertThat(event.appId()).isEqualTo("wx_test_app");
        assertThat(event.openId()).isEqualTo("private-openid");
        assertThat(event.eventType()).isEqualTo(EventType.MENU_CLICK);
        assertThat(event.rawMsgType()).isEqualTo("event");
        assertThat(event.rawEvent()).isEqualTo("CLICK");
        assertThat(event.messageId()).isEqualTo(9001L);
        assertThat(event.originalOccurredAt()).isEqualTo(RECEIVED_AT.minusSeconds(60));
        assertThat(event.effectiveOccurredAt()).isEqualTo(RECEIVED_AT.minusSeconds(60));
        assertThat(event.receivedAt()).isEqualTo(RECEIVED_AT);
        assertThat(event.anomalousTimestamp()).isFalse();
        assertThat(event.deduplicationKey()).isEqualTo("msgid:9001");
        assertThat(event.rawEventKey()).isEqualTo("MENU_REPORT");
        assertThat(event.ticket()).isEqualTo("private-ticket");
        assertThat(event.ticketPresent()).isTrue();
        assertThat(event.menuKey()).isEqualTo("MENU_REPORT");
        assertThat(event.safeSummary()).doesNotContain("private-openid", "private-ticket", "MENU_REPORT");
        assertThat(event.normalizedMessageSha256()).matches("[0-9a-f]{64}");
        assertThat(event.createdAt()).isNull();
    }

    @ParameterizedTest
    @MethodSource("timestampCases")
    void appliesStrictThirtyDayTimestampBoundary(
            Instant occurredAt, boolean anomalous, Instant effectiveAt) {
        repository.result = WechatEventRepository.InsertResult.DUPLICATE;

        assertThat(service.ingest(baseEvent(occurredAt).build()))
                .isEqualTo(WechatEventIngestionService.IngestionResult.DUPLICATE);

        assertThat(repository.event.originalOccurredAt()).isEqualTo(occurredAt);
        assertThat(repository.event.anomalousTimestamp()).isEqualTo(anomalous);
        assertThat(repository.event.effectiveOccurredAt()).isEqualTo(effectiveAt);
        assertThat(meterRegistry.get("lumora.wechat.timestamp.anomalous").counter().count())
                .isEqualTo(anomalous ? 1.0 : 0.0);
    }

    static Stream<Arguments> timestampCases() {
        Duration thirtyDays = Duration.ofDays(30);
        return Stream.of(
                Arguments.of(RECEIVED_AT.minus(thirtyDays), false, RECEIVED_AT.minus(thirtyDays)),
                Arguments.of(RECEIVED_AT.plus(thirtyDays), false, RECEIVED_AT.plus(thirtyDays)),
                Arguments.of(RECEIVED_AT.minus(thirtyDays).minusSeconds(1), true, RECEIVED_AT),
                Arguments.of(RECEIVED_AT.plus(thirtyDays).plusSeconds(1), true, RECEIVED_AT));
    }

    @Test
    void incrementsUnknownEventCounter() {
        repository.result = WechatEventRepository.InsertResult.INSERTED;

        service.ingest(baseEvent(RECEIVED_AT).event("new_future_event").build());

        assertThat(meterRegistry.get("lumora.wechat.event.unknown").counter().count())
                .isEqualTo(1.0);
    }

    @Test
    void ignoresOrdinaryMessagesWithoutRepositoryInteraction() {
        WechatInboundMessage text = baseEvent(RECEIVED_AT)
                .msgType("text")
                .event(null)
                .build();

        assertThat(service.ingest(text))
                .isEqualTo(WechatEventIngestionService.IngestionResult.IGNORED);
        assertThat(repository.calls).isZero();
    }

    private static WechatInboundMessage.Builder baseEvent(Instant occurredAt) {
        return WechatInboundMessage.builder()
                .appId("wx_test_app")
                .openId("private-openid")
                .msgType("event")
                .event("subscribe")
                .createTimeEpochSeconds(occurredAt.getEpochSecond())
                .msgId(9001L);
    }

    private static final class CapturingRepository extends WechatEventRepository {

        private WechatEventRepository.InsertResult result =
                WechatEventRepository.InsertResult.INSERTED;
        private WechatEvent event;
        private int calls;

        private CapturingRepository() {
            super(null);
        }

        @Override
        public WechatEventRepository.InsertResult insert(WechatEvent event) {
            this.event = event;
            calls++;
            return result;
        }
    }
}
