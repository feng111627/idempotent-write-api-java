package com.github.feng111627.idempotentwriteapijava.error;

/**
 * 对外可区分的错误码。
 */
public enum ErrorCode {
    INVALID_ARGUMENT,
    IDEMPOTENCY_KEY_CONFLICT,
    SERVICE_UNAVAILABLE,
    INTERNAL_ERROR
}
