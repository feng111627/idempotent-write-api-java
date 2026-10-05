package com.github.feng111627.idempotentwriteapijava.api;

/**
 * 统一错误返回体。不携带任何内部异常细节。
 */
public record ErrorResponse(String code, String message) {
}
