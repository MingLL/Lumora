package cn.minglli.lumora.wechat;

import jakarta.servlet.http.HttpServletRequest;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

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
        log.warn("Rejected WeChat callback status={} reason={} cause={} path={} method={}",
                exception.status().value(), exception.getMessage(), causeName(exception),
                currentPath(), currentMethod());
        return ResponseEntity.status(exception.status()).build();
    }

    @ExceptionHandler(WechatMalformedXmlException.class)
    public ResponseEntity<Void> handleMalformedXml(WechatMalformedXmlException exception) {
        log.warn("Rejected malformed WeChat XML reason={} cause={} path={} method={}",
                exception.getMessage(), causeName(exception), currentPath(), currentMethod());
        return ResponseEntity.badRequest().build();
    }

    @ExceptionHandler(WechatInvalidPayloadException.class)
    public ResponseEntity<Void> handleInvalidPayload(WechatInvalidPayloadException exception) {
        log.warn("Rejected invalid WeChat payload reason={} cause={} path={} method={}",
                exception.getMessage(), causeName(exception), currentPath(), currentMethod());
        return ResponseEntity.badRequest().build();
    }

    @ExceptionHandler(DataAccessException.class)
    public ResponseEntity<Void> handleDatabaseFailure(DataAccessException exception) {
        log.error("WeChat callback database failure errorClass={} path={} method={}",
                exception.getClass().getSimpleName(), currentPath(), currentMethod(), exception);
        return ResponseEntity.status(503).build();
    }

    private static String causeName(Throwable exception) {
        Throwable cause = exception.getCause();
        return cause == null ? "none" : cause.getClass().getSimpleName();
    }

    private static String currentPath() {
        ServletRequestAttributes attrs =
                (ServletRequestAttributes) RequestContextHolder.getRequestAttributes();
        if (attrs == null) {
            return "unknown";
        }
        HttpServletRequest request = attrs.getRequest();
        String path = request.getRequestURI();
        return path != null ? path : "unknown";
    }

    private static String currentMethod() {
        ServletRequestAttributes attrs =
                (ServletRequestAttributes) RequestContextHolder.getRequestAttributes();
        if (attrs == null) {
            return "unknown";
        }
        HttpServletRequest request = attrs.getRequest();
        String method = request.getMethod();
        return method != null ? method : "unknown";
    }
}
