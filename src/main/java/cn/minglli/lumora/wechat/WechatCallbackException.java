package cn.minglli.lumora.wechat;

import org.springframework.http.HttpStatus;

public final class WechatCallbackException extends RuntimeException {

    private final HttpStatus status;

    private WechatCallbackException(HttpStatus status, String message, Throwable cause) {
        super(message, cause);
        this.status = status;
    }

    public static WechatCallbackException notFound() {
        return new WechatCallbackException(HttpStatus.NOT_FOUND, "Unknown WeChat app", null);
    }

    public static WechatCallbackException forbidden() {
        return new WechatCallbackException(HttpStatus.FORBIDDEN, "Forbidden WeChat callback", null);
    }

    public static WechatCallbackException forbidden(Throwable cause) {
        return new WechatCallbackException(
                HttpStatus.FORBIDDEN, "Forbidden WeChat callback", cause);
    }

    public static WechatCallbackException payloadTooLarge() {
        return new WechatCallbackException(
                HttpStatus.PAYLOAD_TOO_LARGE, "WeChat callback body is too large", null);
    }

    public static WechatCallbackException serviceUnavailable(Throwable cause) {
        return new WechatCallbackException(
                HttpStatus.SERVICE_UNAVAILABLE, "WeChat callback unavailable", cause);
    }

    public HttpStatus status() {
        return status;
    }
}
