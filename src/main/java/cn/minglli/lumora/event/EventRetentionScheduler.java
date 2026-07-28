package cn.minglli.lumora.event;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(prefix = "lumora", name = "retention-enabled", havingValue = "true")
public class EventRetentionScheduler {

    private static final Logger log = LoggerFactory.getLogger(EventRetentionScheduler.class);

    private final EventRetentionService retentionService;

    public EventRetentionScheduler(EventRetentionService retentionService) {
        this.retentionService = retentionService;
    }

    @Scheduled(cron = "0 30 3 * * *", zone = "${lumora.zone:Asia/Shanghai}")
    public void runRetention() {
        EventRetentionService.RetentionResult result = retentionService.runRetention();
        log.info("Scheduled retention completed {}", result);
    }
}
