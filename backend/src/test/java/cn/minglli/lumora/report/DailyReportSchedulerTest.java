package cn.minglli.lumora.report;

import org.junit.jupiter.api.Test;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DailyReportSchedulerTest {

    @Test
    void runDailyReportDelegatesToDeliveryService() {
        ReportDeliveryService service = mock(ReportDeliveryService.class);
        when(service.runAutoReport())
                .thenReturn(new ReportDeliveryService.DeliveryOutcome(
                        ReportDeliveryService.DeliveryOutcome.Result.SENT, "delivery-1", null));
        DailyReportScheduler scheduler = new DailyReportScheduler(service);

        scheduler.runDailyReport();

        verify(service).runAutoReport();
    }
}
