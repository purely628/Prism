package com.prism.user.config;

import com.prism.common.result.ErrorCode;
import com.prism.common.result.Result;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

/**
 * user 模块补充异常处理（优先于 common 的兜底 Exception handler）：
 * 把请求体不可读/缺参/参数类型错误等客户端问题归为 400 参数错误，
 * 而不是落进 common GlobalExceptionHandler 的 50000 兜底。
 * （各 Controller 的 X-User-Id 头均为 required=false + 手动判空，不会抛缺头异常）
 */
@Order(Ordered.HIGHEST_PRECEDENCE)
@RestControllerAdvice
public class UserModuleExceptionHandler {

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public Result<Void> unreadable(HttpMessageNotReadableException e) {
        return Result.fail(ErrorCode.PARAM_ERROR, "请求体不是合法的 JSON（请使用 UTF-8 编码）");
    }

    @ExceptionHandler({MissingServletRequestParameterException.class,
            MethodArgumentTypeMismatchException.class})
    public Result<Void> missing(Exception e) {
        return Result.fail(ErrorCode.PARAM_ERROR, "缺少必要参数或参数类型错误");
    }
}
