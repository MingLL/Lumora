package cn.minglli.lumora.operations;

import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import cn.minglli.lumora.config.LumoraProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Proves an instance really is the background worker before its container is
 * reported healthy.
 *
 * <p>The release flow starts the new worker only after stopping the old one, so
 * "healthy" has to mean "actually doing background work" — not merely "process
 * started". A worker that came up with the wrong flags, or without a database,
 * or with no scheduled tasks registered, must never satisfy its health check;
 * otherwise the release completes with nobody sending the daily report.
 *
 * <p>The marker file is written to a temporary path and then atomically moved
 * into place, so a health check never observes a half-written marker. It lives
 * inside the container and disappears when the container is replaced.
 */
public class WorkerReadinessVerifier {

    private static final Logger log = LoggerFactory.getLogger(WorkerReadinessVerifier.class);

    /** Scheduled methods a worker must have registered, as {@code Class.method}. */
    static final List<String> REQUIRED_TASKS = List.of(
            "DailyReportScheduler.runDailyReport",
            "ReportRecoveryScheduler.recoverStaleDeliveries",
            "EventRetentionScheduler.runRetention");

    private final LumoraProperties properties;
    private final ScheduledTasks scheduledTasks;
    private final DatabaseProbe databaseProbe;
    private final Path markerPath;

    public WorkerReadinessVerifier(
            LumoraProperties properties,
            ScheduledTasks scheduledTasks,
            DatabaseProbe databaseProbe,
            Path markerPath) {
        this.properties = properties;
        this.scheduledTasks = scheduledTasks;
        this.databaseProbe = databaseProbe;
        this.markerPath = markerPath;
    }

    /**
     * Runs every check and, only when all of them pass, publishes the marker.
     *
     * @return true when this instance is a verified worker
     */
    public boolean verify() {
        if (!isWorkerMode()) {
            log.info("Not a worker instance, skipping readiness marker "
                            + "scheduling={} recovery={} retention={} internalSend={}",
                    properties.isSchedulingEnabled(), properties.isReportRecoveryEnabled(),
                    properties.isRetentionEnabled(), properties.isInternalSendEnabled());
            return false;
        }
        if (!databaseIsReachable()) {
            return false;
        }
        if (!scheduledTasksAreRegistered()) {
            return false;
        }
        return publishMarker();
    }

    /**
     * A worker owns all background work. Since 2026-08-09 it also serves the internal
     * send endpoint: the separate ops container was merged away to free ~180 MiB on
     * dev2, which was hitting node-level OOM. Internal sending is therefore no longer
     * a disqualifier here. The endpoint stays unreachable from the internet because
     * the Ingress only routes /wechat/callback; the contract test asserts that.
     */
    private boolean isWorkerMode() {
        return properties.isSchedulingEnabled()
                && properties.isReportRecoveryEnabled()
                && properties.isRetentionEnabled();
    }

    private boolean databaseIsReachable() {
        try {
            databaseProbe.probe();
            return true;
        } catch (RuntimeException exception) {
            log.error("Worker readiness failed: database probe errorClass={}",
                    exception.getClass().getSimpleName(), exception);
            return false;
        }
    }

    private boolean scheduledTasksAreRegistered() {
        Set<String> registered = scheduledTasks.descriptions();
        List<String> missing = REQUIRED_TASKS.stream()
                .filter(required -> registered.stream().noneMatch(task -> task.contains(required)))
                .toList();
        if (!missing.isEmpty()) {
            log.error("Worker readiness failed: scheduled tasks not registered {}", missing);
            return false;
        }
        return true;
    }

    private boolean publishMarker() {
        Path pending = markerPath.resolveSibling(markerPath.getFileName() + ".pending");
        try {
            Files.createDirectories(markerPath.toAbsolutePath().getParent());
            Files.writeString(pending, "ready\n");
            move(pending, markerPath);
            log.info("Worker readiness verified, marker published at {}", markerPath);
            return true;
        } catch (IOException exception) {
            log.error("Worker readiness failed: unable to publish marker at {}", markerPath, exception);
            return false;
        }
    }

    private static void move(Path pending, Path target) throws IOException {
        try {
            Files.move(pending, target,
                    StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException exception) {
            // Some container filesystems refuse atomic moves; a plain replace is
            // still better than writing the marker in place.
            Files.move(pending, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    /** Narrow seam over the datasource so the check is unit-testable without one. */
    @FunctionalInterface
    public interface DatabaseProbe {
        void probe();
    }

    /**
     * The registered scheduled methods, as {@code Class.method()} strings. Kept as a
     * seam because Spring's {@code ScheduledTask} cannot be constructed from a test.
     */
    @FunctionalInterface
    public interface ScheduledTasks {
        Set<String> descriptions();
    }
}
