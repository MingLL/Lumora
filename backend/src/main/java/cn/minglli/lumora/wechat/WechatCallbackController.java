package cn.minglli.lumora.wechat;

import java.io.IOException;

import jakarta.servlet.http.HttpServletRequest;

import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
public final class WechatCallbackController {

    public static final int MAX_REQUEST_BODY_BYTES = 262_144;

    private final WechatProtocolService protocol;
    private final WechatEventIngestionService ingestion;

    public WechatCallbackController(
            WechatProtocolService protocol,
            WechatEventIngestionService ingestion) {
        this.protocol = protocol;
        this.ingestion = ingestion;
    }

    @GetMapping(value = "/wechat/callback/{appId}", produces = MediaType.TEXT_PLAIN_VALUE)
    public ResponseEntity<String> verify(
            @PathVariable String appId,
            @RequestParam(required = false) String signature,
            @RequestParam(required = false) String timestamp,
            @RequestParam(required = false) String nonce,
            @RequestParam(name = "echostr", required = false) String echoString) {
        protocol.validateAppId(appId);
        return ResponseEntity.ok()
                .contentType(MediaType.TEXT_PLAIN)
                .body(protocol.verifyUrl(signature, timestamp, nonce, echoString));
    }

    @PostMapping(value = "/wechat/callback/{appId}", produces = MediaType.TEXT_PLAIN_VALUE)
    public ResponseEntity<String> receive(
            @PathVariable String appId,
            @RequestParam(required = false) String signature,
            @RequestParam(name = "msg_signature", required = false) String messageSignature,
            @RequestParam(required = false) String timestamp,
            @RequestParam(required = false) String nonce,
            @RequestParam(name = "encrypt_type", required = false) String encryptType,
            HttpServletRequest request) {
        protocol.validateAppId(appId);
        byte[] body = readLimitedBody(request);
        WechatInboundMessage message =
                "aes".equals(encryptType) || messageSignature != null
                        ? protocol.parseEncrypted(body, messageSignature, timestamp, nonce)
                        : protocol.parsePlaintext(body, signature, timestamp, nonce);
        ingestion.ingest(message);
        return ResponseEntity.ok()
                .contentType(MediaType.TEXT_PLAIN)
                .body("success");
    }

    private static byte[] readLimitedBody(HttpServletRequest request) {
        if (request.getContentLengthLong() > MAX_REQUEST_BODY_BYTES) {
            throw WechatCallbackException.payloadTooLarge();
        }
        try {
            byte[] body = request.getInputStream().readNBytes(MAX_REQUEST_BODY_BYTES + 1);
            if (body.length > MAX_REQUEST_BODY_BYTES) {
                throw WechatCallbackException.payloadTooLarge();
            }
            return body;
        } catch (IOException exception) {
            throw WechatCallbackException.serviceUnavailable(exception);
        }
    }
}
