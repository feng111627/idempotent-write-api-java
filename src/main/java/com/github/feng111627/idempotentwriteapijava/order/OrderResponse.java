package com.github.feng111627.idempotentwriteapijava.order;

/**
 * 下单结果。
 */
public record OrderResponse(String orderId, String itemId, int quantity, boolean replayed) {
}
