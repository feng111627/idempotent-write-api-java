package com.github.feng111627.idempotentwriteapijava.idempotency;

import com.github.feng111627.idempotentwriteapijava.api.OrderResponse;
import com.github.feng111627.idempotentwriteapijava.error.ApiException;
import com.github.feng111627.idempotentwriteapijava.error.ErrorCode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.function.Supplier;

/**
 * 幂等执行器：
 * <ul>
 *   <li>同一个幂等键 + 同样的内容：只真正执行一次，后续请求（包括并发请求）重放首次结果；</li>
 *   <li>同一个幂等键 + 不同的内容：明确拒绝（409 IDEMPOTENCY_KEY_CONFLICT）；</li>
 *   <li>首次执行失败：不占用该键，客户端可用同一个键重试。</li>
 * </ul>
 */
@Service
public class IdempotencyService {

    private static final Logger log = LoggerFactory.getLogger(IdempotencyService.class);

    /** 幂等记录表：key -> 首次请求的记录（内容指纹 + 执行结果）。 */
    private final ConcurrentMap<String, IdempotencyRecord> records = new ConcurrentHashMap<>();

    /**
     * @param key         幂等键
     * @param fingerprint 请求内容指纹
     * @param action      真正产生副作用的业务动作
     * @return 首次执行的结果（重放请求拿到的也是这个结果，replayed 标记为 true）
     */
    public OrderResponse execute(String key, String fingerprint, Supplier<OrderResponse> action) {
        IdempotencyRecord fresh = new IdempotencyRecord(fingerprint);
        IdempotencyRecord existing = records.putIfAbsent(key, fresh);

        if (existing == null) {
            // 抢到键：本次请求负责真正执行
            log.info("idempotency key [{}] claimed, executing business action", key);
            try {
                OrderResponse response = action.get();
                fresh.result.complete(response);
                return response;
            } catch (RuntimeException e) {
                // 执行失败：完成 future 让等待方拿到同样的错误，并释放键允许重试
                fresh.result.completeExceptionally(e);
                records.remove(key, fresh);
                log.info("idempotency key [{}] released after failed execution, retry is allowed", key);
                throw e;
            }
        }

        // 键已被占用：先比对内容指纹
        if (!existing.fingerprint.equals(fingerprint)) {
            log.warn("idempotency key [{}] reused with different payload, rejected", key);
            throw new ApiException(ErrorCode.IDEMPOTENCY_KEY_CONFLICT, HttpStatus.CONFLICT,
                    "idempotency key was already used with a different request payload");
        }

        // 内容一致：等待首次执行完成并重放其结果（并发重放不会产生第二份副作用）
        try {
            OrderResponse first = existing.result.join();
            log.info("idempotency key [{}] replayed, returning the first result", key);
            return new OrderResponse(first.orderId(), first.productId(), first.quantity(), first.status(), true);
        } catch (CompletionException e) {
            // 首次执行失败：重放方得到与首次相同的错误
            if (e.getCause() instanceof RuntimeException cause) {
                throw cause;
            }
            throw e;
        }
    }

    /** 仅供测试观察当前记录数。 */
    int recordCount() {
        return records.size();
    }

    private static final class IdempotencyRecord {
        private final String fingerprint;
        private final CompletableFuture<OrderResponse> result = new CompletableFuture<>();

        private IdempotencyRecord(String fingerprint) {
            this.fingerprint = fingerprint;
        }
    }
}
