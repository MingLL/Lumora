package cn.minglli.lumora.event;

import java.util.Map;

import cn.minglli.lumora.operations.LogSanitizer;
import cn.minglli.lumora.operations.SiteUrlValidator;
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

    // properties 是开放字段，没有上限的话任意大小的 JSON 都会被写进 JSONB 列。
    // 真正挡住超大请求体的是入口层的 buffering 中间件（见
    // deploy/k8s/lumora-ingress.yaml），这里是应用层兜底，也顺带划出「一个事件
    // 的属性该有多大」的边界。当前最大的事件属性不到 100 字节。
    private static final int MAX_PROPERTIES_JSON_LENGTH = 2048;

    private final ClientEventMapper mapper;
    private final ObjectMapper objectMapper;
    private final SiteUrlValidator siteUrlValidator;

    public ClientEventController(ClientEventMapper mapper, ObjectMapper objectMapper,
            SiteUrlValidator siteUrlValidator) {
        this.mapper = mapper;
        this.objectMapper = objectMapper;
        this.siteUrlValidator = siteUrlValidator;
    }

    @PostMapping("/client-events")
    public ResponseEntity<Void> report(@Valid @RequestBody ClientEventReport report) {
        // 站外 URL 的事件只会污染访问分析，不收。
        if (!siteUrlValidator.isSiteUrl(report.url())) {
            log.warn("Rejected client event with off-site url={}", LogSanitizer.forLog(report.url()));
            return ResponseEntity.badRequest().build();
        }

        String violation = ClientEventContract.violation(report.type(), report.properties());
        if (violation != null) {
            log.warn("Rejected client event violating contract type={} reason={}",
                    LogSanitizer.forLog(report.type()), violation);
            return ResponseEntity.badRequest().build();
        }

        String propertiesJson;
        try {
            propertiesJson = objectMapper.writeValueAsString(report.properties());
        } catch (JsonProcessingException exception) {
            // 序列化不了说明请求本身就不合法，和写库失败不是一回事，直接 400。
            return ResponseEntity.badRequest().build();
        }
        // 走到这里 properties 已经过了契约校验，长度不该超标。留着这道兜底，是为了
        // 将来往契约里加不定长属性时，不至于悄悄把超大值写进库。
        if (propertiesJson.length() > MAX_PROPERTIES_JSON_LENGTH) {
            log.warn("Rejected oversized client event type={} propertiesLength={}",
                    LogSanitizer.forLog(report.type()), propertiesJson.length());
            return ResponseEntity.badRequest().build();
        }

        try {
            mapper.insert(new ClientEventRecord(null, report.visitId(), report.type(), report.url(),
                    propertiesJson, null, null));
        } catch (RuntimeException exception) {
            // 客户端观测事件不能影响页面可用性。
            log.warn("Failed to persist client event type={} errorClass={}",
                    LogSanitizer.forLog(report.type()), exception.getClass().getSimpleName());
        }
        return ResponseEntity.noContent().build();
    }

    public record ClientEventReport(
            @NotBlank @Size(max = 64) String visitId,
            @NotBlank @Size(max = 64) String type,
            @NotBlank @Size(max = SiteUrlValidator.MAX_URL_LENGTH) String url,
            @NotNull Map<String, Object> properties) {
    }
}
