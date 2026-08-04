package cn.minglli.lumora.wechat;

import java.util.Map;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class WechatJsapiController {

    private final WechatJsapiSignatureService signatureService;

    public WechatJsapiController(WechatJsapiSignatureService signatureService) {
        this.signatureService = signatureService;
    }

    @GetMapping("/wechat/callback/jsapi-signature")
    public ResponseEntity<Map<String, String>> signature(@RequestParam String url) {
        return ResponseEntity.ok(signatureService.generateSignature(url));
    }
}