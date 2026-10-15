package com.prism.common.web;

import com.prism.common.exception.BizException;
import com.prism.common.result.ErrorCode;
import com.prism.common.result.Result;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * MVC 服务全局异常处理（chat / gateway 为响应式服务，各自实现 ErrorWebExceptionHandler）
 */
@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(BizException.class)
    public ResponseEntity<Result<Void>> handleBiz(BizException e) {
        HttpStatus status = switch (e.getErrorCode()) {
            case UNAUTHORIZED, API_KEY_INVALID -> HttpStatus.UNAUTHORIZED;
            case FORBIDDEN -> HttpStatus.FORBIDDEN;
            case NOT_FOUND, CHANNEL_UNAVAILABLE -> HttpStatus.NOT_FOUND;
            case RATE_LIMITED, QUOTA_EXCEEDED -> HttpStatus.TOO_MANY_REQUESTS;
            default -> HttpStatus.OK;
        };
        return ResponseEntity.status(status).body(Result.fail(e.getErrorCode(), e.getMessage()));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<Result<Void>> handleValid(MethodArgumentNotValidException e) {
        String msg = e.getBindingResult().getFieldErrors().stream()
                .findFirst().map(FieldError::getDefaultMessage).orElse("参数错误");
        return ResponseEntity.badRequest().body(Result.fail(ErrorCode.PARAM_ERROR, msg));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<Result<Void>> handleOther(Exception e) {
        log.error("unhandled exception", e);
        return ResponseEntity.internalServerError().body(Result.fail(ErrorCode.INTERNAL_ERROR));
    }
}
