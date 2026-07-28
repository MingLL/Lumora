package cn.minglli.lumora.wechat;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.DateTimeException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import cn.minglli.lumora.config.LumoraProperties;
import me.chanjar.weixin.common.util.crypto.SHA1;
import me.chanjar.weixin.mp.config.impl.WxMpDefaultConfigImpl;
import me.chanjar.weixin.mp.util.crypto.WxMpCryptUtil;
import org.springframework.stereotype.Service;

@Service
public final class WechatProtocolService {

    private final String appId;
    private final String originalId;
    private final String token;
    private final SafeXmlParser xmlParser;
    private final WxMpCryptUtil cryptUtil;

    public WechatProtocolService(LumoraProperties properties, SafeXmlParser xmlParser) {
        this.appId = properties.getWechatAppId();
        this.originalId = properties.getWechatOriginalId();
        this.token = properties.getWechatToken();
        this.xmlParser = xmlParser;

        WxMpDefaultConfigImpl config = new WxMpDefaultConfigImpl();
        config.setAppId(appId);
        config.setToken(token);
        config.setAesKey(properties.getWechatAesKey());
        this.cryptUtil = new WxMpCryptUtil(config);
    }

    public void validateAppId(String routeAppId) {
        if (!appId.equals(routeAppId)) {
            throw WechatCallbackException.notFound();
        }
    }

    public String verifyUrl(
            String signature, String timestamp, String nonce, String echoString) {
        verifySignature(signature, timestamp, nonce);
        return echoString == null ? "" : echoString;
    }

    public WechatInboundMessage parsePlaintext(
            byte[] body, String signature, String timestamp, String nonce) {
        verifySignature(signature, timestamp, nonce);
        SafeXmlParser.ParsedXml xml = parseMessageXml(body);
        requireRecipient(xml.text("ToUserName"));
        return toInboundMessage(xml);
    }

    public WechatInboundMessage parseEncrypted(
            byte[] body, String messageSignature, String timestamp, String nonce) {
        SafeXmlParser.ParsedXml envelope = parseMessageXml(body);
        String ciphertext = requiredText(envelope, "Encrypt");
        if (!matchesSignature(messageSignature, token, timestamp, nonce, ciphertext)) {
            throw WechatCallbackException.forbidden();
        }
        validateAvailableIdentity(envelope.text("AppId"), appId);
        validateAvailableIdentity(envelope.text("ToUserName"), originalId);

        String plaintext;
        try {
            plaintext = cryptUtil.decrypt(ciphertext);
        } catch (RuntimeException exception) {
            throw WechatCallbackException.forbidden(exception);
        }

        SafeXmlParser.ParsedXml decrypted = parseMessageXml(
                plaintext.getBytes(StandardCharsets.UTF_8));
        validateAvailableIdentity(decrypted.text("AppId"), appId);
        requireRecipient(decrypted.text("ToUserName"));
        return toInboundMessage(decrypted);
    }

    private void verifySignature(String signature, String timestamp, String nonce) {
        if (!matchesSignature(signature, token, timestamp, nonce)) {
            throw WechatCallbackException.forbidden();
        }
    }

    private static boolean matchesSignature(String actual, String... parts) {
        if (actual == null) {
            return false;
        }
        for (String part : parts) {
            if (part == null) {
                return false;
            }
        }
        byte[] expected = SHA1.gen(parts).getBytes(StandardCharsets.US_ASCII);
        return MessageDigest.isEqual(expected, actual.getBytes(StandardCharsets.US_ASCII));
    }

    private SafeXmlParser.ParsedXml parseMessageXml(byte[] body) {
        SafeXmlParser.ParsedXml xml = xmlParser.parse(body);
        if (!"xml".equals(xml.rootName())) {
            throw new WechatMalformedXmlException(
                    "Unexpected WeChat XML root", new IllegalArgumentException("root"));
        }
        return xml;
    }

    private void requireRecipient(String recipient) {
        if (!originalId.equals(recipient)) {
            throw WechatCallbackException.forbidden();
        }
    }

    private static void validateAvailableIdentity(String actual, String expected) {
        if (actual != null && !actual.isBlank() && !expected.equals(actual)) {
            throw WechatCallbackException.forbidden();
        }
    }

