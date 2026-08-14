package cn.minglli.lumora.event;

import java.util.Map;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class ClientEventController {

    private static final Logger log = LoggerFactory.getLogger(ClientEventController.class);
    private final ClientEventMapper mapper;
    private final ObjectMapper objectMapper;

    public ClientEventController(ClientEventMapper mapper, ObjectMapper objectMapper) {
        this.mapper = mapper;
        this.objectMapper = objectMapper;
    }

    @PostMapping("/client-events")
    public ResponseEntity<Void> report(@Valid @RequestBody ClientEventReport report) {
        try {
            mapper.insert(new ClientEventRecord(null, report.visitId(), report.type(), report.url(),
                    objectMapper.writeValueAsString(report.properties()), null, null));
        } catch (RuntimeException | JsonProcessingException exception) {
            // 客户端观测事件不能影响页面可用性。
            log.warn("Failed to persist client event type={} errorClass={}",
                    report.type(), exception.getClass().getSimpleName());
        }
        return ResponseEntity.noContent().build();
    }

    public record ClientEventReport(
            @NotBlank @Size(max = 64) String visitId,
            @NotBlank @Size(max = 64) String type,
            @NotBlank @Size(max = 2048) String url,
            @NotNull Map<String, Object> properties) {
    }
}
