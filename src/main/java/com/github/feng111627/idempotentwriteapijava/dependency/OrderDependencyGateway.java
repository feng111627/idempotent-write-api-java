package com.github.feng111627.idempotentwriteapijava.dependency;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import org.springframework.stereotype.Component;

/**
 * 下游依赖网关：下单前检查依赖是否可用。
 *
 * <p>本地不依赖任何外部服务即可稳定制造"临时不可用"：
 * <ul>
 *   <li>{@link #simulateOutage()} 进入持续故障，{@link #recover()} 恢复；</li>
 *   <li>{@link #failNext(int)} 让接下来 n 次检查失败，之后自动恢复。</li>
 * </ul>
 * 故障期间 {@link #checkAvailable()} 抛出 {@link DependencyUnavailableException}，
 * 这是一种可恢复的失败：客户端稍后重试可能成功。
 */
@Component
public class OrderDependencyGateway {

    /** 是否处于持续故障状态 */
    private volatile boolean outage = false;

    /** 剩余的有计划失败次数（failNext 使用） */
    private final AtomicInteger remainingFailures = new AtomicInteger(0);

    /** 因依赖不可用而失败的检查总次数，用于测试验证故障被真实触发 */
    private final AtomicLong failureCount = new AtomicLong(0);

    /**
     * 检查依赖可用性。
     *
     * @throws DependencyUnavailableException 依赖临时不可用（可恢复，值得重试）
     */
    public void checkAvailable() {
        if (outage || consumePlannedFailure()) {
            failureCount.incrementAndGet();
            throw new DependencyUnavailableException("下游依赖暂时不可用");
        }
    }

    /** 进入持续故障状态：之后所有检查都失败，直到 {@link #recover()} */
    public void simulateOutage() {
        outage = true;
    }

    /** 从故障状态恢复，并清除剩余的计划失败次数 */
    public void recover() {
        outage = false;
        remainingFailures.set(0);
    }

    /** 让接下来的 n 次检查失败，之后自动恢复（用于模拟抖动） */
    public void failNext(int n) {
        remainingFailures.set(n);
    }

    /** 因依赖不可用而失败的检查总次数 */
    public long failureCount() {
        return failureCount.get();
    }

    private boolean consumePlannedFailure() {
        // 仅当剩余次数 > 0 时消费一次并返回 true
        return remainingFailures.getAndUpdate(n -> n > 0 ? n - 1 : 0) > 0;
    }
}
