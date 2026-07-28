package cn.minglli.lumora.operations;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.Map;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import cn.minglli.lumora.event.WechatEventRepository;
import cn.minglli.lumora.wechat.WechatCallbackExceptionHandler;
import cn.minglli.lumora.wechat.WechatEventIngestionService;
import cn.minglli.lumora.wechat.WechatInboundMessage;
import cn.minglli.lumora.wechat.WechatMalformedXmlException;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * The callback path now logs. These assertions are what keeps it from logging the
 * wrong things: an OpenID, a coordinate, a ticket or a signature in a log line is a
 * privacy incident, and log files travel further than the database ever does.
 */
class LogRedactionTest {

    private static final String OPEN_ID = "oABC123_secret_openid";
    private static final String TICKET = "gQH47joAAAAAAAAAASxodHRwOi8vd2VpeGluLnFxLmNvbS9x";
    private static final String LATITUDE = "31.2304167";
    private static final String LONGITUDE = "121.4737012";

    private ListAppender<ILoggingEvent> appender;
    private ch.qos.logback.classic.Logger rootLogger;

    @BeforeEach
    void captureLogs() {
        LoggerContext context = (LoggerContext) LoggerFactory.getILoggerFactory();
        rootLogger = context.getLogger(ch.qos.logback.classic.Logger.ROOT_LOGGER_NAME);
        appender = new ListAppender<>();
        appender.setContext(context);
        appender.start();
        rootLogger.addAppender(appender);
        rootLogger.setLevel(Level.TRACE);
    }

    @AfterEach
    void releaseLogs() {
        rootLogger.detachAppender(appender);
        appender.stop();
    }

    @Test
    void ingestionLogsTheEventTypeButNeverTheUserOrTheirLocation() {
        WechatEventRepository repository = mock(WechatEventRepository.class);
        when(repository.insert(any())).thenReturn(WechatEventRepository.InsertResult.INSERTED);
        WechatEventIngestionService service = new WechatEventIngestionService(
                repository,
                Clock.fixed(Instant.parse("2026-07-28T02:00:00Z"), ZoneId.of("Asia/Shanghai")),
                new SimpleMeterRegistry());

        service.ingest(locationMessage());

        assertThat(logText()).contains("LOCATION").contains("INSERTED");
        assertThatNothingSensitiveLeaked();
    }

    @Test
    void unknownEventsAreReportedWithoutTheUserIdentity() {
        WechatEventRepository repository = mock(WechatEventRepository.class);
        when(repository.insert(any())).thenReturn(WechatEventRepository.InsertResult.INSERTED);
        WechatEventIngestionService service = new WechatEventIngestionService(
                repository,
                Clock.fixed(Instant.parse("2026-07-28T02:00:00Z"), ZoneId.of("Asia/Shanghai")),
                new SimpleMeterRegistry());

        service.ingest(message("brand_new_event_type", null));

        assertThat(logText()).contains("UNKNOWN").contains("brand_new_event_type");
        assertThatNothingSensitiveLeaked();
    }

    @Test
    void rejectedCallbacksLogTheReasonWithoutThePayload() {
        WechatCallbackExceptionHandler handler = new WechatCallbackExceptionHandler();

        handler.handleMalformedXml(new WechatMalformedXmlException(
                "Malformed WeChat XML",
                new IllegalArgumentException("<xml><FromUserName>" + OPEN_ID + "</FromUserName></xml>")));

        assertThat(logText()).contains("Rejected malformed WeChat XML");
        assertThatNothingSensitiveLeaked();
    }

    private void assertThatNothingSensitiveLeaked() {
        assertThat(logText())
                .doesNotContain(OPEN_ID)
                .doesNotContain(TICKET)
                .doesNotContain(LATITUDE)
                .doesNotContain(LONGITUDE)
                .doesNotContain("wechat-token")
                .doesNotContain("mail-auth-code")
                .doesNotContain("admin-secret");
    }

    private String logText() {
        return appender.list.stream()
                .map(event -> event.getFormattedMessage() + " " + String.valueOf(event.getThrowableProxy()))
                .reduce("", (a, b) -> a + "\n" + b);
    }

    private static WechatInboundMessage locationMessage() {
        return WechatInboundMessage.builder()
                .appId("wx-app-id")
                .openId(OPEN_ID)
                .msgType("event")
                .event("LOCATION")
                .createTimeEpochSeconds(Instant.parse("2026-07-28T01:00:00Z").getEpochSecond())
                .latitude(new BigDecimal(LATITUDE))
                .longitude(new BigDecimal(LONGITUDE))
                .locationPrecision(new BigDecimal("65.000000"))
                .ticket(TICKET)
                .payload(Map.of())
                .build();
    }

    private static WechatInboundMessage message(String event, String eventKey) {
        return WechatInboundMessage.builder()
                .appId("wx-app-id")
                .openId(OPEN_ID)
                .msgType("event")
                .event(event)
                .eventKey(eventKey)
                .ticket(TICKET)
                .createTimeEpochSeconds(Instant.parse("2026-07-28T01:00:00Z").getEpochSecond())
                .payload(Map.of())
                .build();
    }
}
