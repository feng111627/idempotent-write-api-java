package com.github.feng111627.idempotentwriteapijava.error;

import java.time.Instant;

/**
 * 统一错误返回体。
 *
 * <p>retryable 表示这次失败值不值得重试：
 * true（如 503 临时不可用）说明稍后重试可能成功；
 * false（如 400 入参不合法、409 键冲突）说明原样重试无意义，需修正请求。
 */
public record ErrorResponse(String code, String message, boolean retryable, Instant timestamp) {

    public static ErrorResponse of(ErrorCode code, String message, boolean retryable) {
        return new ErrorResponse(code.name(), message, retryable, Instant.now());
    }
}
