package cn.minglli.lumora.report;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;

import cn.minglli.lumora.config.LumoraProperties;
import cn.minglli.lumora.mail.MailGateway;
import cn.minglli.lumora.mail.QqSmtpMailGateway;
import jakarta.mail.AuthenticationFailedException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@MockitoSettings(strictness = Strictness.LENIENT)
class ReportDeliveryServiceTest {

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-07-28T02:00:00Z"), ZoneId.of("Asia/Shanghai"));

    private DailyReportService dailyReportService;
    private DailyReportMapper dailyReportMapper;
    private ReportDeliveryMapper deliveryMapper;
    private MailGateway mailGateway;
    private ReportTemplateRenderer renderer;
    private LumoraProperties properties;
    private ReportDeliveryService.Sleeper sleeper;
    private ReportDeliveryService service;

    @BeforeEach
    void setUp() {
        dailyReportService = mock(DailyReportService.class);
        dailyReportMapper = mock(DailyReportMapper.class);
        deliveryMapper = mock(ReportDeliveryMapper.class);
        mailGateway = mock(MailGateway.class);
        renderer = mock(ReportTemplateRenderer.class);
        sleeper = mock(ReportDeliveryService.Sleeper.class);
        properties = new LumoraProperties();
        properties.setWechatOriginalId("gh_original");
        properties.setReportRecipients(List.of("owner@example.com"));
        service = new ReportDeliveryService(
                dailyReportService, dailyReportMapper, deliveryMapper, mailGateway,
                renderer, properties, CLOCK, sleeper);

        when(dailyReportService.getOrCreateAutoSnapshot()).thenReturn(snapshot());
        when(dailyReportMapper.findByDateAndVersion(any(), anyInt()))
                .thenReturn(new DailyReportRecord(1L, LocalDate.of(2026, 7, 27), 1,
                        Instant.parse("2026-07-26T16:00:00Z"), Instant.parse("2026-07-27T16:00:00Z"),
                        Instant.parse("2026-07-28T02:00:00Z"), "{}", null));
        when(deliveryMapper.findAutoByReportId(1L)).thenReturn(
                new ReportDeliveryRecord(10L, "delivery-1", 1L, DeliveryTriggerType.AUTO,
                        null, DeliveryStatus.PENDING, "", "", 0,
                        null, null, null, null, null, null, null));
        when(deliveryMapper.claim(anyLong(), any(), any())).thenReturn(1);
        when(renderer.render(any(), any())).thenReturn(
                new ReportTemplateRenderer.RenderedReport("subject", "<html/>", "text"));
    }

    @Test
    void successfulSendMarksSentOnFirstAttempt() {
        ReportDeliveryService.DeliveryOutcome outcome = service.runAutoReport();

        assertThat(outcome.result()).isEqualTo(ReportDeliveryService.DeliveryOutcome.Result.SENT);
        verify(mailGateway).send(any());
        verify(deliveryMapper).markSent(eq10(), any());
        verify(deliveryMapper, never()).markFailed(anyLong(), any(), any(), any());
        verify(deliveryMapper, never()).markPendingRetry(anyLong(), any(), any(), any());
        verify(sleeper, never()).sleep(anyLong());
    }

    @Test
    void transientFailureRetriesAndSucceedsOnThirdAttempt() {
        org.mockito.Mockito.doThrow(transientError())
                .doThrow(transientError())
                .doNothing()
                .when(mailGateway).send(any());

        ReportDeliveryService.DeliveryOutcome outcome = service.runAutoReport();

        assertThat(outcome.result()).isEqualTo(ReportDeliveryService.DeliveryOutcome.Result.SENT);
        verify(deliveryMapper, times(2)).markPendingRetry(anyLong(), any(), any(), any());
        verify(deliveryMapper).markSent(eq10(), any());
        verify(deliveryMapper, never()).markFailed(anyLong(), any(), any(), any());
        verify(sleeper).sleep(5_000L);
        verify(sleeper).sleep(30_000L);
        verify(mailGateway, times(3)).send(any());
    }

    @Test
    void exhaustedRetriesMarkFailedAfterThreeAttempts() {
        org.mockito.Mockito.doThrow(transientError()).when(mailGateway).send(any());

        ReportDeliveryService.DeliveryOutcome outcome = service.runAutoReport();

        assertThat(outcome.result()).isEqualTo(ReportDeliveryService.DeliveryOutcome.Result.FAILED);
        verify(deliveryMapper, times(2)).markPendingRetry(anyLong(), any(), any(), any());
        verify(deliveryMapper).markFailed(anyLong(), any(), any(), any());
        verify(mailGateway, times(3)).send(any());
    }

    @Test
    void permanentAuthFailureFailsImmediatelyWithoutRetry() {
        org.mockito.Mockito.doThrow(permanentError()).when(mailGateway).send(any());

        ReportDeliveryService.DeliveryOutcome outcome = service.runAutoReport();

        assertThat(outcome.result()).isEqualTo(ReportDeliveryService.DeliveryOutcome.Result.FAILED);
        verify(deliveryMapper).markFailed(anyLong(), any(), any(), any());
        verify(deliveryMapper, never()).markPendingRetry(anyLong(), any(), any(), any());
        verify(sleeper, never()).sleep(anyLong());
        verify(mailGateway).send(any());
    }

    @Test
    void autoDeliveryRecordsTheMaskedRecipientSnapshotJustLikeManual() {
        properties.setReportRecipients(List.of("owner@example.com", "ops@qq.com"));

        service.runAutoReport();

        ArgumentCaptor<String> masked = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> sha = ArgumentCaptor.forClass(String.class);
        verify(deliveryMapper).upsertAuto(
                any(), org.mockito.ArgumentMatchers.eq(1L), masked.capture(), sha.capture());
        assertThat(masked.getValue()).isEqualTo("o***@example.com,o***@qq.com");
        assertThat(sha.getValue()).hasSize(64).isNotBlank();
    }

    @Test
    void stableMessageIdCarriesReportDateVersionAndDeliveryId() {
        ArgumentCaptor<MailGateway.MailRequest> captor = ArgumentCaptor.forClass(MailGateway.MailRequest.class);

        service.runAutoReport();

        verify(mailGateway).send(captor.capture());
        assertThat(captor.getValue().stableMessageId())
                .contains("2026-07-27")
                .contains("v1")
                .contains("delivery-1");
    }

    private Long eq10() {
        return org.mockito.ArgumentMatchers.eq(10L);
    }

    private QqSmtpMailGateway.MailDeliveryException transientError() {
        return new QqSmtpMailGateway.MailDeliveryException(
                "java.net.SocketTimeoutException: timeout", new java.net.SocketTimeoutException("timeout"));
    }

    private QqSmtpMailGateway.MailDeliveryException permanentError() {
        return new QqSmtpMailGateway.MailDeliveryException(
                "jakarta.mail.AuthenticationFailedException: auth failed",
                new AuthenticationFailedException("auth failed"));
    }

    private DailyReportSnapshot snapshot() {
        return new DailyReportSnapshot(
                LocalDate.of(2026, 7, 27), 1,
                Instant.parse("2026-07-26T16:00:00Z"), Instant.parse("2026-07-27T16:00:00Z"),
                Instant.parse("2026-07-28T02:00:00Z"), 0L, 0L, List.of(),
                0L, 0L, 0L, 0L, 0L, List.of(), List.of(), List.of(), List.of(), 0L, 0L, 0L, true);
    }
}
