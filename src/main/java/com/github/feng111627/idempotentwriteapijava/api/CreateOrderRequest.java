package com.github.feng111627.idempotentwriteapijava.api;

/**
 * 创建订单请求体。字段保持与既有调用方式兼容。
 */
public record CreateOrderRequest(String productId, Integer quantity) {
}
