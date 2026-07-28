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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

/**
 * Drives report delivery.
 *
 * <p>Deliberately not {@code @Transactional}. Every mapper call runs in its own
 * auto-committed statement so that the {@code claim} lease becomes visible to
 * other instances the moment it is taken, and so that SMTP I/O and retry backoff
 * never happen while a database transaction is open. Correctness comes from the
 * unique constraints on {@code report_delivery_attempt} plus the conditional
 * {@code claim} update, not from transaction scope.
 */
@Service
public class ReportDeliveryService {

    static final int MAX_ATTEMPTS = 3;
    static final Duration LEASE = Duration.ofMinutes(10);
    private static final Duration[] BACKOFF = {Duration.ofSeconds(5), Duration.ofSeconds(30)};
    private static final int MAX_RECIPIENT_MASK_LENGTH = 1024;

    private static final Logger log = LoggerFactory.getLogger(ReportDeliveryService.class);

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

    public DeliveryOutcome runAutoReport() {
        DailyReportSnapshot snapshot = dailyReportService.getOrCreateAutoSnapshot();
        DailyReportRecord record = dailyReportMapper.findByDateAndVersion(
                snapshot.reportDate(), snapshot.version());
        Long reportId = record.id();

        RecipientSnapshot recipients = recipientSnapshot();
        deliveryMapper.upsertAuto(
                UUID.randomUUID().toString(), reportId, recipients.masked(), recipients.sha256());
        ReportDeliveryRecord delivery = deliveryMapper.findAutoByReportId(reportId);
        return deliver(delivery, snapshot);
    }

    public DeliveryOutcome recoverStaleDeliveries() {
        Instant now = clock.instant();
        List<ReportDeliveryRecord> stale = deliveryMapper.findRecoverable(now);
        DeliveryOutcome last = DeliveryOutcome.none();
        for (ReportDeliveryRecord delivery : stale) {
            DailyReportRecord record = dailyReportMapper.findDailyReportById(delivery.reportId());
            if (record == null) {
                continue;
            }
            last = deliver(delivery, dailyReportService.load(record));
        }
        return last;
    }

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
        RecipientSnapshot recipients = recipientSnapshot();
        String deliveryId = UUID.randomUUID().toString();
        try {
            deliveryMapper.insertManual(
                    deliveryId, reportId, requestId, recipients.masked(), recipients.sha256());
        } catch (DuplicateKeyException exception) {
            return DeliveryOutcome.of(deliveryMapper.findByReportIdAndRequestId(reportId, requestId));
        }
        ReportDeliveryRecord delivery = deliveryMapper.findByDeliveryId(deliveryId);
        DailyReportRecord record = dailyReportMapper.findDailyReportById(reportId);
        return deliver(delivery, dailyReportService.load(record));
    }

    /**
     * Masked addresses plus a digest of the sorted list, for the delivery audit row.
     * Full addresses are read from protected configuration only at send time.
     */
    private RecipientSnapshot recipientSnapshot() {
        List<String> recipients = properties.getReportRecipients();
        String masked = String.join(",", recipients.stream().map(RecipientCodec::mask).toList());
        if (masked.length() > MAX_RECIPIENT_MASK_LENGTH) {
            masked = masked.substring(0, MAX_RECIPIENT_MASK_LENGTH);
        }
        return new RecipientSnapshot(masked, RecipientCodec.sha256(recipients.stream().sorted().toList()));
    }

    private record RecipientSnapshot(String masked, String sha256) {
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
                log.info("Delivery already claimed elsewhere deliveryId={} reportDate={}",
                        delivery.deliveryId(), snapshot.reportDate());
                return DeliveryOutcome.busy();
            }
            try {
                mailGateway.send(new MailGateway.MailRequest(
                        recipients, rendered.subject(), rendered.htmlBody(), rendered.textBody(), messageId));
                deliveryMapper.markSent(delivery.id(), clock.instant());
                log.info("Delivery sent deliveryId={} reportDate={} version={} attempt={}",
                        delivery.deliveryId(), snapshot.reportDate(), snapshot.version(), attempt);
                return DeliveryOutcome.sent(delivery.deliveryId());
            } catch (QqSmtpMailGateway.MailDeliveryException exception) {
                String errorClass = exception.getCause() == null
                        ? exception.getClass().getSimpleName()
                        : exception.getCause().getClass().getSimpleName();
                String summary = exception.getMessage();
                if (isPermanent(exception) || attempt >= MAX_ATTEMPTS) {
                    deliveryMapper.markFailed(delivery.id(), clock.instant(), errorClass, summary);
                    log.error("Delivery failed permanently deliveryId={} reportDate={} attempt={} "
                                    + "errorClass={} error={}",
                            delivery.deliveryId(), snapshot.reportDate(), attempt, errorClass, summary);
                    return DeliveryOutcome.failed(delivery.deliveryId(), summary);
                }
                deliveryMapper.markPendingRetry(delivery.id(), clock.instant(), errorClass, summary);
                log.warn("Delivery attempt failed, retrying deliveryId={} reportDate={} attempt={} "
                                + "errorClass={} error={}",
                        delivery.deliveryId(), snapshot.reportDate(), attempt, errorClass, summary);
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
