package cn.minglli.lumora.wechat;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

@Service
public class WechatJsapiSignatureService {

    private static final Logger log = LoggerFactory.getLogger(WechatJsapiSignatureService.class);

    private static final String TOKEN_URL =
            "https://api.weixin.qq.com/cgi-bin/token?grant_type=client_credential&appid=%s&secret=%s";
    private static final String TICKET_URL =
            "https://api.weixin.qq.com/cgi-bin/ticket/getticket?access_token=%s&type=jsapi";

    private final String appId;
    private final String appSecret;
    private final RestTemplate restTemplate;

    private volatile String accessToken;
    private volatile long accessTokenExpiresAt;

    private volatile String jsapiTicket;
    private volatile long jsapiTicketExpiresAt;

    public WechatJsapiSignatureService(
            cn.minglli.lumora.config.LumoraProperties properties,
            RestTemplateBuilder restTemplateBuilder) {
        this.appId = properties.getWechatAppId();
        this.appSecret = properties.getWechatAppSecret();
        this.restTemplate = restTemplateBuilder.build();
    }

    public Map<String, String> generateSignature(String url) {
        String ticket = getJsapiTicket();
        String nonceStr = UUID.randomUUID().toString().replace("-", "");
        String timestamp = String.valueOf(Instant.now().getEpochSecond());

        String raw = "jsapi_ticket=" + ticket
                + "&noncestr=" + nonceStr
                + "&timestamp=" + timestamp
                + "&url=" + url;

        String signature = sha1(raw);

        return Map.of(
                "appId", appId,
                "timestamp", timestamp,
                "nonceStr", nonceStr,
                "signature", signature);
    }

    private synchronized String getJsapiTicket() {
        if (jsapiTicket != null && Instant.now().getEpochSecond() < jsapiTicketExpiresAt) {
            return jsapiTicket;
        }
        String token = getAccessToken();
        ResponseEntity<Map> response = restTemplate.getForEntity(
                String.format(TICKET_URL, token), Map.class);
        Map<String, Object> body = response.getBody();
        if (body == null || !"ok".equals(body.get("errmsg"))) {
            log.error("Failed to get jsapi_ticket: {}", body);
            throw new RuntimeException("Failed to get jsapi_ticket from WeChat");
        }
        jsapiTicket = (String) body.get("ticket");
        int expiresIn = ((Number) body.get("expires_in")).intValue();
        jsapiTicketExpiresAt = Instant.now().getEpochSecond() + expiresIn - 60;
        return jsapiTicket;
    }

    private synchronized String getAccessToken() {
        if (accessToken != null && Instant.now().getEpochSecond() < accessTokenExpiresAt) {
            return accessToken;
        }
        ResponseEntity<Map> response = restTemplate.getForEntity(
                String.format(TOKEN_URL, appId, appSecret), Map.class);
        Map<String, Object> body = response.getBody();
        if (body == null || body.get("access_token") == null) {
            log.error("Failed to get access_token: {}", body);
            throw new RuntimeException("Failed to get access_token from WeChat");
        }
        accessToken = (String) body.get("access_token");
        int expiresIn = ((Number) body.get("expires_in")).intValue();
        accessTokenExpiresAt = Instant.now().getEpochSecond() + expiresIn - 60;
        return accessToken;
    }

    private static String sha1(String input) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-1");
            byte[] hash = digest.digest(input.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder();
            for (byte b : hash) {
                hex.append(String.format("%02x", b & 0xff));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException("SHA-1 not available", e);
        }
    }
}