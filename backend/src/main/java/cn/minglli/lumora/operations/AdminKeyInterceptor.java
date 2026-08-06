package cn.minglli.lumora.operations;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.Base64;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import cn.minglli.lumora.config.LumoraProperties;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

@Component
public class AdminKeyInterceptor implements HandlerInterceptor {

    private static final Logger log = LoggerFactory.getLogger(AdminKeyInterceptor.class);

    private static final long MAX_TIMESTAMP_DRIFT_SECONDS = 300;
    private static final long NONCE_TTL_SECONDS = 600;

    private final byte[] expectedKey;
    private final byte[] hmacKey;

    private final Map<String, Long> nonceStore = new ConcurrentHashMap<>();

    public AdminKeyInterceptor(LumoraProperties properties) {
        this.expectedKey = properties.getReportAdminKey().getBytes(StandardCharsets.UTF_8);
        this.hmacKey = properties.getReportAdminKey().getBytes(StandardCharsets.UTF_8);
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler)
            throws Exception {
        String requestId = request.getHeader("X-Request-Id");
        if (requestId == null || requestId.isBlank()) {
            log.warn("Rejected internal request without X-Request-Id path={}", request.getRequestURI());
            response.sendError(HttpStatus.BAD_REQUEST.value(), "X-Request-Id is required");
            return false;
        }

        String signature = request.getHeader("X-Lumora-Signature");

        if (signature != null && !signature.isBlank()) {
            return verifyHmac(request, response, requestId, signature);
        }

        String key = request.getHeader("X-Lumora-Admin-Key");
        if (key == null || !constantTimeEquals(key)) {
            log.warn("Rejected internal request with missing or invalid admin key "
                    + "path={} requestId={}", request.getRequestURI(), requestId);
            response.sendError(HttpStatus.UNAUTHORIZED.value());
            return false;
        }
        return true;
    }

    private boolean verifyHmac(HttpServletRequest request, HttpServletResponse response,
            String requestId, String signature) throws Exception {
        String timestampStr = request.getHeader("X-Lumora-Timestamp");
        String nonce = request.getHeader("X-Lumora-Nonce");

        if (timestampStr == null || timestampStr.isBlank() || nonce == null || nonce.isBlank()) {
            log.warn("Rejected HMAC request without timestamp or nonce path={} requestId={}",
                    request.getRequestURI(), requestId);
            response.sendError(HttpStatus.BAD_REQUEST.value());
            return false;
        }

        long timestamp;
        try {
            timestamp = Long.parseLong(timestampStr);
        } catch (NumberFormatException e) {
            log.warn("Rejected HMAC request with invalid timestamp path={} requestId={}",
                    request.getRequestURI(), requestId);
            response.sendError(HttpStatus.BAD_REQUEST.value());
            return false;
        }

        long now = Instant.now().getEpochSecond();
        if (Math.abs(now - timestamp) > MAX_TIMESTAMP_DRIFT_SECONDS) {
            log.warn("Rejected HMAC request with drifted timestamp path={} requestId={} drift={}",
                    request.getRequestURI(), requestId, Math.abs(now - timestamp));
            response.sendError(HttpStatus.UNAUTHORIZED.value());
            return false;
        }

        if (nonceStore.putIfAbsent(nonce, now + NONCE_TTL_SECONDS) != null) {
            log.warn("Rejected HMAC request with replayed nonce path={} requestId={}",
                    request.getRequestURI(), requestId);
            response.sendError(HttpStatus.UNAUTHORIZED.value());
            return false;
        }

        String message = nonce + timestampStr + requestId + request.getMethod() + request.getRequestURI();
        String expected = computeHmac(hmacKey, message);

        if (!MessageDigest.isEqual(expected.getBytes(StandardCharsets.UTF_8),
                signature.getBytes(StandardCharsets.UTF_8))) {
            log.warn("Rejected HMAC request with invalid signature path={} requestId={}",
                    request.getRequestURI(), requestId);
            response.sendError(HttpStatus.UNAUTHORIZED.value());
            return false;
        }

        cleanupExpiredNonces(now);
        return true;
    }

    private void cleanupExpiredNonces(long now) {
        nonceStore.entrySet().removeIf(entry -> entry.getValue() < now);
    }

    private boolean constantTimeEquals(String candidate) {
        byte[] provided = candidate.getBytes(StandardCharsets.UTF_8);
        if (provided.length != expectedKey.length) {
            MessageDigest.isEqual(provided, provided);
            return false;
        }
        return MessageDigest.isEqual(provided, expectedKey);
    }

    private static String computeHmac(byte[] key, String message) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key, "HmacSHA256"));
            byte[] result = mac.doFinal(message.getBytes(StandardCharsets.UTF_8));
            return Base64.getEncoder().encodeToString(result);
        } catch (NoSuchAlgorithmException | java.security.InvalidKeyException e) {
            throw new RuntimeException("HMAC-SHA256 not available", e);
        }
    }
}