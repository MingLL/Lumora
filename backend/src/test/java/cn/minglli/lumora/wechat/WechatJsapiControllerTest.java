package cn.minglli.lumora.wechat;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
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

class WechatJsapiControllerTest {
    private WechatJsapiSignatureService signatureService;
    private JsapiSignatureErrorMapper errorMapper;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        signatureService = mock(WechatJsapiSignatureService.class);
        errorMapper = mock(JsapiSignatureErrorMapper.class);
        LumoraProperties properties = new LumoraProperties();
        properties.setSiteOrigin("https://lumora.love");
        mockMvc = MockMvcBuilders.standaloneSetup(
                new WechatJsapiController(signatureService, errorMapper,
                        new SiteUrlValidator(properties))).build();
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
    void rejectsErrorReportWithBlankFields() throws Exception {
        mockMvc.perform(post("/wechat/callback/jsapi-signature/error")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"url\":\"https://lumora.love/\",\"errMsg\":\"\"}"))
                .andExpect(status().isBadRequest());

        verifyNoInteractions(errorMapper);
    }

    @Test
    void rejectsErrorReportLongerThanTheColumnItLandsIn() throws Exception {
        // err_msg 是 VARCHAR(1024)：不在入口拦住的话，INSERT 必然抛异常，
        // 而那条路径会静默丢掉这次上报。
        String body = "{\"url\":\"https://lumora.love/\",\"errMsg\":\"" + "x".repeat(1025) + "\"}";
        mockMvc.perform(post("/wechat/callback/jsapi-signature/error")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest());

        verifyNoInteractions(errorMapper);
    }

    @Test
    void signatureEndpointDoesNotTouchErrorMapper() throws Exception {
        mockMvc.perform(get("/wechat/callback/jsapi-signature").param("url", "https://lumora.love/"))
                .andExpect(status().isOk());
        verifyNoInteractions(errorMapper);
    }

    @Test
    void refusesToSignOffSiteUrls() throws Exception {
        for (String offSite : new String[] {
                "https://evil.com/",
                "http://lumora.love/",                  // scheme 不同
                "https://lumora.love.evil.com/",        // 后缀伪装
                "https://lumora.love@evil.com/",        // 真实 host 是 evil.com
                "not-a-url"}) {
            mockMvc.perform(get("/wechat/callback/jsapi-signature").param("url", offSite))
                    .andExpect(status().isBadRequest());
        }
        verifyNoInteractions(signatureService);
    }

    @Test
    void signsArticleUrlsUnderTheSiteOrigin() throws Exception {
        mockMvc.perform(get("/wechat/callback/jsapi-signature")
                        .param("url", "https://lumora.love/posts/hello?from=timeline"))
                .andExpect(status().isOk());

        verify(signatureService).generateSignature("https://lumora.love/posts/hello?from=timeline");
    }
}
