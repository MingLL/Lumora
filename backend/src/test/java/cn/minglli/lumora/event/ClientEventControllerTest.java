package cn.minglli.lumora.event;

import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import cn.minglli.lumora.config.LumoraProperties;
import cn.minglli.lumora.operations.SiteUrlValidator;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class ClientEventControllerTest {

    private static final String VISIT_ID = "9f1c3b2a-4d5e-4f60-8a71-2b3c4d5e6f70";

    private ClientEventMapper mapper;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mapper = mock(ClientEventMapper.class);
        ObjectMapper objectMapper = new ObjectMapper();
        LumoraProperties properties = new LumoraProperties();
        properties.setSiteOrigin("https://lumora.love");
        mockMvc = MockMvcBuilders.standaloneSetup(
                        new ClientEventController(mapper, objectMapper, new SiteUrlValidator(properties)))
                .build();
    }

    @Test
    void acceptsExtensibleTypedEventAndPersistsJsonProperties() throws Exception {
        mockMvc.perform(post("/client-events")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(event("NETWORK_TYPE", "https://lumora.love/",
                                "{\"networkType\":\"wifi\"}")))
                .andExpect(status().isNoContent());

        verify(mapper).insert(argThat(record ->
                record.visitId().equals(VISIT_ID)
                        && record.type().equals("NETWORK_TYPE")
                        && record.propertiesJson().contains("wifi")));
    }

    @Test
    void rejectsMissingType() throws Exception {
        mockMvc.perform(post("/client-events")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"visitId\":\"" + VISIT_ID
                                + "\",\"url\":\"https://lumora.love/\",\"properties\":{}}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void rejectsOversizedPropertiesWithoutTouchingTheDatabase() throws Exception {
        String oversized = "x".repeat(4096);

        mockMvc.perform(post("/client-events")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(event("PAGE_OPEN", "https://lumora.love/",
                                "{\"blob\":\"" + oversized + "\"}")))
                .andExpect(status().isBadRequest());

        verifyNoInteractions(mapper);
    }

    @Test
    void rejectsUndeclaredEventTypes() throws Exception {
        mockMvc.perform(post("/client-events")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(event("ADMIN_LOGIN_FAILED", "https://lumora.love/", "{}")))
                .andExpect(status().isBadRequest());

        verifyNoInteractions(mapper);
    }

    @Test
    void rejectsDeclaredTypeCarryingUndeclaredProperties() throws Exception {
        mockMvc.perform(post("/client-events")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(event("PAGE_OPEN", "https://lumora.love/",
                                "{\"browser\":\"WECHAT\",\"injected\":\"x\"}")))
                .andExpect(status().isBadRequest());

        verifyNoInteractions(mapper);
    }

    @Test
    void rejectsOffSiteUrls() throws Exception {
        mockMvc.perform(post("/client-events")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(event("PAGE_OPEN", "https://evil.com/", "{\"browser\":\"WECHAT\"}")))
                .andExpect(status().isBadRequest());

        verifyNoInteractions(mapper);
    }

    @Test
    void acceptsUppercaseUuids() throws Exception {
        mockMvc.perform(post("/client-events")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"visitId\":\"" + VISIT_ID.toUpperCase()
                                + "\",\"type\":\"PAGE_OPEN\",\"url\":\"https://lumora.love/\","
                                + "\"properties\":{\"browser\":\"WECHAT\"}}"))
                .andExpect(status().isNoContent());

        verify(mapper).insert(argThat(record -> record.visitId().equals(VISIT_ID.toUpperCase())));
    }

    @ParameterizedTest
    @ValueSource(strings = {
            // 收紧之前前端在老 webview 上的兜底格式，现在不再接受。
            "1755168000000-a1b2c3d4e5f6",
            "visit-1",
            "9f1c3b2a4d5e4f608a712b3c4d5e6f70",          // 少了分隔符
            "9f1c3b2a-4d5e-4f60-8a71-2b3c4d5e6f7",       // 少一位
            "9f1c3b2a-4d5e-4f60-8a71-2b3c4d5e6f700",     // 多一位
            "9f1c3b2a-4d5e-4f60-8a71-2b3c4d5e6g70",      // 非十六进制
            "'; DROP TABLE client_event; --"})
    void rejectsVisitIdsThatAreNotUuids(String visitId) throws Exception {
        mockMvc.perform(post("/client-events")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"visitId\":\"" + visitId
                                + "\",\"type\":\"PAGE_OPEN\",\"url\":\"https://lumora.love/\","
                                + "\"properties\":{\"browser\":\"WECHAT\"}}"))
                .andExpect(status().isBadRequest());

        verifyNoInteractions(mapper);
    }

    private static String event(String type, String url, String propertiesJson) {
        return "{\"visitId\":\"" + VISIT_ID + "\",\"type\":\"" + type
                + "\",\"url\":\"" + url + "\",\"properties\":" + propertiesJson + "}";
    }
}
