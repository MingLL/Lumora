package cn.minglli.lumora.operations;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

class RateLimitInterceptorTest {

    private static final long WINDOW_MILLIS = 60_000;

    private final MutableClock clock = new MutableClock(Instant.parse("2026-08-14T10:00:00Z"));

    @Test
    void allowsUpToLimitThenRejectsWithinSameWindow() {
        RateLimitInterceptor interceptor = new RateLimitInterceptor(3, WINDOW_MILLIS, clock);

        assertThat(call(interceptor, "1.1.1.1")).isTrue();
        assertThat(call(interceptor, "1.1.1.1")).isTrue();
        assertThat(call(interceptor, "1.1.1.1")).isTrue();

        MockHttpServletResponse response = new MockHttpServletResponse();
        assertThat(interceptor.preHandle(requestFrom("1.1.1.1"), response, new Object())).isFalse();
        assertThat(response.getStatus()).isEqualTo(429);
    }

    @Test
    void countsEachClientIpSeparately() {
        RateLimitInterceptor interceptor = new RateLimitInterceptor(1, WINDOW_MILLIS, clock);

        assertThat(call(interceptor, "1.1.1.1")).isTrue();
        assertThat(call(interceptor, "2.2.2.2")).isTrue();
        assertThat(call(interceptor, "1.1.1.1")).isFalse();
    }

    @Test
    void startsFreshWindowAfterExpiry() {
        RateLimitInterceptor interceptor = new RateLimitInterceptor(1, WINDOW_MILLIS, clock);

        assertThat(call(interceptor, "1.1.1.1")).isTrue();
        assertThat(call(interceptor, "1.1.1.1")).isFalse();

        clock.advanceMillis(WINDOW_MILLIS);
        assertThat(call(interceptor, "1.1.1.1")).isTrue();
    }

    @Test
    void evictsCountersForClientsThatNeverComeBack() {
        RateLimitInterceptor interceptor = new RateLimitInterceptor(10, WINDOW_MILLIS, clock);

        // 模拟一次扫描：大量一次性 IP，之后再也不出现。
        for (int i = 0; i < 500; i++) {
            call(interceptor, "10.0." + (i / 256) + "." + (i % 256));
        }
        assertThat(interceptor.trackedClients()).isEqualTo(500);

        // 过了一个窗口之后的第一个请求负责清扫，一次性 IP 不该继续占位。
        clock.advanceMillis(WINDOW_MILLIS);
        call(interceptor, "1.1.1.1");

        assertThat(interceptor.trackedClients()).isEqualTo(1);
    }

    @Test
    void sweepDropsStaleCountersButKeepsActiveOnes() {
        RateLimitInterceptor interceptor = new RateLimitInterceptor(2, WINDOW_MILLIS, clock);

        call(interceptor, "1.1.1.1");
        clock.advanceMillis(WINDOW_MILLIS - 1_000);
        assertThat(call(interceptor, "2.2.2.2")).isTrue();
        assertThat(interceptor.trackedClients()).isEqualTo(2);

        // 这次请求触发清扫：1.1.1.1 的窗口刚好到期，2.2.2.2 的还差 59 秒。
        clock.advanceMillis(1_000);
        assertThat(call(interceptor, "2.2.2.2")).isTrue();
        assertThat(interceptor.trackedClients()).isEqualTo(1);

        // 清扫不能顺手把 2.2.2.2 的计数清零，否则等于给了它一个新窗口。
        assertThat(call(interceptor, "2.2.2.2")).isFalse();
    }

    @Test
    void prefersForwardedHeaderOverRemoteAddress() {
        RateLimitInterceptor interceptor = new RateLimitInterceptor(1, WINDOW_MILLIS, clock);

        MockHttpServletRequest first = requestFrom("10.0.0.1");
        first.addHeader("X-Forwarded-For", "203.0.113.7, 10.0.0.1");
        assertThat(interceptor.preHandle(first, new MockHttpServletResponse(), new Object())).isTrue();

        MockHttpServletRequest second = requestFrom("10.0.0.2");
        second.addHeader("X-Forwarded-For", "203.0.113.7, 10.0.0.2");
        assertThat(interceptor.preHandle(second, new MockHttpServletResponse(), new Object())).isFalse();
    }

    private boolean call(RateLimitInterceptor interceptor, String clientIp) {
        return interceptor.preHandle(requestFrom(clientIp), new MockHttpServletResponse(), new Object());
    }

    private MockHttpServletRequest requestFrom(String clientIp) {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/client-events");
        request.setRemoteAddr(clientIp);
        return request;
    }

    private static final class MutableClock extends Clock {
        private Instant now;

        private MutableClock(Instant now) {
            this.now = now;
        }

        private void advanceMillis(long millis) {
            now = now.plusMillis(millis);
        }

        @Override
        public ZoneOffset getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(java.time.ZoneId zone) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Instant instant() {
            return now;
        }
    }
}
