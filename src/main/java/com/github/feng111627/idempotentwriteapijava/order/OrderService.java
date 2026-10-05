package com.github.feng111627.idempotentwriteapijava.order;

import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * 订单业务逻辑：真正的副作用发生在这里。
 */
@Service
public class OrderService {

    /** 已真实创建的订单数，用于验证副作用只发生一次 */
    private final AtomicLong createdCount = new AtomicLong();

    /** 模拟下游耗时（毫秒），默认 0；测试可用它放大并发竞争窗口 */
    private final long createDelayMs;

    public OrderService(@Value("${order.create-delay-ms:0}") long createDelayMs) {
        this.createDelayMs = createDelayMs;
    }

    public OrderResponse createOrder(OrderRequest request) {
        if (createDelayMs > 0) {
            try {
                Thread.sleep(createDelayMs);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        String orderId = UUID.randomUUID().toString();
        createdCount.incrementAndGet();
        return new OrderResponse(orderId, request.itemId(), request.quantity(), false);
    }

    public long createdCount() {
        return createdCount.get();
    }
}
