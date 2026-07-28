package cn.minglli.lumora.report;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(prefix = "lumora", name = "report-recovery-enabled", havingValue = "true")
public class ReportRecoveryScheduler {

    private static final Logger log = LoggerFactory.getLogger(ReportRecoveryScheduler.class);

    private final ReportDeliveryService deliveryService;

    public ReportRecoveryScheduler(ReportDeliveryService deliveryService) {
        this.deliveryService = deliveryService;
    }

    @Scheduled(fixedDelay = 300_000, initialDelay = 300_000)
    public void recoverStaleDeliveries() {
        ReportDeliveryService.DeliveryOutcome outcome = deliveryService.recoverStaleDeliveries();
        log.info("Stale delivery recovery result={} deliveryId={}", outcome.result(), outcome.deliveryId());
    }
}
