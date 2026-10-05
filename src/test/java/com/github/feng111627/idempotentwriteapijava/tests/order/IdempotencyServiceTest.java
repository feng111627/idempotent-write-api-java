package com.github.feng111627.idempotentwriteapijava.tests.order;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import com.github.feng111627.idempotentwriteapijava.error.ApiException;
import com.github.feng111627.idempotentwriteapijava.error.ErrorCode;
import com.github.feng111627.idempotentwriteapijava.idempotency.IdempotencyService;
import com.github.feng111627.idempotentwriteapijava.idempotency.IdempotencyService.IdempotentResult;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 幂等执行器的单元测试（不依赖 Spring 容器）。
 */
class IdempotencyServiceTest {

    private static final Logger log = LoggerFactory.getLogger(IdempotencyServiceTest.class);

    private final IdempotencyService service = new IdempotencyService();

    @Test
    @DisplayName("执行失败：释放幂等键，允许修正后重试")
    void failureReleasesKeyForRetry() {
        AtomicInteger calls = new AtomicInteger();

        assertThatThrownBy(() -> service.execute("k", "fp", () -> {
            calls.incrementAndGet();
            throw new IllegalStateException("下游故障");
        })).isInstanceOf(IllegalStateException.class);

        IdempotentResult<String> retry = service.execute("k", "fp", () -> {
            calls.incrementAndGet();
            return "ok";
        });

        log.info("[失败重试] 首次失败已释放键, 重试结果={}, 总执行次数={}", retry.value(), calls.get());
        assertThat(retry.value()).isEqualTo("ok");
        assertThat(retry.replayed()).isFalse();
        assertThat(calls.get()).isEqualTo(2);
        log.info("[失败重试] 通过：失败后同键可重新执行");
    }

    @Test
    @DisplayName("同键不同内容：抛出 IDEMPOTENCY_KEY_CONFLICT")
    void sameKeyDifferentFingerprintConflicts() {
        service.execute("k", "fp-1", () -> "done");

        assertThatThrownBy(() -> service.execute("k", "fp-2", () -> "other"))
                .isInstanceOfSatisfying(ApiException.class, e -> {
                    log.info("[键冲突] 错误码={}, 消息={}", e.getCode(), e.getMessage());
                    assertThat(e.getCode()).isEqualTo(ErrorCode.IDEMPOTENCY_KEY_CONFLICT);
                });
        log.info("[键冲突] 通过：同键不同内容被明确拒绝");
    }

    @Test
    @DisplayName("并发等待：同键并发请求等待首个结果而非重复执行")
    void concurrentCallsWaitForFirstResult() throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicInteger executions = new AtomicInteger();

        Future<IdempotentResult<String>> first = pool.submit(() -> service.execute("k", "fp", () -> {
            executions.incrementAndGet();
            entered.countDown();
            try {
                assertThat(release.await(5, TimeUnit.SECONDS)).isTrue();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return "first-result";
        }));

        assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();
        Future<IdempotentResult<String>> second = pool.submit(() -> service.execute("k", "fp", () -> {
            executions.incrementAndGet();
            return "second-result";
        }));

        release.countDown();
        IdempotentResult<String> r1 = first.get(5, TimeUnit.SECONDS);
        IdempotentResult<String> r2 = second.get(5, TimeUnit.SECONDS);
        pool.shutdown();

        log.info("[并发等待] 首个结果={}, 并发结果={}(replayed={}), 真实执行次数={}",
                r1.value(), r2.value(), r2.replayed(), executions.get());
        assertThat(r1.value()).isEqualTo("first-result");
        assertThat(r2.value()).isEqualTo("first-result");
        assertThat(r2.replayed()).isTrue();
        assertThat(executions.get()).isEqualTo(1);
        log.info("[并发等待] 通过：并发请求等待首个结果，副作用仅一次");
    }
}
