package cn.minglli.lumora.operations;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;

import cn.minglli.lumora.config.LumoraProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThat;

class WorkerReadinessVerifierTest {

    @TempDir
    Path tempDir;

    private Path marker;
    private LumoraProperties properties;

    @BeforeEach
    void setUp() {
        marker = tempDir.resolve("nested").resolve("lumora-worker-ready");
        properties = new LumoraProperties();
        properties.setSchedulingEnabled(true);
        properties.setReportRecoveryEnabled(true);
        properties.setRetentionEnabled(true);
        properties.setInternalSendEnabled(false);
    }

    @Test
    void publishesTheMarkerOnlyWhenEveryCheckPasses() {
        boolean verified = verifier(allTasks(), () -> { }).verify();

        assertThat(verified).isTrue();
        assertThat(marker).exists();
        assertThat(marker.resolveSibling(marker.getFileName() + ".pending")).doesNotExist();
    }

    @Test
    void withoutADatabaseThereIsNoMarker() {
        boolean verified = verifier(allTasks(), () -> {
            throw new IllegalStateException("connection refused");
        }).verify();

        assertThat(verified).isFalse();
        assertThat(marker).doesNotExist();
    }

    @Test
    void withAMissingScheduledTaskThereIsNoMarker() {
        Set<String> withoutRetention = Set.of(
                "cn.minglli.lumora.report.DailyReportScheduler.runDailyReport()",
                "cn.minglli.lumora.report.ReportRecoveryScheduler.recoverStaleDeliveries()");

        boolean verified = verifier(withoutRetention, () -> { }).verify();

        assertThat(verified).isFalse();
        assertThat(marker).doesNotExist();
    }

    @Test
    void aCandidateInstanceNeverClaimsToBeAWorker() {
        properties.setSchedulingEnabled(false);
        properties.setReportRecoveryEnabled(false);
        properties.setRetentionEnabled(false);

        boolean verified = verifier(allTasks(), () -> { }).verify();

        assertThat(verified).isFalse();
        assertThat(marker).doesNotExist();
    }

    @Test
    void aWorkerThatAlsoServesInternalSendsIsStillAWorker() {
        // Until 2026-08-09 this asserted the opposite: internal sending belonged to a
        // separate ops container, so a worker with the flag on was disqualified. That
        // container was merged into the worker to free ~180 MiB on dev2, which was
        // hitting node-level OOM, so the flag no longer says anything about the role.
        // Keeping the assertion as a positive one matters: if someone reinstates the
        // old disqualifier, the worker silently stops publishing its readiness marker
        // and every rollout hangs on a probe that can never pass.
        properties.setInternalSendEnabled(true);

        boolean verified = verifier(allTasks(), () -> { }).verify();

        assertThat(verified).isTrue();
        assertThat(marker).exists();
    }

    @Test
    void aStaleMarkerFromAnEarlierRunIsReplacedAtomically() throws Exception {
        Files.createDirectories(marker.getParent());
        Files.writeString(marker, "stale");

        boolean verified = verifier(allTasks(), () -> { }).verify();

        assertThat(verified).isTrue();
        assertThat(Files.readString(marker)).isEqualTo("ready\n");
    }

    private WorkerReadinessVerifier verifier(
            Set<String> registeredTasks, WorkerReadinessVerifier.DatabaseProbe probe) {
        return new WorkerReadinessVerifier(properties, () -> registeredTasks, probe, marker);
    }

    private static Set<String> allTasks() {
        return Set.of(
                "cn.minglli.lumora.report.DailyReportScheduler.runDailyReport()",
                "cn.minglli.lumora.report.ReportRecoveryScheduler.recoverStaleDeliveries()",
                "cn.minglli.lumora.event.EventRetentionScheduler.runRetention()");
    }

}
