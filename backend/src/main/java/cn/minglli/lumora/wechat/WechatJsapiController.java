package cn.minglli.lumora.wechat;

import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class WechatJsapiController {

    private static final Logger log = LoggerFactory.getLogger(WechatJsapiController.class);

    private final WechatJsapiSignatureService signatureService;
    private final JsapiSignatureErrorMapper errorMapper;

    public WechatJsapiController(
            WechatJsapiSignatureService signatureService,
            JsapiSignatureErrorMapper errorMapper) {
        this.signatureService = signatureService;
        this.errorMapper = errorMapper;
    }

    @GetMapping("/wechat/callback/jsapi-signature")
    public ResponseEntity<Map<String, String>> signature(@RequestParam String url) {
        return ResponseEntity.ok(signatureService.generateSignature(url));
    }

    @PostMapping("/wechat/callback/jsapi-signature/error")
    public ResponseEntity<Void> reportSignatureError(@RequestBody JsapiSignatureErrorReport report) {
        log.warn("WeChat JS-SDK config failed url={} errMsg={}", report.url(), report.errMsg());
        persist(report);
        return ResponseEntity.noContent().build();
    }

    private void persist(JsapiSignatureErrorReport report) {
        try {
            errorMapper.insert(new JsapiSignatureErrorRecord(
                    null, report.url(), report.errMsg(), null, null));
        } catch (RuntimeException exception) {
            // 不影响 204 响应；前端 sendBeacon fire-and-forget，分享本身不依赖这条记录。
            // 日志已有 WARN，DB 写不进去也不该把上报接口拖成 5xx。
            log.warn("Failed to persist JS-SDK signature error url={} errMsg={} errorClass={}",
                    report.url(), report.errMsg(), exception.getClass().getSimpleName());
        }
    }

    public record JsapiSignatureErrorReport(String url, String errMsg) {
    }
}
