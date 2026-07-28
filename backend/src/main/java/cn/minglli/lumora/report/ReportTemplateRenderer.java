package cn.minglli.lumora.report;

import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.FormatStyle;
import java.util.List;
import java.util.Locale;

import cn.minglli.lumora.config.LumoraProperties;
import org.springframework.stereotype.Component;

@Component
public class ReportTemplateRenderer {

    private static final String MISSING_LABEL = "（未提供）";
    private static final DateTimeFormatter GENERATED_AT_FORMATTER =
            DateTimeFormatter.ofLocalizedDateTime(FormatStyle.FULL).withLocale(Locale.CHINA);

    private final ZoneId zone;

    public ReportTemplateRenderer(LumoraProperties properties) {
        this.zone = properties.getZone();
    }

    public RenderedReport render(DailyReportSnapshot snapshot, String originalId) {
        String subject = subject(snapshot, originalId);
        if (snapshot.emptyDay()) {
            return new RenderedReport(
                    subject,
                    emptyHtml(snapshot, originalId),
                    emptyText(snapshot, originalId));
        }
        return new RenderedReport(subject, html(snapshot, originalId), text(snapshot, originalId));
    }

    private String subject(DailyReportSnapshot snapshot, String originalId) {
        return originalId + " 微信公众号日报 - " + snapshot.reportDate();
    }

    private String emptyHtml(DailyReportSnapshot snapshot, String originalId) {
        return """
                <html>
                  <body>
                    <h1>%s 微信公众号日报</h1>
                    <p>报告日期：%s</p>
                    <p>快照版本：%d</p>
                    <p>生成时间：%s</p>
                    <p>昨日无事件。</p>
                  </body>
                </html>
                """.formatted(
                escape(originalId),
                snapshot.reportDate(),
                snapshot.version(),
                generatedAt(snapshot));
    }

    private String emptyText(DailyReportSnapshot snapshot, String originalId) {
        return """
                %s 微信公众号日报
                报告日期：%s
                快照版本：%d
                生成时间：%s

                昨日无事件。
                """.formatted(
                originalId,
                snapshot.reportDate(),
                snapshot.version(),
                generatedAt(snapshot));
    }

    private String html(DailyReportSnapshot snapshot, String originalId) {
        StringBuilder builder = new StringBuilder();
        builder.append("""
                <html>
                  <body>
                    <h1>%s 微信公众号日报</h1>
                    <p>报告日期：%s</p>
                    <p>快照版本：%d</p>
                    <p>生成时间：%s</p>
                    <h2>总览</h2>
                    <table border="1">
                      <tr><th>指标</th><th>事件数</th><th>去重用户数</th></tr>
                      <tr><td>总事件</td><td>%d</td><td>%d</td></tr>
                      <tr><td>新增关注</td><td>%d</td><td>%d</td></tr>
                      <tr><td>取消关注</td><td>%d</td><td>%d</td></tr>
                      <tr><td>净增长</td><td>%d</td><td>-</td></tr>
                      <tr><td>异常时间戳</td><td>%d</td><td>-</td></tr>
                    </table>
                    <h2>事件类型分布</h2>
                    <table border="1">
                      <tr><th>事件类型</th><th>事件数</th><th>去重用户数</th></tr>
                """.formatted(
                escape(originalId),
                snapshot.reportDate(),
                snapshot.version(),
                generatedAt(snapshot),
                snapshot.totalEvents(),
                snapshot.totalUniqueUsers(),
                snapshot.subscribeEvents(),
                snapshot.subscribeUniqueUsers(),
                snapshot.unsubscribeEvents(),
                snapshot.unsubscribeUniqueUsers(),
                snapshot.netGrowth(),
                snapshot.anomalousTimestampCount()));
        for (DailyReportSnapshot.EventTypeCount count : snapshot.byEventType()) {
            builder.append("""
                      <tr><td>%s</td><td>%d</td><td>%d</td></tr>
                    """.formatted(
                    count.eventType(), count.events(), count.uniqueUsers()));
        }
        builder.append("""
                    </table>
                    <h2>二维码场景分布</h2>
                    <table border="1">
                      <tr><th>场景</th><th>事件数</th><th>去重用户数</th></tr>
                """);
        appendLabelRows(builder, snapshot.qrScenes());
        builder.append("""
                    </table>
                    <h2>菜单点击 EventKey 分布</h2>
                    <table border="1">
                      <tr><th>EventKey</th><th>事件数</th><th>去重用户数</th></tr>
                """);
        appendLabelRows(builder, snapshot.menuClickKeys());
        builder.append("""
                    </table>
                    <h2>菜单跳转 URL 分布</h2>
                    <table border="1">
                      <tr><th>URL</th><th>事件数</th><th>去重用户数</th></tr>
                """);
        appendLabelRows(builder, snapshot.menuViewUrls());
        builder.append("""
                    </table>
                    <h2>复合菜单事件分布</h2>
                    <table border="1">
                      <tr><th>原始 Event</th><th>EventKey</th><th>事件数</th><th>去重用户数</th></tr>
                """);
        for (DailyReportSnapshot.MenuOtherCount count : snapshot.menuOther()) {
            builder.append("""
                      <tr><td>%s</td><td>%s</td><td>%d</td><td>%d</td></tr>
                    """.formatted(
                    escape(label(count.rawEvent())),
                    escape(label(count.eventKey())),
                    count.events(),
                    count.uniqueUsers()));
        }
        builder.append("""
                    </table>
                    <h2>地理位置上报</h2>
                    <table border="1">
                      <tr><th>指标</th><th>数值</th></tr>
                      <tr><td>上报次数</td><td>%d</td></tr>
                      <tr><td>去重用户数</td><td>%d</td></tr>
                    </table>
                  </body>
                </html>
                """.formatted(
                snapshot.locationReports(),
                snapshot.locationUniqueUsers()));
        return builder.toString();
    }

