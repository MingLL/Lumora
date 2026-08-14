package cn.minglli.lumora.event;

import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

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
        mockMvc = MockMvcBuilders.standaloneSetup(new ClientEventController(mapper, objectMapper))
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
}
