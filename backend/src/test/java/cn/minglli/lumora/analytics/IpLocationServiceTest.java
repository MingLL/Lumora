package cn.minglli.lumora.analytics;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import java.util.List;
import java.util.Map;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class IpLocationServiceTest {
    @Test void doesNotSendPrivateIpsOrHostnamesToProvider() {
        var jdbc = mock(JdbcTemplate.class);
        var service = new IpLocationService(new ObjectMapper(), jdbc, "");
        for (String ip : List.of("127.0.0.1", "10.1.2.3", "::1", "fd00::1", "169.254.1.1")) {
            assertThat(service.lookup(ip).get("status")).isEqualTo("private");
        }
        assertThat(service.lookup("example.com").get("status")).isEqualTo("unavailable");
        verify(jdbc, never()).update(anyString(), any(Object[].class));
    }
    @Test void usesPersistentCoordinatesWithoutCallingProvider() {
        var jdbc = mock(JdbcTemplate.class);
        when(jdbc.queryForList(anyString(), eq("8.8.8.8"))).thenReturn(List.of(Map.of("payload",
                "{\"location\":\"Mountain View\",\"status\":\"ok\",\"latitude\":\"37.38\",\"longitude\":\"-122.08\"}")));
        var result = new IpLocationService(new ObjectMapper(), jdbc, "").lookup("8.8.8.8");
        assertThat(result.get("latitude")).isEqualTo("37.38");
        assertThat(result.get("longitude")).isEqualTo("-122.08");
        verify(jdbc, never()).update(anyString(), any(Object[].class));
    }
}
