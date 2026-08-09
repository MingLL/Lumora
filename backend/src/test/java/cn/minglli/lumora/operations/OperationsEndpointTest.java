package cn.minglli.lumora.operations;

import cn.minglli.lumora.support.PostgresContainerTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Application-side protection of the operator surface.
 *
 * <p>The ingress only routes {@code /wechat/callback/}, so these paths should be
 * unreachable from the internet anyway. This asserts the second layer: even with
 * network access, {@code /internal/**} needs the admin key, and Actuator exposes
 * nothing but health. Network rules and application rules fail differently, and
 * the audit trail should not depend on only one of them holding.
 */
// @TestPropertySource merges with the base class's @SpringBootTest; redeclaring
// @SpringBootTest here would replace it and drop every required property.
@TestPropertySource(properties = "lumora.scheduling-enabled=false")
@AutoConfigureMockMvc
class OperationsEndpointTest extends PostgresContainerTest {

    private static final String YESTERDAY_PATH = "/internal/reports/2026-01-01/send";

    @Autowired
    private MockMvc mockMvc;

    @Test
    void internalSendWithoutAnAdminKeyIsUnauthorized() throws Exception {
        mockMvc.perform(post(YESTERDAY_PATH).header("X-Request-Id", "req-1"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void internalSendWithTheWrongAdminKeyIsUnauthorized() throws Exception {
        mockMvc.perform(post(YESTERDAY_PATH)
                        .header("X-Request-Id", "req-1")
                        .header("X-Lumora-Admin-Key", "not-the-key"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void internalSendWithoutARequestIdIsRejectedBeforeAuthentication() throws Exception {
        // Request ID is the manual-send idempotency key; without it a retry would
        // create a second delivery instead of returning the first one's result.
        mockMvc.perform(post(YESTERDAY_PATH).header("X-Lumora-Admin-Key", "admin-secret"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void livenessAndReadinessAreAvailable() throws Exception {
        mockMvc.perform(get("/actuator/health/liveness")).andExpect(status().isOk());
        mockMvc.perform(get("/actuator/health/readiness")).andExpect(status().isOk());
    }

    @Test
    void noOtherActuatorEndpointIsExposed() throws Exception {
        // env and configprops would print the AES key and the mail auth code.
        for (String path : new String[] {
                "/actuator/env", "/actuator/configprops", "/actuator/beans",
                "/actuator/mappings", "/actuator/loggers", "/actuator/heapdump",
                "/actuator/threaddump", "/actuator/metrics"}) {
            mockMvc.perform(get(path)).andExpect(status().isNotFound());
        }
    }
}
