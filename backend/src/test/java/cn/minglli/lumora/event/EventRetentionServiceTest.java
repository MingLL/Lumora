package cn.minglli.lumora.event;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class EventRetentionServiceTest {

    private static final Instant NOW = Instant.parse("2026-08-28T03:30:00Z");

    @Test
    void nullsCoordinatesAtThirtyDaysAndDeletesAtFourHundredDays() {
        EventRetentionMapper mapper = mock(EventRetentionMapper.class);
        Clock clock = Clock.fixed(NOW, ZoneId.of("Asia/Shanghai"));
        EventRetentionService service = new EventRetentionService(mapper, clock);

        EventRetentionService.RetentionResult result = service.runRetention();

        ArgumentCaptor<Instant> cutoff = ArgumentCaptor.forClass(Instant.class);
        verify(mapper).nullCoordinatesOlderThan(cutoff.capture());
        assertThat(cutoff.getValue()).isEqualTo(NOW.minus(java.time.Duration.ofDays(30)));

        List<Instant> deleteCutoffs = captureDeleteCutoffs(mapper);
        assertThat(deleteCutoffs).allSatisfy(value ->
                assertThat(value).isEqualTo(NOW.minus(java.time.Duration.ofDays(400))));
    }

    private List<Instant> captureDeleteCutoffs(EventRetentionMapper mapper) {
        ArgumentCaptor<Instant> deliveries = ArgumentCaptor.forClass(Instant.class);
        verify(mapper).deleteDeliveriesOlderThan(deliveries.capture());
        ArgumentCaptor<Instant> reports = ArgumentCaptor.forClass(Instant.class);
        verify(mapper).deleteUnreferencedReportsOlderThan(reports.capture());
        ArgumentCaptor<Instant> events = ArgumentCaptor.forClass(Instant.class);
        verify(mapper).deleteEventsOlderThan(events.capture());
        ArgumentCaptor<Instant> jsapiErrors = ArgumentCaptor.forClass(Instant.class);
        verify(mapper).deleteJsapiSignatureErrorsOlderThan(jsapiErrors.capture());
        return List.of(deliveries.getValue(), reports.getValue(), events.getValue(), jsapiErrors.getValue());
    }
}