    private String text(DailyReportSnapshot snapshot, String originalId) {
        StringBuilder builder = new StringBuilder();
        builder.append("""
                %s 微信公众号日报
                报告日期：%s
                快照版本：%d
                生成时间：%s

                【总览】
                总事件：%d（去重用户 %d）
                新增关注：%d（去重用户 %d）
                取消关注：%d（去重用户 %d）
                净增长：%d
                异常时间戳：%d

                【事件类型分布】
                """.formatted(
                originalId,
                snapshot.reportDate(),
                snapshot.version(),
                generatedAt(snapshot),
                snapshot.totalEvents(),
                snapshot.totalUniqueUsers(),
                snapshot.subscribeEvents(),
                snapshot.subscribeUniqueUsers(),
                snapshot.unsubscribeEvents(),
                snapshot.unsubscribeUniqueUsers(),
                snapshot.netGrowth(),
                snapshot.anomalousTimestampCount()));
        for (DailyReportSnapshot.EventTypeCount count : snapshot.byEventType()) {
            builder.append(count.eventType())
                    .append("：")
                    .append(count.events())
                    .append("（去重用户 ")
                    .append(count.uniqueUsers())
                    .append("）\n");
        }
        builder.append("\n【二维码场景分布】\n");
        appendLabelText(builder, snapshot.qrScenes());
        builder.append("\n【菜单点击 EventKey 分布】\n");
        appendLabelText(builder, snapshot.menuClickKeys());
        builder.append("\n【菜单跳转 URL 分布】\n");
        appendLabelText(builder, snapshot.menuViewUrls());
        builder.append("\n【复合菜单事件分布】\n");
        for (DailyReportSnapshot.MenuOtherCount count : snapshot.menuOther()) {
            builder.append(label(count.rawEvent()))
                    .append(" / ")
                    .append(label(count.eventKey()))
                    .append("：")
                    .append(count.events())
                    .append("（去重用户 ")
                    .append(count.uniqueUsers())
                    .append("）\n");
        }
        builder.append("\n【地理位置上报】\n")
                .append("上报次数：").append(snapshot.locationReports()).append("\n")
                .append("去重用户数：").append(snapshot.locationUniqueUsers()).append("\n");
        return builder.toString();
    }

    private void appendLabelRows(StringBuilder builder, List<DailyReportSnapshot.LabelCount> rows) {
        for (DailyReportSnapshot.LabelCount count : rows) {
            builder.append("""
                      <tr><td>%s</td><td>%d</td><td>%d</td></tr>
                    """.formatted(escape(label(count.label())), count.events(), count.uniqueUsers()));
        }
        if (rows.isEmpty()) {
            builder.append("      <tr><td>-</td><td>0</td><td>0</td></tr>\n");
        }
    }

    private void appendLabelText(StringBuilder builder, List<DailyReportSnapshot.LabelCount> rows) {
        if (rows.isEmpty()) {
            builder.append("-\n");
            return;
        }
        for (DailyReportSnapshot.LabelCount count : rows) {
            builder.append(label(count.label()))
                    .append("：")
                    .append(count.events())
                    .append("（去重用户 ")
                    .append(count.uniqueUsers())
                    .append("）\n");
        }
    }

    private String label(String value) {
        return value == null || value.isBlank() ? MISSING_LABEL : value;
    }

    private String generatedAt(DailyReportSnapshot snapshot) {
        return snapshot.generatedAt().atZone(zone).format(GENERATED_AT_FORMATTER);
    }

    private String escape(String value) {
        return value == null ? ""
                : value.replace("&", "&amp;")
                        .replace("<", "&lt;")
                        .replace(">", "&gt;");
    }

    public record RenderedReport(String subject, String htmlBody, String textBody) {
    }
}
