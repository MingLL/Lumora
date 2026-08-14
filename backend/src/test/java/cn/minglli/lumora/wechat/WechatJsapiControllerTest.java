package cn.minglli.lumora.wechat;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class WechatJsapiControllerTest {
    private WechatJsapiSignatureService signatureService;
    private JsapiSignatureErrorMapper errorMapper;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        signatureService = mock(WechatJsapiSignatureService.class);
        errorMapper = mock(JsapiSignatureErrorMapper.class);
        mockMvc = MockMvcBuilders.standaloneSetup(
                new WechatJsapiController(signatureService, errorMapper)).build();
    }

    @Test
    void reportSignatureErrorReturnsNoContentAndPersists() throws Exception {
        String body = new ObjectMapper().writeValueAsString(
                new WechatJsapiController.JsapiSignatureErrorReport("https://lumora.love/", "invalid signature"));
        mockMvc.perform(post("/wechat/callback/jsapi-signature/error")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isNoContent());
        verify(errorMapper).insert(any(JsapiSignatureErrorRecord.class));
    }

    @Test
    void reportSignatureErrorReturnsNoContentEvenWhenPersistenceFails() throws Exception {
        doThrow(new RuntimeException("database down"))
                .when(errorMapper).insert(any(JsapiSignatureErrorRecord.class));
        String body = "{\"url\":\"https://lumora.love/\",\"errMsg\":\"invalid signature\"}";
        mockMvc.perform(post("/wechat/callback/jsapi-signature/error")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isNoContent());

        verify(errorMapper).insert(any(JsapiSignatureErrorRecord.class));
    }

    @Test
    void signatureEndpointDoesNotTouchErrorMapper() throws Exception {
        mockMvc.perform(get("/wechat/callback/jsapi-signature").param("url", "https://lumora.love/"))
                .andExpect(status().isOk());
        verifyNoInteractions(errorMapper);
    }
}
