package com.fansea.ai.config;

import com.fansea.ai.auth.AuthException;
import com.fansea.ai.domain.dto.AjaxResult;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * 全局异常处理器
 * 
 */
@RestControllerAdvice
public class GlobalExceptionHandler
{
    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    /**
     * 请求方式不支持
     */
    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public AjaxResult handleHttpRequestMethodNotSupported(HttpRequestMethodNotSupportedException e,
                                                          HttpServletRequest request)
    {
        String requestURI = request.getRequestURI();
        log.error("请求地址'{}',不支持'{}'请求", requestURI, e.getMethod());
        return AjaxResult.error(e.getMessage());
    }


    /**
     * 拦截未知的运行时异常
     */
    @ExceptionHandler(RuntimeException.class)
    public AjaxResult handleRuntimeException(RuntimeException e, HttpServletRequest request)
    {
        String requestURI = request.getRequestURI();
        log.error("请求地址'{}',发生未知异常.", requestURI, e);
        return AjaxResult.error(e.getMessage());
    }

    /**
     * 鉴权异常(AuthException):根据错误码映射 HTTP 状态,并对 401 附加 WWW-Authenticate 头。
     * 因 AuthException 继承自 RuntimeException,该处理器比 handleRuntimeException 更具体,
     * Spring 的 ExceptionHandlerExceptionResolver 会优先匹配此方法。
     */
    @ExceptionHandler(AuthException.class)
    public ResponseEntity<AjaxResult> handleAuth(AuthException ex)
    {
        int code = ex.getCode();
        int http;
        if (code == 40100 || code == 40101 || code == 40102 || code == 40103) {
            http = 401;
        } else if (code == 40301 || code == 40302) {
            http = 403;
        } else if (code == 40901) {
            http = 409;
        } else if (code == 41001 || code == 41002) {
            http = 410;
        } else if (code == 40401) {
            http = 404;
        } else {
            http = 400;
        }
        log.warn("auth error code={} msg={}", code, ex.getMessage());
        AjaxResult body = AjaxResult.error(code, ex.getMessage());
        if (http == 401) {
            HttpHeaders h = new HttpHeaders();
            h.add("WWW-Authenticate",
                    "Bearer error=\"invalid_token\", error_description=\"" + ex.getMessage() + "\"");
            return new ResponseEntity<>(body, h, HttpStatus.valueOf(http));
        }
        return new ResponseEntity<>(body, HttpStatus.valueOf(http));
    }

    /**
     * 系统异常
     */
    @ExceptionHandler(Exception.class)
    public AjaxResult handleException(Exception e, HttpServletRequest request)
    {
        String requestURI = request.getRequestURI();
        log.error("请求地址'{}',发生系统异常.", requestURI, e);
        return AjaxResult.error(e.getMessage());
    }

}