    private WechatInboundMessage toInboundMessage(SafeXmlParser.ParsedXml xml) {
        try {
            long createTime = requiredLong(xml, "CreateTime");
            Instant.ofEpochSecond(createTime);
            BigDecimal latitude = optionalDecimal(xml.text("Latitude"));
            BigDecimal longitude = optionalDecimal(xml.text("Longitude"));
            BigDecimal precision = optionalDecimal(xml.text("Precision"));
            validateCoordinates(latitude, longitude, precision);
            return WechatInboundMessage.builder()
                    .appId(appId)
                    .openId(requiredText(xml, "FromUserName"))
                    .msgType(requiredText(xml, "MsgType"))
                    .event(xml.text("Event"))
                    .createTimeEpochSeconds(createTime)
                    .msgId(optionalLong(xml.text("MsgId")))
                    .eventKey(xml.text("EventKey"))
                    .ticket(xml.text("Ticket"))
                    .latitude(latitude)
                    .longitude(longitude)
                    .locationPrecision(precision)
                    .composite(composite(xml))
                    .payload(xml.fields())
                    .build();
        } catch (NumberFormatException | DateTimeException exception) {
            throw new WechatMalformedXmlException("Invalid WeChat numeric field", exception);
        }
    }

    private static void validateCoordinates(
            BigDecimal latitude, BigDecimal longitude, BigDecimal precision) {
        try {
            EventDeduplicationKey.canonicalLatitude(latitude);
            EventDeduplicationKey.canonicalLongitude(longitude);
            EventDeduplicationKey.canonicalPrecision(precision);
        } catch (IllegalArgumentException exception) {
            throw new WechatInvalidPayloadException(
                    "Invalid WeChat coordinate field", exception);
        }
    }

    private static WechatInboundMessage.CompositePayload composite(
            SafeXmlParser.ParsedXml xml) {
        Map<String, Object> scanCode = xml.object("ScanCodeInfo");
        if (!scanCode.isEmpty()) {
            return new WechatInboundMessage.CompositePayload(
                    "ScanCodeInfo", List.of(scanCode));
        }

        Map<String, Object> sendPics = xml.object("SendPicsInfo");
        if (!sendPics.isEmpty()) {
            List<Map<String, Object>> items = pictureItems(sendPics);
            return new WechatInboundMessage.CompositePayload(
                    "SendPicsInfo", pictureCount(sendPics, items.size()), items);
        }

        Map<String, Object> sendLocation = xml.object("SendLocationInfo");
        if (!sendLocation.isEmpty()) {
            return new WechatInboundMessage.CompositePayload(
                    "SendLocationInfo", List.of(sendLocation));
        }
        return null;
    }

    private static List<Map<String, Object>> pictureItems(Map<String, Object> sendPics) {
        Object picListValue = sendPics.get("PicList");
        if (!(picListValue instanceof Map<?, ?> picList)) {
            return List.of();
        }
        Object itemValue = picList.get("item");
        List<Map<String, Object>> items = new ArrayList<>();
        if (itemValue instanceof Map<?, ?> item) {
            items.add(stringKeyMap(item));
        } else if (itemValue instanceof List<?> list) {
            for (Object value : list) {
                if (value instanceof Map<?, ?> item) {
                    items.add(stringKeyMap(item));
                }
            }
        }
        return List.copyOf(items);
    }

    private static int pictureCount(Map<String, Object> sendPics, int defaultCount) {
        Object count = sendPics.get("Count");
        if (!(count instanceof String text) || text.isBlank()) {
            return defaultCount;
        }
        int parsed = Integer.parseInt(text.trim());
        if (parsed < 0) {
            throw new WechatInvalidPayloadException(
                    "Invalid WeChat picture count",
                    new IllegalArgumentException("Count"));
        }
        return parsed;
    }

    private static Map<String, Object> stringKeyMap(Map<?, ?> source) {
        return source.entrySet().stream().collect(
                java.util.stream.Collectors.toUnmodifiableMap(
                        entry -> (String) entry.getKey(), Map.Entry::getValue));
    }

    private static String requiredText(SafeXmlParser.ParsedXml xml, String name) {
        String value = xml.text(name);
        if (value == null || value.isBlank()) {
            throw new WechatMalformedXmlException(
                    "Missing required WeChat field", new IllegalArgumentException(name));
        }
        return value;
    }

    private static long requiredLong(SafeXmlParser.ParsedXml xml, String name) {
        return Long.parseLong(requiredText(xml, name).trim());
    }

    private static Long optionalLong(String value) {
        return value == null || value.isBlank() ? null : Long.valueOf(value.trim());
    }

    private static BigDecimal optionalDecimal(String value) {
        return value == null || value.isBlank() ? null : new BigDecimal(value.trim());
    }
}
