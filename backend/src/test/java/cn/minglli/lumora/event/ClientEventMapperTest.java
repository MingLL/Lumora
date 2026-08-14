package cn.minglli.lumora.event;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;

import cn.minglli.lumora.support.PostgresContainerTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

class ClientEventMapperTest extends PostgresContainerTest {
    @Autowired private ClientEventMapper mapper;
    @Autowired private JdbcTemplate jdbcTemplate;

    @Test
    void storesVisitTypeAndExtensibleProperties() {
        mapper.insert(new ClientEventRecord(null, "visit-1", "NETWORK_TYPE",
                "https://lumora.love/", "{\"networkType\":\"wifi\"}", null, null));
        Map<String, Object> row = jdbcTemplate.queryForMap(
                "SELECT visit_id, type, properties FROM client_event WHERE visit_id = ?", "visit-1");
        assertThat(row.get("type")).isEqualTo("NETWORK_TYPE");
        assertThat(row.get("properties").toString()).contains("wifi");
    }
}
