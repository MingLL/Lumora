package cn.minglli.lumora.analytics;

import java.time.Clock;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import cn.minglli.lumora.config.LumoraProperties;
import cn.minglli.lumora.operations.AdminKeyInterceptor;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class AnalyticsControllerTest {
    private JdbcTemplate jdbc;
    private MockMvc mvc;
    @BeforeEach void setup() {
        jdbc = mock(JdbcTemplate.class);
        var properties = new LumoraProperties(); properties.setReportAdminKey("test-key");
        mvc = MockMvcBuilders.standaloneSetup(new AnalyticsController(jdbc, Clock.systemUTC()))
                .addInterceptors(new AdminKeyInterceptor(properties)).build();
    }
    @Test void rejectsAnonymousAccessWithoutDatabaseQuery() throws Exception {
        mvc.perform(get("/api/analytics/summary").header("X-Request-Id", "test"))
                .andExpect(status().isUnauthorized()).andExpect(header().string("Cache-Control", "no-store"));
        verifyNoInteractions(jdbc);
    }
    @Test void rejectsInvalidAndOversizedWindows() throws Exception {
        for (String range : new String[]{"from=2026-09-29&to=2026-09-01", "from=2026-01-01&to=2026-09-29"}) {
            mvc.perform(get("/api/analytics/summary?" + range)
                    .header("X-Request-Id", "test").header("X-Lumora-Admin-Key", "test-key"))
                    .andExpect(status().isBadRequest());
        }
        verifyNoInteractions(jdbc);
    }
    @Test void rejectsNegativePageAndNonNumericIp() throws Exception {
        for (String query : new String[]{"page=-1", "ip=example.com", "ip=1.2.3.4'"}) {
            mvc.perform(get("/api/analytics/visits?" + query)
                    .header("X-Request-Id", "test").header("X-Lumora-Admin-Key", "test-key"))
                    .andExpect(status().isBadRequest());
        }
        verifyNoInteractions(jdbc);
    }
}
