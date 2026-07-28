package cn.minglli.lumora.wechat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import cn.minglli.lumora.config.LumoraProperties;
import cn.minglli.lumora.event.EventType;
import cn.minglli.lumora.event.WechatEvent;
import cn.minglli.lumora.event.WechatEventRepository;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import me.chanjar.weixin.common.util.crypto.SHA1;
import me.chanjar.weixin.common.util.crypto.WxCryptUtil;
import me.chanjar.weixin.mp.config.impl.WxMpDefaultConfigImpl;
import me.chanjar.weixin.mp.util.crypto.WxMpCryptUtil;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class WechatCallbackControllerTest {

    private static final String APP_ID = "wx_test_app";
    private static final String ORIGINAL_ID = "gh_test_original";
    private static final String TOKEN = "fixed-non-production-token";
    private static final String AES_KEY = "abcdefghijklmnopqrstuvwxyz0123456789ABCDEFG";
    private static final String TIMESTAMP = "1785240000";
    private static final String NONCE = "fixed-nonce";
    private static final String PATH = "/wechat/callback/" + APP_ID;

    private CapturingRepository repository;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        LumoraProperties properties = new LumoraProperties();
        properties.setWechatAppId(APP_ID);
        properties.setWechatOriginalId(ORIGINAL_ID);
        properties.setWechatToken(TOKEN);
        properties.setWechatAesKey(AES_KEY);

        repository = new CapturingRepository();
        WechatEventIngestionService ingestion = new WechatEventIngestionService(
                repository,
                Clock.fixed(Instant.parse("2026-07-28T12:00:00Z"), ZoneOffset.UTC),
                new SimpleMeterRegistry());
        WechatProtocolService protocol = new WechatProtocolService(
                properties, new SafeXmlParser());
        WechatCallbackController controller =
                new WechatCallbackController(protocol, ingestion);
        mockMvc = MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(new WechatCallbackExceptionHandler())
                .build();
    }

    @Test
    void getReturnsNotFoundForPathAppIdMismatchBeforeSignatureHandling() throws Exception {
        mockMvc.perform(get("/wechat/callback/wx_other")
                        .queryParam("signature", signature())
                        .queryParam("timestamp", TIMESTAMP)
                        .queryParam("nonce", NONCE)
                        .queryParam("echostr", "echo-exact"))
                .andExpect(status().isNotFound());

        assertThat(repository.calls).isZero();
    }

    @Test
    void getRejectsInvalidSignature() throws Exception {
        mockMvc.perform(get(PATH)
                        .queryParam("signature", "invalid")
                        .queryParam("timestamp", TIMESTAMP)
                        .queryParam("nonce", NONCE)
                        .queryParam("echostr", "echo-exact"))
                .andExpect(status().isForbidden());

        assertThat(repository.calls).isZero();
    }

    @Test
    void getReturnsExactEchoForValidSignature() throws Exception {
        mockMvc.perform(get(PATH)
                        .queryParam("signature", signature())
                        .queryParam("timestamp", TIMESTAMP)
                        .queryParam("nonce", NONCE)
                        .queryParam("echostr", "echo-exact"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.TEXT_PLAIN))
                .andExpect(content().string("echo-exact"));
    }

    @ParameterizedTest
    @MethodSource("eventFixtures")
    void plaintextFixturesNormalizeAndInsert(
            String fixture, EventType expectedType, String expectedCompositeType) throws Exception {
        mockMvc.perform(plainPost(APP_ID, fixture(fixture), signature()))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.TEXT_PLAIN))
                .andExpect(content().string("success"));

        assertThat(repository.calls).isOne();
        assertThat(repository.event.eventType()).isEqualTo(expectedType);
        assertThat(repository.event.compositeType()).isEqualTo(expectedCompositeType);
        assertThat(repository.event.safeSummary())
                .doesNotContain("openid-private", "ticket-private", "private-");
    }

    static Stream<Arguments> eventFixtures() {
        return Stream.of(
                Arguments.of("subscribe.xml", EventType.SUBSCRIBE, null),
                Arguments.of("unsubscribe.xml", EventType.UNSUBSCRIBE, null),
                Arguments.of("qr-subscribe.xml", EventType.SUBSCRIBE, null),
                Arguments.of("scan.xml", EventType.SCAN, null),
                Arguments.of("location.xml", EventType.LOCATION, null),
                Arguments.of("click.xml", EventType.MENU_CLICK, null),
                Arguments.of("view.xml", EventType.MENU_VIEW, null),
                Arguments.of("scancode-push.xml", EventType.MENU_OTHER, "ScanCodeInfo"),
                Arguments.of("scancode-waitmsg.xml", EventType.MENU_OTHER, "ScanCodeInfo"),
                Arguments.of("pic-sysphoto.xml", EventType.MENU_OTHER, "SendPicsInfo"),
                Arguments.of("pic-photo-or-album.xml", EventType.MENU_OTHER, "SendPicsInfo"),
                Arguments.of("pic-weixin.xml", EventType.MENU_OTHER, "SendPicsInfo"),
                Arguments.of("location-select.xml", EventType.MENU_OTHER, "SendLocationInfo"),
                Arguments.of("unknown-event.xml", EventType.UNKNOWN, null));
    }

    @Test
    void sendPicsCountParticipatesInFingerprintAndPersistedMetadata() throws Exception {
        String onePicture = fixture("pic-sysphoto.xml");
        String twoDeclaredPictures = onePicture.replace("<Count>1</Count>", "<Count>2</Count>");

        mockMvc.perform(plainPost(APP_ID, onePicture, signature()))
                .andExpect(status().isOk());
        mockMvc.perform(plainPost(APP_ID, twoDeclaredPictures, signature()))
                .andExpect(status().isOk());

        assertThat(repository.events).hasSize(2);
        WechatEvent first = repository.events.get(0);
        WechatEvent second = repository.events.get(1);
        assertThat(first.compositeItemCount()).isEqualTo(1);
        assertThat(second.compositeItemCount()).isEqualTo(2);
        assertThat(first.compositeSha256()).isNotEqualTo(second.compositeSha256());
        assertThat(first.deduplicationKey()).isNotEqualTo(second.deduplicationKey());
        assertThat(first.normalizedMessageSha256())
                .isNotEqualTo(second.normalizedMessageSha256());
    }

    @Test
    void plaintextRouteMismatchWinsBeforeBodyProcessing() throws Exception {
        mockMvc.perform(plainPost("wx_other", "<not-xml>", "invalid"))
                .andExpect(status().isNotFound());

        assertThat(repository.calls).isZero();
    }

    @Test
    void plaintextRejectsInvalidSignatureWithoutRepositoryInteraction() throws Exception {
        mockMvc.perform(plainPost(APP_ID, fixture("subscribe.xml"), "invalid"))
                .andExpect(status().isForbidden());

        assertThat(repository.calls).isZero();
    }

    @Test
    void plaintextRejectsOriginalIdMismatch() throws Exception {
        String body = fixture("subscribe.xml").replace(ORIGINAL_ID, "gh_other");

        mockMvc.perform(plainPost(APP_ID, body, signature()))
                .andExpect(status().isForbidden());

        assertThat(repository.calls).isZero();
    }

    @Test
    void rejectsMalformedAndXxeXml() throws Exception {
        mockMvc.perform(plainPost(APP_ID, "<xml><Event>subscribe</xml>", signature()))
                .andExpect(status().isBadRequest());
        mockMvc.perform(plainPost(APP_ID, """
                        <!DOCTYPE xml [<!ENTITY xxe SYSTEM "file:///etc/passwd">]>
                        <xml><ToUserName>&xxe;</ToUserName></xml>
                        """, signature()))
                .andExpect(status().isBadRequest());

        assertThat(repository.calls).isZero();
    }

    @Test
    void rejectsOutOfRangeCreateTimeAsMalformedInput() throws Exception {
        String body = fixture("subscribe.xml")
                .replace("1785240000", Long.toString(Long.MAX_VALUE));

        mockMvc.perform(plainPost(APP_ID, body, signature()))
                .andExpect(status().isBadRequest());

        assertThat(repository.calls).isZero();
    }

    @ParameterizedTest
    @ValueSource(strings = {"91.0000000", "31.12345678"})
    void rejectsInvalidCoordinateRangeOrScaleWithoutPersistence(String latitude)
            throws Exception {
        String body = fixture("location.xml").replace("31.2304000", latitude);

        mockMvc.perform(plainPost(APP_ID, body, signature()))
                .andExpect(status().isBadRequest());

        assertThat(repository.calls).isZero();
    }

    @Test
    void rejectsBodyLargerThan256KiBBeforeParsing() throws Exception {
        byte[] oversized = new byte[WechatCallbackController.MAX_REQUEST_BODY_BYTES + 1];

        mockMvc.perform(post(PATH)
                        .queryParam("signature", signature())
                        .queryParam("timestamp", TIMESTAMP)
                        .queryParam("nonce", NONCE)
                        .contentType(MediaType.APPLICATION_XML)
                        .content(oversized))
                .andExpect(status().isPayloadTooLarge());

        assertThat(repository.calls).isZero();
    }

    @Test
    void ordinaryMessageReturnsSuccessWithoutInsert() throws Exception {
        mockMvc.perform(plainPost(APP_ID, fixture("text-message.xml"), signature()))
                .andExpect(status().isOk())
                .andExpect(content().string("success"));

        assertThat(repository.calls).isZero();
    }

    @Test
    void duplicateReturnsSuccess() throws Exception {
        repository.result = WechatEventRepository.InsertResult.DUPLICATE;

        mockMvc.perform(plainPost(APP_ID, fixture("subscribe.xml"), signature()))
                .andExpect(status().isOk())
                .andExpect(content().string("success"));
    }

    @Test
    void unrelatedDatabaseFailureReturnsServiceUnavailable() throws Exception {
        repository.failure = new DataAccessResourceFailureException("database unavailable");

        mockMvc.perform(plainPost(APP_ID, fixture("subscribe.xml"), signature()))
                .andExpect(status().isServiceUnavailable());
    }

    @Test
    void encryptedFixtureGeneratedByWeixinApiDecryptsAndInserts() throws Exception {
        EncryptedRequest encrypted = encrypt(
                fixture("subscribe.xml"), APP_ID, ORIGINAL_ID);

        mockMvc.perform(encryptedPost(APP_ID, encrypted))
                .andExpect(status().isOk())
                .andExpect(content().string("success"));

        assertThat(repository.calls).isOne();
        assertThat(repository.event.eventType()).isEqualTo(EventType.SUBSCRIBE);
    }

    @Test
    void encryptedMalformedXmlReturnsBadRequestWithoutPersistence() throws Exception {
        EncryptedRequest encrypted = encrypt(
                "<xml><Event>subscribe</xml>", APP_ID, ORIGINAL_ID);

        mockMvc.perform(encryptedPost(APP_ID, encrypted))
                .andExpect(status().isBadRequest());

        assertThat(repository.calls).isZero();
    }

    @Test
    void encryptedXxeReturnsBadRequestWithoutPersistence() throws Exception {
        EncryptedRequest encrypted = encrypt("""
                <!DOCTYPE xml [<!ENTITY xxe SYSTEM "file:///etc/passwd">]>
                <xml>
                  <ToUserName>&xxe;</ToUserName>
                  <FromUserName>openid-private</FromUserName>
                  <CreateTime>1785240000</CreateTime>
                  <MsgType>event</MsgType>
                  <Event>subscribe</Event>
                </xml>
                """, APP_ID, ORIGINAL_ID);

        mockMvc.perform(encryptedPost(APP_ID, encrypted))
                .andExpect(status().isBadRequest());

        assertThat(repository.calls).isZero();
    }

    @Test
    void encryptedDeeplyNestedXmlReturnsBadRequestWithoutPersistence() throws Exception {
        StringBuilder inner = new StringBuilder("<xml>");
        for (int depth = 1; depth <= SafeXmlParser.MAX_ELEMENT_DEPTH; depth++) {
            inner.append("<node>");
        }
        inner.append("<tooDeep/>");
        for (int depth = 1; depth <= SafeXmlParser.MAX_ELEMENT_DEPTH; depth++) {
            inner.append("</node>");
        }
        inner.append("</xml>");
        EncryptedRequest encrypted = encrypt(inner.toString(), APP_ID, ORIGINAL_ID);

        mockMvc.perform(encryptedPost(APP_ID, encrypted))
                .andExpect(status().isBadRequest());

        assertThat(repository.calls).isZero();
    }

    @Test
    void encryptedOversizedBodyReturnsPayloadTooLarge() throws Exception {
        byte[] oversized = new byte[WechatCallbackController.MAX_REQUEST_BODY_BYTES + 1];

        mockMvc.perform(post(PATH)
                        .queryParam("encrypt_type", "aes")
                        .queryParam("msg_signature", "irrelevant")
                        .queryParam("timestamp", TIMESTAMP)
                        .queryParam("nonce", NONCE)
                        .contentType(MediaType.APPLICATION_XML)
                        .content(oversized))
                .andExpect(status().isPayloadTooLarge());

        assertThat(repository.calls).isZero();
    }

    @Test
    void encryptedOrdinaryMessageReturnsSuccessWithoutInsert() throws Exception {
        EncryptedRequest encrypted = encrypt(
                fixture("text-message.xml"), APP_ID, ORIGINAL_ID);

        mockMvc.perform(encryptedPost(APP_ID, encrypted))
                .andExpect(status().isOk())
                .andExpect(content().string("success"));

        assertThat(repository.calls).isZero();
    }

    @Test
    void encryptedDuplicateReturnsSuccess() throws Exception {
        repository.result = WechatEventRepository.InsertResult.DUPLICATE;
        EncryptedRequest encrypted = encrypt(
                fixture("subscribe.xml"), APP_ID, ORIGINAL_ID);

        mockMvc.perform(encryptedPost(APP_ID, encrypted))
                .andExpect(status().isOk())
                .andExpect(content().string("success"));

        assertThat(repository.calls).isOne();
    }

    @Test
    void encryptedDatabaseFailureReturnsServiceUnavailable() throws Exception {
        repository.failure = new DataAccessResourceFailureException("database unavailable");
        EncryptedRequest encrypted = encrypt(
                fixture("subscribe.xml"), APP_ID, ORIGINAL_ID);

        mockMvc.perform(encryptedPost(APP_ID, encrypted))
                .andExpect(status().isServiceUnavailable());

        assertThat(repository.calls).isOne();
    }

    @Test
    void encryptedRouteMismatchWinsBeforeBodyProcessing() throws Exception {
        EncryptedRequest encrypted = encrypt(
                fixture("subscribe.xml"), APP_ID, ORIGINAL_ID);

        mockMvc.perform(encryptedPost("wx_other", encrypted))
                .andExpect(status().isNotFound());

        assertThat(repository.calls).isZero();
    }

    @Test
    void encryptedRejectsInvalidMessageSignature() throws Exception {
        EncryptedRequest encrypted = encrypt(
                fixture("subscribe.xml"), APP_ID, ORIGINAL_ID);
        encrypted = new EncryptedRequest(
                encrypted.body(), "invalid", encrypted.timestamp(), encrypted.nonce());

        mockMvc.perform(encryptedPost(APP_ID, encrypted))
                .andExpect(status().isForbidden());

        assertThat(repository.calls).isZero();
    }

    @Test
    void encryptedRejectsEnvelopeAppIdMismatch() throws Exception {
        EncryptedRequest encrypted = encrypt(
                fixture("subscribe.xml"), APP_ID, ORIGINAL_ID);
        encrypted = new EncryptedRequest(
                encrypted.body().replace("<AppId>" + APP_ID + "</AppId>",
                        "<AppId>wx_other</AppId>"),
                encrypted.signature(),
                encrypted.timestamp(),
                encrypted.nonce());

        mockMvc.perform(encryptedPost(APP_ID, encrypted))
                .andExpect(status().isForbidden());

        assertThat(repository.calls).isZero();
    }

    @Test
    void encryptedRejectsAvailableOuterRecipientMismatch() throws Exception {
        EncryptedRequest encrypted = encrypt(
                fixture("subscribe.xml"), APP_ID, "gh_other");

        mockMvc.perform(encryptedPost(APP_ID, encrypted))
                .andExpect(status().isForbidden());

        assertThat(repository.calls).isZero();
    }

    @Test
    void encryptedRejectsDecryptedOriginalIdMismatch() throws Exception {
        String inner = fixture("subscribe.xml").replace(ORIGINAL_ID, "gh_other");
        EncryptedRequest encrypted = encrypt(inner, APP_ID, ORIGINAL_ID);

        mockMvc.perform(encryptedPost(APP_ID, encrypted))
                .andExpect(status().isForbidden());

        assertThat(repository.calls).isZero();
    }

    @Test
    void encryptedRejectsDecryptedAppIdMismatchWhenPresent() throws Exception {
        String inner = fixture("subscribe.xml")
                .replace("<xml>", "<xml><AppId>wx_other</AppId>");
        EncryptedRequest encrypted = encrypt(inner, APP_ID, ORIGINAL_ID);

        mockMvc.perform(encryptedPost(APP_ID, encrypted))
                .andExpect(status().isForbidden());

        assertThat(repository.calls).isZero();
    }

    @Test
    void encryptedRejectsCryptographicAppIdMismatchWithoutXmlAppId() throws Exception {
        EncryptedRequest encrypted = encryptWithoutEnvelopeAppId(
                fixture("subscribe.xml"), "wx_wrong_cryptographic_app", ORIGINAL_ID);

        mockMvc.perform(encryptedPost(APP_ID, encrypted))
                .andExpect(status().isForbidden());

        assertThat(repository.calls).isZero();
    }

    @Test
    void encryptedRejectsCorruptCiphertextWithValidMessageSignature() throws Exception {
        String corrupt = "not-valid-ciphertext";
        String timestamp = "1785240001";
        String nonce = "corrupt-nonce";
        String messageSignature = SHA1.gen(TOKEN, timestamp, nonce, corrupt);
        EncryptedRequest encrypted = new EncryptedRequest(
                encryptedEnvelope(APP_ID, ORIGINAL_ID, corrupt),
                messageSignature,
                timestamp,
                nonce);

        mockMvc.perform(encryptedPost(APP_ID, encrypted))
                .andExpect(status().isForbidden());

        assertThat(repository.calls).isZero();
    }

    private static MockHttpServletRequestBuilder plainPost(
            String appId, String body, String requestSignature) {
        return post("/wechat/callback/" + appId)
                .queryParam("signature", requestSignature)
                .queryParam("timestamp", TIMESTAMP)
                .queryParam("nonce", NONCE)
                .contentType(MediaType.APPLICATION_XML)
                .content(body);
    }

    private static MockHttpServletRequestBuilder encryptedPost(
            String appId, EncryptedRequest encrypted) {
        return post("/wechat/callback/" + appId)
                .queryParam("encrypt_type", "aes")
                .queryParam("msg_signature", encrypted.signature())
                .queryParam("timestamp", encrypted.timestamp())
                .queryParam("nonce", encrypted.nonce())
                .contentType(MediaType.APPLICATION_XML)
                .content(encrypted.body());
    }

    private static String signature() {
        return SHA1.gen(TOKEN, TIMESTAMP, NONCE);
    }

    private static EncryptedRequest encrypt(
            String plaintext, String appId, String outerOriginalId) {
        return encrypt(plaintext, appId, outerOriginalId, true);
    }

    private static EncryptedRequest encryptWithoutEnvelopeAppId(
            String plaintext, String cryptographicAppId, String outerOriginalId) {
        return encrypt(plaintext, cryptographicAppId, outerOriginalId, false);
    }

    private static EncryptedRequest encrypt(
            String plaintext,
            String cryptographicAppId,
            String outerOriginalId,
            boolean includeEnvelopeAppId) {
        WxMpDefaultConfigImpl config = new WxMpDefaultConfigImpl();
        config.setAppId(cryptographicAppId);
        config.setToken(TOKEN);
        config.setAesKey(AES_KEY);
        WxCryptUtil.EncryptContext context =
                new WxMpCryptUtil(config).encryptContext(plaintext);
        return new EncryptedRequest(
                includeEnvelopeAppId
                        ? encryptedEnvelope(
                                cryptographicAppId, outerOriginalId, context.getEncrypt())
                        : encryptedEnvelopeWithoutAppId(
                                outerOriginalId, context.getEncrypt()),
                context.getSignature(),
                context.getTimeStamp(),
                context.getNonce());
    }

    private static String encryptedEnvelope(
            String appId, String originalId, String ciphertext) {
        return """
                <xml>
                  <ToUserName><![CDATA[%s]]></ToUserName>
                  <AppId>%s</AppId>
                  <Encrypt><![CDATA[%s]]></Encrypt>
                </xml>
                """.formatted(originalId, appId, ciphertext);
    }

    private static String encryptedEnvelopeWithoutAppId(
            String originalId, String ciphertext) {
        return """
                <xml>
                  <ToUserName><![CDATA[%s]]></ToUserName>
                  <Encrypt><![CDATA[%s]]></Encrypt>
                </xml>
                """.formatted(originalId, ciphertext);
    }

    private static String fixture(String name) throws IOException {
        try (InputStream stream = WechatCallbackControllerTest.class
                .getResourceAsStream("/wechat/" + name)) {
            if (stream == null) {
                throw new IOException("Missing fixture " + name);
            }
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private record EncryptedRequest(
            String body, String signature, String timestamp, String nonce) {}

    private static final class CapturingRepository extends WechatEventRepository {

        private WechatEventRepository.InsertResult result =
                WechatEventRepository.InsertResult.INSERTED;
        private final List<WechatEvent> events = new ArrayList<>();
        private WechatEvent event;
        private int calls;
        private RuntimeException failure;

        private CapturingRepository() {
            super(null);
        }

        @Override
        public WechatEventRepository.InsertResult insert(WechatEvent event) {
            calls++;
            if (failure != null) {
                throw failure;
            }
            this.event = event;
            events.add(event);
            return result;
        }
    }
}
