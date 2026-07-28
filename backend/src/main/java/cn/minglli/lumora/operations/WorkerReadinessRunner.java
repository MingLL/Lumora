package cn.minglli.lumora.operations;

import java.nio.file.Path;

import cn.minglli.lumora.config.LumoraProperties;
import org.springframework.boot.CommandLineRunner;
import org.springframework.core.annotation.Order;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.config.ScheduledTaskHolder;
import org.springframework.stereotype.Component;

/**
 * Runs {@link WorkerReadinessVerifier} at startup, after {@link StartupModeRunner}
 * has had its chance to exit for migrate/schema-smoke modes.
 */
@Component
@Order(1)
public class WorkerReadinessRunner implements CommandLineRunner {

    private final WorkerReadinessVerifier verifier;

    public WorkerReadinessRunner(
            LumoraProperties properties,
            ScheduledTaskHolder scheduledTaskHolder,
            JdbcTemplate jdbcTemplate) {
        this.verifier = new WorkerReadinessVerifier(
                properties,
                () -> scheduledTaskHolder.getScheduledTasks().stream()
                        .map(task -> String.valueOf(task.getTask().getRunnable()))
                        .collect(java.util.stream.Collectors.toSet()),
                () -> jdbcTemplate.queryForObject("SELECT 1", Integer.class),
                Path.of(properties.getWorkerReadyMarker()));
    }

    @Override
    public void run(String... args) {
        verifier.verify();
    }
}
