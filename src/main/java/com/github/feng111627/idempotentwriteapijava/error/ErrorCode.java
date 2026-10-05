package com.github.feng111627.idempotentwriteapijava.error;

/**
 * 对外暴露的统一错误码。客户端可据此区分错误类型。
 */
public enum ErrorCode {
    /** 入参校验失败 */
    VALIDATION_ERROR,
    /** 同一幂等键携带了不同的请求内容 */
    IDEMPOTENCY_KEY_CONFLICT,
    /** 服务暂时无法处理（如等待首个请求超时） */
    SERVICE_UNAVAILABLE,
    /** 未预期的内部错误 */
    INTERNAL_ERROR
}
