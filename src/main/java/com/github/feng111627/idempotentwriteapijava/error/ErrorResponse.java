package com.github.feng111627.idempotentwriteapijava.error;

import java.time.Instant;

/**
 * 统一错误返回体。
 *
 * <p>retryable 告诉客户端这次失败值不值得原样重试：
 * 临时性故障（503）为 true；入参不合法、键冲突等需要客户端先修正请求的为 false。
 */
public record ErrorResponse(String code, String message, boolean retryable, Instant timestamp) {

    public static ErrorResponse of(ErrorCode code, String message, boolean retryable) {
        return new ErrorResponse(code.name(), message, retryable, Instant.now());
    }
}
