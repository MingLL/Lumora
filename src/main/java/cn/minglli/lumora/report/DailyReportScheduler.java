package cn.minglli.lumora.report;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(prefix = "lumora", name = "scheduling-enabled", havingValue = "true")
public class DailyReportScheduler {

    private static final Logger log = LoggerFactory.getLogger(DailyReportScheduler.class);

    private final ReportDeliveryService deliveryService;

    public DailyReportScheduler(ReportDeliveryService deliveryService) {
        this.deliveryService = deliveryService;
    }

    @Scheduled(cron = "0 0 7 * * *", zone = "${lumora.zone:Asia/Shanghai}")
    public void runDailyReport() {
        ReportDeliveryService.DeliveryOutcome outcome = deliveryService.runAutoReport();
        log.info("Daily auto report delivery result={} deliveryId={}", outcome.result(), outcome.deliveryId());
    }
}
