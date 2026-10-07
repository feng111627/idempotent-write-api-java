package com.github.feng111627.idempotentwriteapijava.error;

import org.springframework.http.HttpStatus;

/**
 * 依赖临时不可用：本次请求暂时处理不了，客户端可稍后以相同请求安全重试。
 * 对外映射为 503 + SERVICE_UNAVAILABLE，不暴露内部细节。
 */
public class DependencyUnavailableException extends ApiException {

    public DependencyUnavailableException(String message) {
        super(ErrorCode.SERVICE_UNAVAILABLE, HttpStatus.SERVICE_UNAVAILABLE, message);
    }
}
