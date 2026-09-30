package cn.minglli.lumora.operations;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.Clock;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.ArrayList;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.springframework.test.util.ReflectionTestUtils;
import java.util.Base64;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import cn.minglli.lumora.config.LumoraProperties;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AdminKeyInterceptorTest {

    private static final String ADMIN_KEY = "super-secret-admin-key";

    private AdminKeyInterceptor interceptor;
    private final MutableClock clock = new MutableClock();

    @BeforeEach
    void setUp() {
        LumoraProperties properties = new LumoraProperties();
        properties.setReportAdminKey(ADMIN_KEY);
        interceptor = new AdminKeyInterceptor(properties, clock);
    }

    @Test
    void allowsRequestWithMatchingKeyAndRequestId() throws Exception {
        HttpServletRequest request = request(ADMIN_KEY, "req-1");
        HttpServletResponse response = mock(HttpServletResponse.class);

        assertThat(interceptor.preHandle(request, response, new Object())).isTrue();
    }

    @Test
    void rejectsRequestWithMismatchedKey() throws Exception {
        HttpServletRequest request = request("wrong-key", "req-1");
        HttpServletResponse response = mock(HttpServletResponse.class);

        assertThat(interceptor.preHandle(request, response, new Object())).isFalse();
        verify(response).sendError(401);
    }

    @Test
    void rejectsRequestWithoutRequestId() throws Exception {
        HttpServletRequest request = request(ADMIN_KEY, null);
        HttpServletResponse response = mock(HttpServletResponse.class);

        assertThat(interceptor.preHandle(request, response, new Object())).isFalse();
        verify(response).sendError(400, "X-Request-Id is required");
    }

    @Test
    void rejectsRequestWithBlankRequestId() throws Exception {
        HttpServletRequest request = request(ADMIN_KEY, "  ");
        HttpServletResponse response = mock(HttpServletResponse.class);

        assertThat(interceptor.preHandle(request, response, new Object())).isFalse();
        verify(response).sendError(400, "X-Request-Id is required");
    }

    @Test
    void invalidSignatureDoesNotConsumeNonce() throws Exception {
        var request = signedRequest("nonce-one", clock.instant().getEpochSecond());
        String validSignature = request.getHeader("X-Lumora-Signature");
        request.removeHeader("X-Lumora-Signature");
        request.addHeader("X-Lumora-Signature", "invalid");
        var rejected = new MockHttpServletResponse();
        assertThat(interceptor.preHandle(request, rejected, new Object())).isFalse();
        assertThat(rejected.getStatus()).isEqualTo(401);
        request.removeHeader("X-Lumora-Signature");
        request.addHeader("X-Lumora-Signature", validSignature);
        assertThat(interceptor.preHandle(request, new MockHttpServletResponse(), new Object())).isTrue();
    }

    @Test
    void rejectsReplayOfValidSignature() throws Exception {
        var request = signedRequest("nonce-one", clock.instant().getEpochSecond());
        assertThat(interceptor.preHandle(request, new MockHttpServletResponse(), new Object())).isTrue();
        var replay = new MockHttpServletResponse();
        assertThat(interceptor.preHandle(request, replay, new Object())).isFalse();
        assertThat(replay.getStatus()).isEqualTo(401);
    }

    @Test
    void invalidSignaturesNeverAccumulateInCache() throws Exception {
        for (int n = 0; n < 20; n++) {
            var request = signedRequest("bad-" + n, clock.instant().getEpochSecond());
            request.removeHeader("X-Lumora-Signature");
            request.addHeader("X-Lumora-Signature", "invalid");
            assertThat(interceptor.preHandle(request, new MockHttpServletResponse(), new Object())).isFalse();
        }
        assertThat(nonceCount()).isZero();
    }

    @Test
    void cleanupDoesNotRequireAnotherAuthenticatedRequest() throws Exception {
        var request = signedRequest("expires", clock.instant().getEpochSecond());
        assertThat(interceptor.preHandle(request, new MockHttpServletResponse(), new Object())).isTrue();
        clock.now = clock.now.plusSeconds(601);
        interceptor.cleanupExpiredNonces();
        assertThat(nonceCount()).isZero();
        assertThat(interceptor.preHandle(request, new MockHttpServletResponse(), new Object())).isFalse();
    }

    @Test
    void retainsNonceThroughLastValidTimestampSecond() throws Exception {
        var request = signedRequest("future", clock.instant().getEpochSecond() + 300);
        assertThat(interceptor.preHandle(request, new MockHttpServletResponse(), new Object())).isTrue();
        clock.now = clock.now.plusSeconds(600);
        interceptor.cleanupExpiredNonces();
        assertThat(interceptor.preHandle(request, new MockHttpServletResponse(), new Object())).isFalse();
        assertThat(nonceCount()).isEqualTo(1);
    }

    @Test
    void boundsCapacityWithoutEvictingLiveReplayProtection() throws Exception {
        long now = clock.instant().getEpochSecond();
        for (int n = 0; n < AdminKeyInterceptor.MAX_NONCES; n++) {
            assertThat(interceptor.preHandle(signedRequest("nonce-" + n, now),
                    new MockHttpServletResponse(), new Object())).isTrue();
        }
        var full = new MockHttpServletResponse();
        assertThat(interceptor.preHandle(signedRequest("overflow", now), full, new Object())).isFalse();
        assertThat(full.getStatus()).isEqualTo(503);
        assertThat(nonceCount()).isEqualTo(AdminKeyInterceptor.MAX_NONCES);
        var replay = new MockHttpServletResponse();
        assertThat(interceptor.preHandle(signedRequest("nonce-0", now), replay, new Object())).isFalse();
        assertThat(replay.getStatus()).isEqualTo(401);
        clock.now = clock.now.plusSeconds(601);
        assertThat(interceptor.preHandle(signedRequest("new", clock.instant().getEpochSecond()),
                new MockHttpServletResponse(), new Object())).isTrue();
        assertThat(nonceCount()).isEqualTo(1);
    }

    @Test
    void rejectsOversizedNonceAndOverflowingTimestamp() throws Exception {
        var longNonce = new MockHttpServletResponse();
        assertThat(interceptor.preHandle(signedRequest("x".repeat(129), clock.instant().getEpochSecond()),
                longNonce, new Object())).isFalse();
        assertThat(longNonce.getStatus()).isEqualTo(400);
        var overflow = new MockHttpServletResponse();
        assertThat(interceptor.preHandle(signedRequest("overflow", Long.MIN_VALUE + clock.instant().getEpochSecond()),
                overflow, new Object())).isFalse();
        assertThat(overflow.getStatus()).isEqualTo(401);
        assertThat(nonceCount()).isZero();
    }

    @Test
    void concurrentCopiesOfSignedRequestPassExactlyOnce() throws Exception {
        var pool = Executors.newFixedThreadPool(8);
        try {
            var start = new java.util.concurrent.CountDownLatch(1);
            var tasks = new ArrayList<Callable<Boolean>>();
            for (int n = 0; n < 32; n++) {
                tasks.add(() -> {
                    start.await();
                    return interceptor.preHandle(signedRequest("concurrent", clock.instant().getEpochSecond()),
                            new MockHttpServletResponse(), new Object());
                });
            }
            var futures = tasks.stream().map(pool::submit).toList();
            start.countDown();
            int accepted = 0;
            for (var future : futures) if (future.get(10, TimeUnit.SECONDS)) accepted++;
            assertThat(accepted).isEqualTo(1);
        } finally {
            pool.shutdownNow();
        }
    }

    private int nonceCount() {
        return ((Map<?, ?>) ReflectionTestUtils.getField(interceptor, "nonceStore")).size();
    }

    private static class MutableClock extends Clock {
        private Instant now = Instant.parse("2026-09-30T00:00:00Z");
        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return now; }
    }

    private MockHttpServletRequest signedRequest(String nonce, long timestamp) throws Exception {
        var request = new MockHttpServletRequest("GET", "/api/analytics/summary");
        request.addHeader("X-Request-Id", "test-request");
        request.addHeader("X-Lumora-Timestamp", Long.toString(timestamp));
        request.addHeader("X-Lumora-Nonce", nonce);
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(ADMIN_KEY.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        String message = nonce + timestamp + "test-requestGET/api/analytics/summary";
        request.addHeader("X-Lumora-Signature", Base64.getEncoder().encodeToString(
                mac.doFinal(message.getBytes(StandardCharsets.UTF_8))));
        return request;
    }

    private HttpServletRequest request(String adminKey, String requestId) {
        HttpServletRequest request = mock(HttpServletRequest.class);
        when(request.getHeader("X-Lumora-Admin-Key")).thenReturn(adminKey);
        when(request.getHeader("X-Request-Id")).thenReturn(requestId);
        return request;
    }
}
