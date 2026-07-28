package cn.minglli.lumora.operations;

import cn.minglli.lumora.config.LumoraProperties;
import cn.minglli.lumora.event.EventRetentionScheduler;
import cn.minglli.lumora.event.EventRetentionService;
import cn.minglli.lumora.report.DailyReportScheduler;
import cn.minglli.lumora.report.ReportDeliveryService;
import cn.minglli.lumora.report.ReportRecoveryScheduler;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * A candidate container serves public callbacks but must own no background work.
 *
 * <p>The release flow relies on this: the candidate is started while the previous
 * version is still the active worker. If a candidate registered the daily report
 * job, two instances would race for the same delivery, and the database lease
 * would be the only thing left preventing a double send.
 */
class CandidateModeTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withUserConfiguration(SchedulerConfiguration.class);

    @Test
    void candidateFlagsRegisterNoBackgroundJobs() {
        contextRunner.withPropertyValues(
                        "lumora.scheduling-enabled=false",
                        "lumora.report-recovery-enabled=false",
                        "lumora.retention-enabled=false",
                        "lumora.internal-send-enabled=false")
                .run(context -> assertThat(context)
                        .doesNotHaveBean(DailyReportScheduler.class)
                        .doesNotHaveBean(ReportRecoveryScheduler.class)
                        .doesNotHaveBean(EventRetentionScheduler.class));
    }

    @Test
    void workerFlagsRegisterEveryBackgroundJob() {
        contextRunner.withPropertyValues(
                        "lumora.scheduling-enabled=true",
                        "lumora.report-recovery-enabled=true",
                        "lumora.retention-enabled=true")
                .run(context -> assertThat(context)
                        .hasSingleBean(DailyReportScheduler.class)
                        .hasSingleBean(ReportRecoveryScheduler.class)
                        .hasSingleBean(EventRetentionScheduler.class));
    }

    @Test
    void eachJobCanBeDisabledIndependently() {
        contextRunner.withPropertyValues(
                        "lumora.scheduling-enabled=false",
                        "lumora.report-recovery-enabled=true",
                        "lumora.retention-enabled=true")
                .run(context -> assertThat(context)
                        .doesNotHaveBean(DailyReportScheduler.class)
                        .hasSingleBean(ReportRecoveryScheduler.class)
                        .hasSingleBean(EventRetentionScheduler.class));
    }

    @Test
    void omittingTheFlagsEntirelyRegistersNothing() {
        // @ConditionalOnProperty without matchIfMissing: absent means off, so a
        // container that forgets the flags stays inert rather than silently
        // becoming a second worker.
        contextRunner.run(context -> assertThat(context)
                .doesNotHaveBean(DailyReportScheduler.class)
                .doesNotHaveBean(ReportRecoveryScheduler.class)
                .doesNotHaveBean(EventRetentionScheduler.class));
    }

    @Test
    void candidatePropertiesReportNotAWorker() {
        LumoraProperties candidate = new LumoraProperties();
        candidate.setSchedulingEnabled(false);
        candidate.setReportRecoveryEnabled(false);
        candidate.setRetentionEnabled(false);
        candidate.setInternalSendEnabled(false);

        assertThat(candidate.isSchedulingEnabled()).isFalse();
        assertThat(candidate.isReportRecoveryEnabled()).isFalse();
        assertThat(candidate.isRetentionEnabled()).isFalse();
        assertThat(candidate.isInternalSendEnabled()).isFalse();
    }

    /** Imports the three schedulers so their {@code @ConditionalOnProperty} is evaluated. */
    @Configuration(proxyBeanMethods = false)
    @Import({DailyReportScheduler.class, ReportRecoveryScheduler.class, EventRetentionScheduler.class})
    static class SchedulerConfiguration {

        @Bean
        ReportDeliveryService reportDeliveryService() {
            return mock(ReportDeliveryService.class);
        }

        @Bean
        EventRetentionService eventRetentionService() {
            return mock(EventRetentionService.class);
        }
    }
}
