package cn.minglli.lumora.operations;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

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

    private final byte[] expectedKey;

    public AdminKeyInterceptor(LumoraProperties properties) {
        this.expectedKey = properties.getReportAdminKey().getBytes(StandardCharsets.UTF_8);
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {
        String requestId = request.getHeader("X-Request-Id");
        if (requestId == null || requestId.isBlank()) {
            log.warn("Rejected internal request without X-Request-Id path={}", request.getRequestURI());
            response.sendError(HttpStatus.BAD_REQUEST.value(), "X-Request-Id is required");
            return false;
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

    private boolean constantTimeEquals(String candidate) {
        byte[] provided = candidate.getBytes(StandardCharsets.UTF_8);
        if (provided.length != expectedKey.length) {
            MessageDigest.isEqual(provided, provided);
            return false;
        }
        return MessageDigest.isEqual(provided, expectedKey);
    }
}
