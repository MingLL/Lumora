package cn.minglli.lumora.operations;

import java.time.Clock;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

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
    private final Clock clock;

    private final Map<String, WindowCounter> counters = new ConcurrentHashMap<>();

    // counters 里的条目只在同一个 IP 再次到访时才会被覆盖，从没到访过第二次的
    // IP（扫描器、爬虫、换出口的移动网络）会永久占位。每个窗口清一次过期条目，
    // 把这张表的规模压回「最近一两个窗口内出现过的 IP」。
    private final AtomicLong nextSweepAt = new AtomicLong(Long.MIN_VALUE);

    public RateLimitInterceptor() {
        this(60, 60_000);
    }

    public RateLimitInterceptor(int maxRequests, long windowMillis) {
        this(maxRequests, windowMillis, Clock.systemUTC());
    }

    RateLimitInterceptor(int maxRequests, long windowMillis, Clock clock) {
        this.maxRequests = maxRequests;
        this.windowMillis = windowMillis;
        this.clock = clock;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        String clientIp = extractClientIp(request);
        long now = clock.millis();

        evictExpired(now);

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

    private void evictExpired(long now) {
        long scheduled = nextSweepAt.get();
        // CAS 保证并发请求里只有一个真正去扫，其余直接返回。
        if (now < scheduled || !nextSweepAt.compareAndSet(scheduled, now + windowMillis)) {
            return;
        }
        // ConcurrentHashMap 的 entrySet().removeIf 逐条在桶锁下判断并删除，
        // 不会和上面的 compute 抢同一条记录。
        counters.entrySet().removeIf(entry -> now - entry.getValue().windowStart() >= windowMillis);
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

    int trackedClients() {
        return counters.size();
    }

    private record WindowCounter(long windowStart, int count) {
    }
}
