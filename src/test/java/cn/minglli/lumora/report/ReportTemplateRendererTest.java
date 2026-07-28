package cn.minglli.lumora.report;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

import cn.minglli.lumora.event.EventType;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ReportTemplateRendererTest {

    private final ReportTemplateRenderer renderer = new ReportTemplateRenderer();

    @Test
    void rendersHtmlAndTextWithDateVersionAndGenerationTime() {
        DailyReportSnapshot snapshot = snapshot(false);

        ReportTemplateRenderer.RenderedReport rendered = renderer.render(snapshot, "gh_original");

        assertThat(rendered.subject()).contains("gh_original").contains(snapshot.reportDate().toString());
        assertThat(rendered.htmlBody())
                .contains(snapshot.reportDate().toString())
                .contains("快照版本：2")
                .contains("生成时间");
        assertThat(rendered.textBody())
                .contains(snapshot.reportDate().toString())
                .contains("快照版本：2")
                .contains("生成时间");
    }

    @Test
    void emptyDayReportStatesYesterdayHadNoEvents() {
        DailyReportSnapshot snapshot = snapshot(true);

        ReportTemplateRenderer.RenderedReport rendered = renderer.render(snapshot, "gh_original");

        assertThat(rendered.htmlBody()).contains("昨日无事件");
        assertThat(rendered.textBody()).contains("昨日无事件");
    }

    @Test
    void normalizesMissingDimensionsToProvidedPlaceholder() {
        DailyReportSnapshot snapshot = new DailyReportSnapshot(
                LocalDate.of(2026, 7, 27), 1,
                Instant.parse("2026-07-26T16:00:00Z"),
                Instant.parse("2026-07-27T16:00:00Z"),
                Instant.parse("2026-07-28T02:00:00Z"),
                1L, 1L,
                List.of(new DailyReportSnapshot.EventTypeCount(EventType.SCAN, 1L, 1L)),
                0L, 0L, 0L, 0L, 0L,
                List.of(new DailyReportSnapshot.LabelCount(null, 1L, 1L)),
                List.of(new DailyReportSnapshot.LabelCount("  ", 1L, 1L)),
                List.of(new DailyReportSnapshot.LabelCount(null, 1L, 1L)),
                List.of(new DailyReportSnapshot.MenuOtherCount("pic_sysphoto", null, 1L, 1L)),
                0L, 0L, 0L, false);

        ReportTemplateRenderer.RenderedReport rendered = renderer.render(snapshot, "gh_original");

        assertThat(rendered.htmlBody()).contains("（未提供）");
        assertThat(rendered.textBody()).contains("（未提供）");
    }

    @Test
    void neverEmitsDecimalCoordinates() {
        DailyReportSnapshot snapshot = snapshot(false);

        ReportTemplateRenderer.RenderedReport rendered = renderer.render(snapshot, "gh_original");

        assertThat(rendered.htmlBody()).doesNotContain("31.2304167").doesNotContain("121.4737012");
        assertThat(rendered.textBody()).doesNotContain("31.2304167").doesNotContain("121.4737012");
    }

    private DailyReportSnapshot snapshot(boolean emptyDay) {
        return new DailyReportSnapshot(
                LocalDate.of(2026, 7, 27), 2,
                Instant.parse("2026-07-26T16:00:00Z"),
                Instant.parse("2026-07-27T16:00:00Z"),
                Instant.parse("2026-07-28T02:00:00Z"),
                emptyDay ? 0L : 4L,
                emptyDay ? 0L : 3L,
                emptyDay ? List.of() : List.of(
                        new DailyReportSnapshot.EventTypeCount(EventType.SUBSCRIBE, 2L, 2L),
                        new DailyReportSnapshot.EventTypeCount(EventType.UNSUBSCRIBE, 1L, 1L),
                        new DailyReportSnapshot.EventTypeCount(EventType.SCAN, 1L, 1L)),
                emptyDay ? 0L : 2L, emptyDay ? 0L : 2L,
                emptyDay ? 0L : 1L, emptyDay ? 0L : 1L,
                emptyDay ? 0L : 1L,
                emptyDay ? List.of() : List.of(
                        new DailyReportSnapshot.LabelCount("a", 1L, 1L)),
                emptyDay ? List.of() : List.of(
                        new DailyReportSnapshot.LabelCount("click1", 1L, 1L)),
                emptyDay ? List.of() : List.of(
                        new DailyReportSnapshot.LabelCount("http://a.example", 1L, 1L)),
                emptyDay ? List.of() : List.of(
                        new DailyReportSnapshot.MenuOtherCount("scancode_push", "scan1", 1L, 1L)),
                emptyDay ? 0L : 1L, emptyDay ? 0L : 1L,
                emptyDay ? 0L : 1L, emptyDay);
    }
}
