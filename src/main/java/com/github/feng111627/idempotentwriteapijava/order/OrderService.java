package com.github.feng111627.idempotentwriteapijava.order;

import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

import com.github.feng111627.idempotentwriteapijava.dependency.OrderDependencyGateway;
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

    /** 下游依赖网关：下单前先确认依赖可用 */
    private final OrderDependencyGateway dependencyGateway;

    public OrderService(@Value("${order.create-delay-ms:0}") long createDelayMs,
                        OrderDependencyGateway dependencyGateway) {
        this.createDelayMs = createDelayMs;
        this.dependencyGateway = dependencyGateway;
    }

    public OrderResponse createOrder(OrderRequest request) {
        // 依赖临时不可用时在此抛出 DependencyUnavailableException，
        // 本次请求不算成功、不产生副作用，客户端可稍后重试
        dependencyGateway.checkAvailable();
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
