package cn.minglli.lumora.wechat;

import java.time.Instant;

/**
 * Persisted row for a share JS-SDK signature failure reported by the browser
 * via {@code POST /wechat/callback/jsapi-signature/error}.
 *
 * <p>{@code receivedAt} and {@code createdAt} default to {@code CURRENT_TIMESTAMP(6)}
 * in MySQL; on insert the application passes {@code null} so the database fills them.
 */
public record JsapiSignatureErrorRecord(
        Long id,
        String url,
        String errMsg,
        Instant receivedAt,
        Instant createdAt) {
}
