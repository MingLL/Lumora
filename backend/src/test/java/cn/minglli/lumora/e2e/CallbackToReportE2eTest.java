package cn.minglli.lumora.e2e;

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.ZoneId;

import cn.minglli.lumora.report.DailyReportService;
import cn.minglli.lumora.report.DailyReportSnapshot;
import cn.minglli.lumora.support.PostgresContainerTest;
import me.chanjar.weixin.common.util.crypto.SHA1;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The whole path, once: a signed WeChat push arrives, lands in PostgreSQL exactly once
 * even when WeChat retries it, and shows up in the aggregated snapshot with the
 * counts the daily mail would report.
 *
 * <p>Timestamps are built relative to today rather than pinned, so the events stay
 * inside the 30-day skew window and inside the report window no matter when this
 * runs. A fixed CreateTime would quietly start exercising the anomalous-timestamp
 * branch a month after it was written.
 */
// @TestPropertySource, not @SpringBootTest: redeclaring @SpringBootTest here would
// replace the base class's annotation wholesale, dropping every required property
// it sets. @TestPropertySource merges on top of the inherited configuration.
@TestPropertySource(properties = {
        "lumora.wechat-original-id=gh_test_original",
        "lumora.scheduling-enabled=false",
        "lumora.report-recovery-enabled=false",
        "lumora.retention-enabled=false"
})
@AutoConfigureMockMvc
class CallbackToReportE2eTest extends PostgresContainerTest {

    private static final String APP_ID = "wx-app-id";
    private static final String ORIGINAL_ID = "gh_test_original";
    private static final String TOKEN = "wechat-token";
    private static final String NONCE = "e2e-nonce";
    private static final String PATH = "/wechat/callback/" + APP_ID;

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private DailyReportService dailyReportService;

    private LocalDate reportDate;
    private long createTime;

    @BeforeEach
    void clearAndAnchorTime() {
        jdbcTemplate.update("DELETE FROM report_delivery_attempt");
        jdbcTemplate.update("DELETE FROM daily_report");
        jdbcTemplate.update("DELETE FROM wechat_event");

        ZoneId zone = ZoneId.of("Asia/Shanghai");
        reportDate = LocalDate.now(zone).minusDays(1);
        // Midday yesterday: unambiguously inside the report window on both edges.
        createTime = reportDate.atTime(12, 0).atZone(zone).toInstant().getEpochSecond();
    }

    @Test
    void aRetriedPushIsStoredOnceAndCountedOnce() throws Exception {
        String body = subscribeXml("openid-e2e-1");

        postSigned(body).andExpect(status().isOk()).andExpect(content().string("success"));
        postSigned(body).andExpect(status().isOk()).andExpect(content().string("success"));

        assertThat(countEvents()).isEqualTo(1);

        DailyReportSnapshot snapshot =
                dailyReportService.getOrCreateSnapshotForDate(reportDate, false);
        assertThat(snapshot.totalEvents()).isEqualTo(1);
        assertThat(snapshot.totalUniqueUsers()).isEqualTo(1);
        assertThat(snapshot.subscribeEvents()).isEqualTo(1);
        assertThat(snapshot.emptyDay()).isFalse();
    }

    @Test
    void distinctUsersAndEventTypesAggregateSeparately() throws Exception {
        postSigned(subscribeXml("openid-e2e-1")).andExpect(status().isOk());
        postSigned(subscribeXml("openid-e2e-2")).andExpect(status().isOk());
        postSigned(scanXml("openid-e2e-1", "campaign-42")).andExpect(status().isOk());

        assertThat(countEvents()).isEqualTo(3);

        DailyReportSnapshot snapshot =
                dailyReportService.getOrCreateSnapshotForDate(reportDate, false);
        assertThat(snapshot.totalEvents()).isEqualTo(3);
        assertThat(snapshot.totalUniqueUsers()).isEqualTo(2);
        assertThat(snapshot.subscribeEvents()).isEqualTo(2);
        assertThat(snapshot.subscribeUniqueUsers()).isEqualTo(2);
        assertThat(snapshot.netGrowth()).isEqualTo(2);
        assertThat(snapshot.qrScenes())
                .anySatisfy(scene -> assertThat(scene.label()).isEqualTo("campaign-42"));
    }

    @Test
    void nonEventMessagesNeverReachTheEventStore() throws Exception {
        postSigned(textXml("openid-e2e-1"))
                .andExpect(status().isOk())
                .andExpect(content().string("success"));

        assertThat(countEvents()).isZero();

        DailyReportSnapshot snapshot =
                dailyReportService.getOrCreateSnapshotForDate(reportDate, false);
        assertThat(snapshot.emptyDay()).isTrue();
    }

    @Test
    void anUnsignedPushIsRejectedAndStoresNothing() throws Exception {
        mockMvc.perform(post(PATH)
                        .param("signature", "wrong")
                        .param("timestamp", timestamp())
                        .param("nonce", NONCE)
                        .contentType(MediaType.TEXT_XML)
                        .content(subscribeXml("openid-e2e-1")))
                .andExpect(status().isForbidden());

        assertThat(countEvents()).isZero();
    }

    private org.springframework.test.web.servlet.ResultActions postSigned(String body)
            throws Exception {
        String timestamp = timestamp();
        return mockMvc.perform(post(PATH)
                .param("signature", SHA1.gen(TOKEN, timestamp, NONCE))
                .param("timestamp", timestamp)
                .param("nonce", NONCE)
                .contentType(MediaType.TEXT_XML)
                .content(body.getBytes(StandardCharsets.UTF_8)));
    }

    private String timestamp() {
        return Long.toString(createTime);
    }

    private int countEvents() {
        Integer count = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM wechat_event", Integer.class);
        return count == null ? 0 : count;
    }

    private String subscribeXml(String openId) {
        return envelope(openId, "event", "<Event><![CDATA[subscribe]]></Event>");
    }

    private String scanXml(String openId, String scene) {
        return envelope(openId, "event",
                "<Event><![CDATA[SCAN]]></Event>"
                        + "<EventKey><![CDATA[" + scene + "]]></EventKey>"
                        + "<Ticket><![CDATA[ticket-private]]></Ticket>");
    }

    private String textXml(String openId) {
        return envelope(openId, "text",
                "<Content><![CDATA[hello]]></Content><MsgId>1234567890123456</MsgId>");
    }

    private String envelope(String openId, String msgType, String extra) {
        return "<xml>"
                + "<ToUserName><![CDATA[" + ORIGINAL_ID + "]]></ToUserName>"
                + "<FromUserName><![CDATA[" + openId + "]]></FromUserName>"
                + "<CreateTime>" + createTime + "</CreateTime>"
                + "<MsgType><![CDATA[" + msgType + "]]></MsgType>"
                + extra
                + "</xml>";
    }
}
