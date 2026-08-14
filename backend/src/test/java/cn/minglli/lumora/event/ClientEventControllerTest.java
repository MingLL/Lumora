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
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class ClientEventControllerTest {
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
                        .content("""
                                {"visitId":"visit-1","type":"NETWORK_TYPE","url":"https://lumora.love/",
                                 "properties":{"networkType":"wifi"}}
                                """))
                .andExpect(status().isNoContent());

        verify(mapper).insert(argThat(record ->
                record.visitId().equals("visit-1")
                        && record.type().equals("NETWORK_TYPE")
                        && record.propertiesJson().contains("wifi")));
    }

    @Test
    void rejectsMissingType() throws Exception {
        mockMvc.perform(post("/client-events")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"visitId\":\"visit-1\",\"url\":\"https://lumora.love/\",\"properties\":{}}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void rejectsOversizedPropertiesWithoutTouchingTheDatabase() throws Exception {
        String oversized = "x".repeat(4096);

        mockMvc.perform(post("/client-events")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"visitId\":\"visit-1\",\"type\":\"PAGE_OPEN\",\"url\":\"https://lumora.love/\","
                                + "\"properties\":{\"blob\":\"" + oversized + "\"}}"))
                .andExpect(status().isBadRequest());

        verifyNoInteractions(mapper);
    }

    @Test
    void rejectsUndeclaredEventTypes() throws Exception {
        mockMvc.perform(post("/client-events")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"visitId\":\"visit-1\",\"type\":\"ADMIN_LOGIN_FAILED\","
                                + "\"url\":\"https://lumora.love/\",\"properties\":{}}"))
                .andExpect(status().isBadRequest());

        verifyNoInteractions(mapper);
    }

    @Test
    void rejectsDeclaredTypeCarryingUndeclaredProperties() throws Exception {
        mockMvc.perform(post("/client-events")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"visitId\":\"visit-1\",\"type\":\"PAGE_OPEN\","
                                + "\"url\":\"https://lumora.love/\","
                                + "\"properties\":{\"browser\":\"WECHAT\",\"injected\":\"x\"}}"))
                .andExpect(status().isBadRequest());

        verifyNoInteractions(mapper);
    }

    @Test
    void rejectsOffSiteUrls() throws Exception {
        mockMvc.perform(post("/client-events")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"visitId\":\"visit-1\",\"type\":\"PAGE_OPEN\","
                                + "\"url\":\"https://evil.com/\",\"properties\":{\"browser\":\"WECHAT\"}}"))
                .andExpect(status().isBadRequest());

        verifyNoInteractions(mapper);
    }
}
