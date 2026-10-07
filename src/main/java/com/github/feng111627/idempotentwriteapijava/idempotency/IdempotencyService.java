package com.github.feng111627.idempotentwriteapijava.idempotency;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Supplier;

import com.github.feng111627.idempotentwriteapijava.error.ApiException;
import com.github.feng111627.idempotentwriteapijava.error.ErrorCode;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

/**
 * 幂等执行器：同一键只真正执行一次，重复提交返回首次结果。
 *
 * <p>并发保证：以 {@link ConcurrentHashMap#putIfAbsent} 抢占键的所有权，
 * 抢到所有权的线程执行真实操作，其余线程等待同一个 future，
 * 因此副作用只会发生一次，且所有并发请求拿到同一份结果。
 */
@Service
public class IdempotencyService {

    /** 等待首个请求完成的最长时间 */
    private static final long WAIT_TIMEOUT_SECONDS = 10;

    /** 一次幂等执行的结果：value 为业务返回值，replayed 表示是否为重放（未真正执行） */
    public record IdempotentResult<T>(T value, boolean replayed) {
    }

    /** 键下已存储的结果：内容指纹 + 业务返回值 */
    private record StoredResult(String fingerprint, Object value) {
    }

    private final ConcurrentHashMap<String, CompletableFuture<StoredResult>> store =
            new ConcurrentHashMap<>();

    /**
     * 每个键首次见到时的内容指纹。与执行结果分开保存：
     * 即使执行因临时故障失败、键被释放，指纹仍然保留，
     * 因此故障期间同键不同内容的请求依然按冲突拒绝，而不会被当成新请求放行。
     */
    private final ConcurrentHashMap<String, String> fingerprints = new ConcurrentHashMap<>();

    /**
     * 以幂等键执行操作。
     *
     * @param key         幂等键
     * @param fingerprint 请求内容指纹，用于检测同键不同内容
     * @param operation   真正的业务操作，只会被调用一次
     * @throws ApiException 同键不同内容时抛出 {@link ErrorCode#IDEMPOTENCY_KEY_CONFLICT}
     */
    @SuppressWarnings("unchecked")
    public <T> IdempotentResult<T> execute(String key, String fingerprint, Supplier<T> operation) {
        checkFingerprint(key, fingerprint);

        CompletableFuture<StoredResult> newFuture = new CompletableFuture<>();
        CompletableFuture<StoredResult> existing = store.putIfAbsent(key, newFuture);

        if (existing == null) {
            // 抢到键的所有权：执行真实操作
            try {
                T value = operation.get();
                newFuture.complete(new StoredResult(fingerprint, value));
                return new IdempotentResult<>(value, false);
            } catch (RuntimeException e) {
                // 执行失败：先让等待者拿到失败结论，再释放键，允许客户端稍后重试。
                // 键不会被失败占死，但内容指纹保留（见 fingerprints）。
                newFuture.completeExceptionally(e);
                store.remove(key, newFuture);
                throw e;
            }
        }

        // 未抢到：等待首个请求的结果
        StoredResult stored = await(key, existing);
        if (!stored.fingerprint().equals(fingerprint)) {
            throw new ApiException(ErrorCode.IDEMPOTENCY_KEY_CONFLICT, HttpStatus.CONFLICT,
                    "幂等键已被使用，但请求内容不一致");
        }
        return new IdempotentResult<>((T) stored.value(), true);
    }

    /** 同键不同内容一律拒绝，无论当前是否有执行在进行、或之前是否失败过 */
    private void checkFingerprint(String key, String fingerprint) {
        String seen = fingerprints.putIfAbsent(key, fingerprint);
        if (seen != null && !seen.equals(fingerprint)) {
            throw new ApiException(ErrorCode.IDEMPOTENCY_KEY_CONFLICT, HttpStatus.CONFLICT,
                    "幂等键已被使用，但请求内容不一致");
        }
    }

    private StoredResult await(String key, CompletableFuture<StoredResult> future) {
        try {
            return future.get(WAIT_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        } catch (TimeoutException e) {
            throw new ApiException(ErrorCode.SERVICE_UNAVAILABLE, HttpStatus.SERVICE_UNAVAILABLE,
                    "服务暂时繁忙，请稍后重试");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ApiException(ErrorCode.SERVICE_UNAVAILABLE, HttpStatus.SERVICE_UNAVAILABLE,
                    "服务暂时繁忙，请稍后重试");
        } catch (ExecutionException e) {
            // 首个请求执行失败：键已被释放，提示客户端重试
            if (e.getCause() instanceof ApiException apiException) {
                throw apiException;
            }
            throw new ApiException(ErrorCode.SERVICE_UNAVAILABLE, HttpStatus.SERVICE_UNAVAILABLE,
                    "服务暂时繁忙，请稍后重试");
        }
    }
}
