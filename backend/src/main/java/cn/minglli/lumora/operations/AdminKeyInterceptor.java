package cn.minglli.lumora.operations;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import cn.minglli.lumora.config.LumoraProperties;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

@Component
public class AdminKeyInterceptor implements HandlerInterceptor {

    private static final Logger log = LoggerFactory.getLogger(AdminKeyInterceptor.class);

    private static final long MAX_TIMESTAMP_DRIFT_SECONDS = 300;
    private static final long NONCE_TTL_SECONDS = 600;

    static final int MAX_NONCES = 4096;
    private static final int MAX_NONCE_LENGTH = 128;

    private final Clock clock;
    private final byte[] expectedKey;
    private final byte[] hmacKey;

    private final Map<String, Long> nonceStore = new HashMap<>();

    public AdminKeyInterceptor(LumoraProperties properties) {
        this(properties, Clock.systemUTC());
    }

    @Autowired
    public AdminKeyInterceptor(LumoraProperties properties, Clock clock) {
        this.clock = clock;
        this.expectedKey = properties.getReportAdminKey().getBytes(StandardCharsets.UTF_8);
        this.hmacKey = properties.getReportAdminKey().getBytes(StandardCharsets.UTF_8);
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler)
            throws Exception {
        if (request.getRequestURI() != null && request.getRequestURI().startsWith("/api/analytics/")) {
            response.setHeader("Cache-Control", "no-store");
        }
        if (expectedKey.length == 0) {
            response.sendError(HttpStatus.UNAUTHORIZED.value());
            return false;
        }
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

        if (timestampStr == null || timestampStr.isBlank() || nonce == null || nonce.isBlank()
                || nonce.length() > MAX_NONCE_LENGTH) {
            log.warn("Rejected HMAC request with missing timestamp or invalid nonce path={} requestId={}",
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

        long now = clock.instant().getEpochSecond();
        if (timestamp < now - MAX_TIMESTAMP_DRIFT_SECONDS
                || timestamp > now + MAX_TIMESTAMP_DRIFT_SECONDS) {
            log.warn("Rejected HMAC request with drifted timestamp path={} requestId={} timestamp={}",
                    request.getRequestURI(), requestId, timestamp);
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

        // Only authenticated requests may occupy cache space. Check and insert under
        // one lock so concurrent copies of the same signed request cannot both pass.
        int rejection = registerNonce(nonce, now);
        if (rejection != 0) {
            response.sendError(rejection);
            return false;
        }
        return true;
    }

    private synchronized int registerNonce(String nonce, long now) {
        cleanupExpiredNonces();
        if (nonceStore.containsKey(nonce)) {
            return HttpStatus.UNAUTHORIZED.value();
        }
        if (nonceStore.size() >= MAX_NONCES) {
            // Never evict a live nonce to make room: that would allow replay.
            return HttpStatus.SERVICE_UNAVAILABLE.value();
        }
        nonceStore.put(nonce, now + NONCE_TTL_SECONDS);
        return 0;
    }

    // Run independently of successful HMAC authentication (including when the
    // dashboard uses only the plain admin key). Size remains bounded between sweeps.
    @Scheduled(fixedDelay = 60_000)
    synchronized void cleanupExpiredNonces() {
        long now = clock.instant().getEpochSecond();
        // Keep the boundary second: a timestamp 300s in the future remains valid
        // exactly 600s after its first use.
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