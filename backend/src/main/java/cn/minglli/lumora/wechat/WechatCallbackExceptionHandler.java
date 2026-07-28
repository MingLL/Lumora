package cn.minglli.lumora.wechat;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * Turns callback failures into the status codes WeChat expects.
 *
 * <p>Logs carry only the rejection reason and the exception classes. Signatures,
 * ciphertext, OpenIDs and message bodies never reach the log.
 */
@RestControllerAdvice
public final class WechatCallbackExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(WechatCallbackExceptionHandler.class);

    @ExceptionHandler(WechatCallbackException.class)
    public ResponseEntity<Void> handleCallbackException(WechatCallbackException exception) {
        log.warn("Rejected WeChat callback status={} reason={} cause={}",
                exception.status().value(), exception.getMessage(), causeName(exception));
        return ResponseEntity.status(exception.status()).build();
    }

    @ExceptionHandler(WechatMalformedXmlException.class)
    public ResponseEntity<Void> handleMalformedXml(WechatMalformedXmlException exception) {
        log.warn("Rejected malformed WeChat XML reason={} cause={}",
                exception.getMessage(), causeName(exception));
        return ResponseEntity.badRequest().build();
    }

    @ExceptionHandler(WechatInvalidPayloadException.class)
    public ResponseEntity<Void> handleInvalidPayload(WechatInvalidPayloadException exception) {
        log.warn("Rejected invalid WeChat payload reason={} cause={}",
                exception.getMessage(), causeName(exception));
        return ResponseEntity.badRequest().build();
    }

    @ExceptionHandler(DataAccessException.class)
    public ResponseEntity<Void> handleDatabaseFailure(DataAccessException exception) {
        // 503 so WeChat retries the push instead of dropping the event.
        log.error("WeChat callback database failure errorClass={}",
                exception.getClass().getSimpleName(), exception);
        return ResponseEntity.status(503).build();
    }

    private static String causeName(Throwable exception) {
        Throwable cause = exception.getCause();
        return cause == null ? "none" : cause.getClass().getSimpleName();
    }
}
