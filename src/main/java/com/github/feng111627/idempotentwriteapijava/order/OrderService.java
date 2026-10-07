package com.github.feng111627.idempotentwriteapijava.order;

import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

import com.github.feng111627.idempotentwriteapijava.error.DependencyUnavailableException;
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

    /**
     * 故障开关：打开时所有创建尝试都抛出 {@link DependencyUnavailableException}，
     * 用于在本地不依赖任何外部服务地复现"依赖临时不可用"。
     * 可用配置 order.dependency-unavailable=true 在启动时打开，也可运行时切换。
     */
    private final AtomicBoolean dependencyUnavailable;

    public OrderService(@Value("${order.create-delay-ms:0}") long createDelayMs,
                        @Value("${order.dependency-unavailable:false}") boolean dependencyUnavailable) {
        this.createDelayMs = createDelayMs;
        this.dependencyUnavailable = new AtomicBoolean(dependencyUnavailable);
    }

    /** 打开/关闭"依赖临时不可用"开关，返回切换前状态 */
    public boolean setDependencyUnavailable(boolean unavailable) {
        return dependencyUnavailable.getAndSet(unavailable);
    }

    public boolean isDependencyUnavailable() {
        return dependencyUnavailable.get();
    }

    public OrderResponse createOrder(OrderRequest request) {
        if (createDelayMs > 0) {
            try {
                Thread.sleep(createDelayMs);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        // 故障检查放在耗时之后：模拟"依赖响应缓慢且最终失败"的真实形态，
        // 也让本地能稳定复现"请求在途期间依赖转为不可用"的窗口。
        if (dependencyUnavailable.get()) {
            throw new DependencyUnavailableException("依赖暂时不可用，请稍后重试");
        }
        String orderId = UUID.randomUUID().toString();
        createdCount.incrementAndGet();
        return new OrderResponse(orderId, request.itemId(), request.quantity(), false);
    }

    public long createdCount() {
        return createdCount.get();
    }
}
