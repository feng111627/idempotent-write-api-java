package com.github.feng111627.idempotentwriteapijava.error;

import java.util.stream.Collectors;

import com.github.feng111627.idempotentwriteapijava.dependency.DependencyUnavailableException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * 全局异常处理：把异常映射为统一错误体，内部异常细节不外泄。
 *
 * <p>三类客户端可区分的失败：
 * <ul>
 *   <li>400 VALIDATION_ERROR —— 入参不合法，原样重试无意义；</li>
 *   <li>409 IDEMPOTENCY_KEY_CONFLICT —— 同键不同内容，原样重试无意义；</li>
 *   <li>503 SERVICE_UNAVAILABLE —— 临时故障，可稍后重试（带 Retry-After 头）。</li>
 * </ul>
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    /** 建议客户端重试前等待的秒数 */
    private static final String RETRY_AFTER_SECONDS = "1";

    @ExceptionHandler(ApiException.class)
    public ResponseEntity<ErrorResponse> handleApiException(ApiException ex) {
        boolean retryable = ex.getCode() == ErrorCode.SERVICE_UNAVAILABLE;
        ResponseEntity<ErrorResponse> response = ResponseEntity.status(ex.getStatus())
                .body(ErrorResponse.of(ex.getCode(), ex.getMessage(), retryable));
        if (retryable) {
            response = ResponseEntity.status(ex.getStatus())
                    .header(HttpHeaders.RETRY_AFTER, RETRY_AFTER_SECONDS)
                    .body(response.getBody());
        }
        return response;
    }

    @ExceptionHandler(DependencyUnavailableException.class)
    public ResponseEntity<ErrorResponse> handleDependencyUnavailable(DependencyUnavailableException ex) {
        // 内部原因只记日志；对外只说明"暂时处理不了，值得重试"
        log.warn("下游依赖临时不可用: {}", ex.getMessage());
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .header(HttpHeaders.RETRY_AFTER, RETRY_AFTER_SECONDS)
                .body(ErrorResponse.of(ErrorCode.SERVICE_UNAVAILABLE,
                        "服务暂时不可用，请稍后重试", true));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ErrorResponse> handleValidation(MethodArgumentNotValidException ex) {
        String detail = ex.getBindingResult().getFieldErrors().stream()
                .map(fe -> fe.getField() + ": " + fe.getDefaultMessage())
                .collect(Collectors.joining("; "));
        return ResponseEntity.badRequest()
                .body(ErrorResponse.of(ErrorCode.VALIDATION_ERROR,
                        "请求参数不合法: " + detail, false));
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ErrorResponse> handleNotReadable(HttpMessageNotReadableException ex) {
        return ResponseEntity.badRequest()
                .body(ErrorResponse.of(ErrorCode.VALIDATION_ERROR, "请求体无法解析", false));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorResponse> handleUnexpected(Exception ex) {
        // 内部细节只记日志，不返回给客户端
        log.error("未预期的内部错误", ex);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(ErrorResponse.of(ErrorCode.INTERNAL_ERROR, "服务内部错误", false));
    }
}
