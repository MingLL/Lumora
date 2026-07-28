package cn.minglli.lumora.wechat;

import org.springframework.dao.DataAccessException;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public final class WechatCallbackExceptionHandler {

    @ExceptionHandler(WechatCallbackException.class)
    public ResponseEntity<Void> handleCallbackException(WechatCallbackException exception) {
        return ResponseEntity.status(exception.status()).build();
    }

    @ExceptionHandler(WechatMalformedXmlException.class)
    public ResponseEntity<Void> handleMalformedXml(WechatMalformedXmlException exception) {
        return ResponseEntity.badRequest().build();
    }

    @ExceptionHandler(DataAccessException.class)
    public ResponseEntity<Void> handleDatabaseFailure(DataAccessException exception) {
        return ResponseEntity.status(503).build();
    }
}
