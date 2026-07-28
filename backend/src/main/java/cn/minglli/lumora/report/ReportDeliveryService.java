package cn.minglli.lumora.report;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import cn.minglli.lumora.config.LumoraProperties;
import cn.minglli.lumora.mail.MailGateway;
import cn.minglli.lumora.mail.QqSmtpMailGateway;
import jakarta.mail.AuthenticationFailedException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ReportDeliveryService {

    static final int MAX_ATTEMPTS = 3;
    static final Duration LEASE = Duration.ofMinutes(10);
    private static final Duration[] BACKOFF = {Duration.ofSeconds(5), Duration.ofSeconds(30)};

    private final DailyReportService dailyReportService;
    private final DailyReportMapper dailyReportMapper;
    private final ReportDeliveryMapper deliveryMapper;
    private final MailGateway mailGateway;
    private final ReportTemplateRenderer renderer;
    private final LumoraProperties properties;
    private final Clock clock;
    private final Sleeper sleeper;

    public ReportDeliveryService(
            DailyReportService dailyReportService,
            DailyReportMapper dailyReportMapper,
            ReportDeliveryMapper deliveryMapper,
            MailGateway mailGateway,
            ReportTemplateRenderer renderer,
            LumoraProperties properties,
            Clock clock,
            Sleeper sleeper) {
        this.dailyReportService = dailyReportService;
        this.dailyReportMapper = dailyReportMapper;
        this.deliveryMapper = deliveryMapper;
        this.mailGateway = mailGateway;
        this.renderer = renderer;
        this.properties = properties;
        this.clock = clock;
        this.sleeper = sleeper;
    }

    @Transactional
    public DeliveryOutcome runAutoReport() {
        DailyReportSnapshot snapshot = dailyReportService.getOrCreateAutoSnapshot();
        DailyReportRecord record = dailyReportMapper.findByDateAndVersion(
                snapshot.reportDate(), snapshot.version());
        Long reportId = record.id();

        deliveryMapper.upsertAuto(UUID.randomUUID().toString(), reportId);
        ReportDeliveryRecord delivery = deliveryMapper.findAutoByReportId(reportId);
        return deliver(delivery, snapshot);
    }

    @Transactional
    public DeliveryOutcome recoverStaleDeliveries() {
        Instant now = clock.instant();
        List<ReportDeliveryRecord> stale = deliveryMapper.findRecoverable(now);
        DeliveryOutcome last = DeliveryOutcome.none();
        for (ReportDeliveryRecord delivery : stale) {
            DailyReportRecord record = dailyReportMapper.findDailyReportById(delivery.reportId());
            if (record == null) {
                continue;
            }
            DailyReportSnapshot snapshot = dailyReportService.loadLatest(record.reportDate());
            last = deliver(delivery, snapshot);
        }
        return last;
    }

    @Transactional
    public DeliveryOutcome sendManual(Long reportId, String requestId, boolean force) {
        ReportDeliveryRecord existing = deliveryMapper.findByReportIdAndRequestId(reportId, requestId);
        if (existing != null) {
            return DeliveryOutcome.of(existing);
        }
        if (!force) {
            if (!deliveryMapper.findActiveByReportId(reportId).isEmpty()) {
                return DeliveryOutcome.conflict();
            }
            if (!deliveryMapper.findSentByReportId(reportId).isEmpty()) {
                return DeliveryOutcome.alreadySent();
            }
        }
        List<String> recipients = properties.getReportRecipients();
        String masked = recipients.stream().map(RecipientCodec::mask)
                .reduce((a, b) -> a + "," + b).orElse("");
        String sha = RecipientCodec.sha256(recipients.stream().sorted().toList());
        String deliveryId = UUID.randomUUID().toString();
        try {
            deliveryMapper.insertManual(deliveryId, reportId, requestId, masked, sha);
        } catch (DuplicateKeyException exception) {
            return DeliveryOutcome.of(deliveryMapper.findByReportIdAndRequestId(reportId, requestId));
        }
        ReportDeliveryRecord delivery = deliveryMapper.findByDeliveryId(deliveryId);
        DailyReportRecord record = dailyReportMapper.findDailyReportById(reportId);
        DailyReportSnapshot snapshot = dailyReportService.loadLatest(record.reportDate());
        return deliver(delivery, snapshot);
    }

    private DeliveryOutcome deliver(ReportDeliveryRecord delivery, DailyReportSnapshot snapshot) {
        if (delivery.status() == DeliveryStatus.SENT || delivery.status() == DeliveryStatus.FAILED) {
            return DeliveryOutcome.of(delivery);
        }
        ReportTemplateRenderer.RenderedReport rendered = renderer.render(snapshot, properties.getWechatOriginalId());
        String messageId = stableMessageId(snapshot, delivery);
        List<String> recipients = properties.getReportRecipients();
        int attempt = 1;
        while (true) {
            Instant now = clock.instant();
            Instant leaseUntil = now.plus(LEASE);
            int claimed = deliveryMapper.claim(delivery.id(), now, leaseUntil);
            if (claimed == 0) {
                return DeliveryOutcome.busy();
            }
            try {
                mailGateway.send(new MailGateway.MailRequest(
                        recipients, rendered.subject(), rendered.htmlBody(), rendered.textBody(), messageId));
                deliveryMapper.markSent(delivery.id(), clock.instant());
                return DeliveryOutcome.sent(delivery.deliveryId());
            } catch (QqSmtpMailGateway.MailDeliveryException exception) {
                String errorClass = exception.getCause() == null
                        ? exception.getClass().getSimpleName()
                        : exception.getCause().getClass().getSimpleName();
                String summary = exception.getMessage();
                if (isPermanent(exception) || attempt >= MAX_ATTEMPTS) {
                    deliveryMapper.markFailed(delivery.id(), clock.instant(), errorClass, summary);
                    return DeliveryOutcome.failed(delivery.deliveryId(), summary);
                }
                deliveryMapper.markPendingRetry(delivery.id(), clock.instant(), errorClass, summary);
                sleeper.sleep(BACKOFF[attempt - 1].toMillis());
                attempt++;
            }
        }
    }

    private boolean isPermanent(Throwable exception) {
        Throwable current = exception;
        while (current != null) {
            if (current instanceof AuthenticationFailedException) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }

    private String stableMessageId(DailyReportSnapshot snapshot, ReportDeliveryRecord delivery) {
        return "<lumora-" + snapshot.reportDate() + "-v" + snapshot.version()
                + "-" + delivery.deliveryId() + "@lumora>";
    }

    @FunctionalInterface
    public interface Sleeper {
        void sleep(long millis);
    }

    public record DeliveryOutcome(Result result, String deliveryId, String errorSummary) {
        enum Result { SENT, FAILED, BUSY, CONFLICT, ALREADY_SENT, NONE }

        static DeliveryOutcome sent(String id) { return new DeliveryOutcome(Result.SENT, id, null); }
        static DeliveryOutcome failed(String id, String error) { return new DeliveryOutcome(Result.FAILED, id, error); }
        static DeliveryOutcome busy() { return new DeliveryOutcome(Result.BUSY, null, null); }
        static DeliveryOutcome conflict() { return new DeliveryOutcome(Result.CONFLICT, null, null); }
        static DeliveryOutcome alreadySent() { return new DeliveryOutcome(Result.ALREADY_SENT, null, null); }
        static DeliveryOutcome none() { return new DeliveryOutcome(Result.NONE, null, null); }
        static DeliveryOutcome of(ReportDeliveryRecord record) {
            Result result = switch (record.status()) {
                case SENT -> Result.SENT;
                case FAILED -> Result.FAILED;
                default -> Result.BUSY;
            };
            return new DeliveryOutcome(result, record.deliveryId(), record.lastErrorSummary());
        }
    }
}
