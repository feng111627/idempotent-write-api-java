package com.github.feng111627.idempotentwriteapijava.api;

/**
 * 创建订单响应。replayed=true 表示本次是幂等重放，返回的是首次执行的结果。
 */
public record OrderResponse(String orderId, String productId, int quantity, String status, boolean replayed) {
}
