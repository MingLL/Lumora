package cn.minglli.lumora.report;

import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;

import cn.minglli.lumora.config.LumoraProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/internal/reports")
public class ManualReportController {

    private static final Logger log = LoggerFactory.getLogger(ManualReportController.class);

    private final DailyReportService dailyReportService;
    private final DailyReportMapper dailyReportMapper;
    private final ReportDeliveryService deliveryService;
    private final LumoraProperties properties;
    private final Clock clock;

    public ManualReportController(
            DailyReportService dailyReportService,
            DailyReportMapper dailyReportMapper,
            ReportDeliveryService deliveryService,
            LumoraProperties properties,
            Clock clock) {
        this.dailyReportService = dailyReportService;
        this.dailyReportMapper = dailyReportMapper;
        this.deliveryService = deliveryService;
        this.properties = properties;
        this.clock = clock;
    }

    @PostMapping("/{date}/send")
    public ResponseEntity<ManualSendResponse> send(
            @PathVariable("date") LocalDate date,
            @RequestHeader("X-Request-Id") String requestId,
            @RequestBody(required = false) ManualSendRequest request) {
        if (!properties.isInternalSendEnabled()) {
            log.warn("Rejected manual send while internal sending is disabled requestId={} date={}",
                    requestId, date);
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(
                    new ManualSendResponse("DISABLED", "Internal sending is disabled", null));
        }
        ZoneId zone = properties.getZone();
        LocalDate today = ZonedDateTime.now(clock).withZoneSameInstant(zone).toLocalDate();
        if (!date.isBefore(today)) {
            return ResponseEntity.badRequest().body(
                    new ManualSendResponse("REJECTED", "Report date must be before today", null));
        }
        boolean regenerate = request != null && Boolean.TRUE.equals(request.regenerate());
        boolean force = request != null && Boolean.TRUE.equals(request.force());

        DailyReportSnapshot snapshot = dailyReportService.getOrCreateSnapshotForDate(date, regenerate);
        DailyReportRecord record = dailyReportMapper.findByDateAndVersion(date, snapshot.version());
        if (record == null) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(new ManualSendResponse("ERROR", "Snapshot not found", null));
        }
        ReportDeliveryService.DeliveryOutcome outcome =
                deliveryService.sendManual(record.id(), requestId, force);
        log.info("Manual send requestId={} date={} regenerate={} force={} result={} deliveryId={}",
                requestId, date, regenerate, force, outcome.result(), outcome.deliveryId());
        return ResponseEntity.status(httpStatus(outcome))
                .body(new ManualSendResponse(outcome.result().name(), outcome.errorSummary(), outcome.deliveryId()));
    }

    private HttpStatus httpStatus(ReportDeliveryService.DeliveryOutcome outcome) {
        return switch (outcome.result()) {
            case SENT, ALREADY_SENT -> HttpStatus.OK;
            case FAILED -> HttpStatus.OK;
            case CONFLICT, BUSY -> HttpStatus.CONFLICT;
            case NONE -> HttpStatus.OK;
        };
    }

    public record ManualSendRequest(Boolean regenerate, Boolean force) {
    }

    public record ManualSendResponse(String result, String message, String deliveryId) {
    }
}
