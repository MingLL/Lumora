package cn.minglli.lumora.wechat;

import java.util.Map;

import cn.minglli.lumora.operations.LogSanitizer;
import cn.minglli.lumora.operations.SiteUrlValidator;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
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
    private final SiteUrlValidator siteUrlValidator;

    public WechatJsapiController(
            WechatJsapiSignatureService signatureService,
            JsapiSignatureErrorMapper errorMapper,
            SiteUrlValidator siteUrlValidator) {
        this.signatureService = signatureService;
        this.errorMapper = errorMapper;
        this.siteUrlValidator = siteUrlValidator;
    }

    @GetMapping("/wechat/callback/jsapi-signature")
    public ResponseEntity<Map<String, String>> signature(@RequestParam String url) {
        // 签名是用本站的 jsapi_ticket 生成的，只应该签本站页面。不校验的话，
        // 任何人都能拿到我们身份下对任意 URL 的签名。
        if (!siteUrlValidator.isSiteUrl(url)) {
            log.warn("Rejected jsapi signature request for off-site url={}", LogSanitizer.forLog(url));
            return ResponseEntity.badRequest().build();
        }
        return ResponseEntity.ok(signatureService.generateSignature(url));
    }

    @PostMapping("/wechat/callback/jsapi-signature/error")
    public ResponseEntity<Void> reportSignatureError(@Valid @RequestBody JsapiSignatureErrorReport report) {
        log.warn("WeChat JS-SDK config failed url={} errMsg={}",
                LogSanitizer.forLog(report.url()), LogSanitizer.forLog(report.errMsg()));
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
            log.warn("Failed to persist JS-SDK signature error errorClass={}",
                    exception.getClass().getSimpleName());
        }
    }

    // @Size 的上限对齐 jsapi_signature_error 的列宽（url 2048 / err_msg 1024）：
    // 超长的话 INSERT 必然抛 DataIntegrityViolationException，与其让它走到 catch
    // 里丢一条记录，不如在入口就明确拒绝。
    //
    // errMsg 完全由客户端控制，且这个接口无需鉴权，所以它既要限长，写日志时
    // 也要过 LogSanitizer —— 见上面两处调用。
    public record JsapiSignatureErrorReport(
            @NotBlank @Size(max = SiteUrlValidator.MAX_URL_LENGTH) String url,
            @NotBlank @Size(max = 1024) String errMsg) {
    }
}
