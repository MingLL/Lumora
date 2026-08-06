package cn.minglli.lumora.operations;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

@Component
public class RateLimitInterceptor implements HandlerInterceptor {

    private static final Logger log = LoggerFactory.getLogger(RateLimitInterceptor.class);

    private final int maxRequests;
    private final long windowMillis;

    private final Map<String, WindowCounter> counters = new ConcurrentHashMap<>();

    public RateLimitInterceptor() {
        this(60, 60_000);
    }

    public RateLimitInterceptor(int maxRequests, long windowMillis) {
        this.maxRequests = maxRequests;
        this.windowMillis = windowMillis;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        String clientIp = extractClientIp(request);
        long now = System.currentTimeMillis();

        WindowCounter counter = counters.compute(clientIp, (ip, existing) -> {
            if (existing == null || now - existing.windowStart() >= windowMillis) {
                return new WindowCounter(now, 1);
            }
            return new WindowCounter(existing.windowStart(), existing.count() + 1);
        });

        if (counter.count() > maxRequests) {
            log.warn("Rate limit exceeded ip={} path={} count={}",
                    clientIp, request.getRequestURI(), counter.count());
            response.setStatus(HttpStatus.TOO_MANY_REQUESTS.value());
            response.setContentType("text/plain");
            return false;
        }
        return true;
    }

    private String extractClientIp(HttpServletRequest request) {
        String forwarded = request.getHeader("X-Forwarded-For");
        if (forwarded != null && !forwarded.isBlank()) {
            return forwarded.split(",")[0].trim();
        }
        String realIp = request.getHeader("X-Real-IP");
        if (realIp != null && !realIp.isBlank()) {
            return realIp.trim();
        }
        return request.getRemoteAddr();
    }

    private record WindowCounter(long windowStart, int count) {
    }
}