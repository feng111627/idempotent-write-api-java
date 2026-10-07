package com.github.feng111627.idempotentwriteapijava.dependency;

/**
 * 下游依赖临时不可用：表示这是一次可恢复的失败，客户端稍后重试可能成功。
 * 内部细节不对外暴露，由全局异常处理器映射为统一的 503 响应。
 */
public class DependencyUnavailableException extends RuntimeException {

    public DependencyUnavailableException(String message) {
        super(message);
    }
}
