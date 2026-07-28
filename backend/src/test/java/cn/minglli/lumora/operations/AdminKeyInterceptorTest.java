package cn.minglli.lumora.operations;

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

    @BeforeEach
    void setUp() {
        LumoraProperties properties = new LumoraProperties();
        properties.setReportAdminKey(ADMIN_KEY);
        interceptor = new AdminKeyInterceptor(properties);
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

    private HttpServletRequest request(String adminKey, String requestId) {
        HttpServletRequest request = mock(HttpServletRequest.class);
        when(request.getHeader("X-Lumora-Admin-Key")).thenReturn(adminKey);
        when(request.getHeader("X-Request-Id")).thenReturn(requestId);
        return request;
    }
}
