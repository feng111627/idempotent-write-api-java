package com.github.feng111627.idempotentwriteapijava.error;

import java.time.Instant;

/**
 * 统一错误返回体。
 */
public record ErrorResponse(String code, String message, Instant timestamp) {

    public static ErrorResponse of(ErrorCode code, String message) {
        return new ErrorResponse(code.name(), message, Instant.now());
    }
}
