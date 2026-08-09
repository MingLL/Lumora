package cn.minglli.lumora.event;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class EventRetentionService {

    private static final Logger log = LoggerFactory.getLogger(EventRetentionService.class);
    private static final Duration COORDINATE_TTL = Duration.ofDays(30);
    private static final Duration RECORD_TTL = Duration.ofDays(400);

    private final EventRetentionMapper mapper;
    private final Clock clock;

    public EventRetentionService(EventRetentionMapper mapper, Clock clock) {
        this.mapper = mapper;
        this.clock = clock;
    }

    @Transactional
    public RetentionResult runRetention() {
        Instant now = clock.instant();
        Instant coordinateCutoff = now.minus(COORDINATE_TTL);
        Instant recordCutoff = now.minus(RECORD_TTL);

        int coordinatesNulled = mapper.nullCoordinatesOlderThan(coordinateCutoff);
        int deliveriesDeleted = mapper.deleteDeliveriesOlderThan(recordCutoff);
        int reportsDeleted = mapper.deleteUnreferencedReportsOlderThan(recordCutoff);
        int eventsDeleted = mapper.deleteEventsOlderThan(recordCutoff);
        int jsapiErrorsDeleted = mapper.deleteJsapiSignatureErrorsOlderThan(recordCutoff);

        log.info("Retention completed coordinatesNulled={} deliveriesDeleted={} reportsDeleted={} eventsDeleted={} jsapiErrorsDeleted={}",
                coordinatesNulled, deliveriesDeleted, reportsDeleted, eventsDeleted, jsapiErrorsDeleted);
        return new RetentionResult(coordinatesNulled, deliveriesDeleted, reportsDeleted, eventsDeleted, jsapiErrorsDeleted);
    }

    public record RetentionResult(
            int coordinatesNulled,
            int deliveriesDeleted,
            int reportsDeleted,
            int eventsDeleted,
            int jsapiErrorsDeleted) {
    }
}
