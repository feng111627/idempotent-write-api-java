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
 *
 * <p>失败语义：执行失败（含依赖临时不可用）时立即释放键，不把键占死，
 * 客户端可用同一键、同一内容安全重试；指纹在抢占键时就登记，
 * 因此即使首个请求还在执行或刚刚失败，同键不同内容的请求也会被
 * 按冲突拒绝，而不会被当成新请求放行。
 */
@Service
public class IdempotencyService {

    /** 等待首个请求完成的最长时间 */
    private static final long WAIT_TIMEOUT_SECONDS = 10;

    /** 一次幂等执行的结果：value 为业务返回值，replayed 表示是否为重放（未真正执行） */
    public record IdempotentResult<T>(T value, boolean replayed) {
    }

    /** 键的在途记录：抢占键时即登记内容指纹，future 承载最终业务结果 */
    private record InFlight(String fingerprint, CompletableFuture<Object> future) {
    }

    private final ConcurrentHashMap<String, InFlight> store = new ConcurrentHashMap<>();

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
        InFlight inFlight = new InFlight(fingerprint, new CompletableFuture<>());
        InFlight existing = store.putIfAbsent(key, inFlight);

        if (existing == null) {
            // 抢到键的所有权：执行真实操作
            try {
                T value = operation.get();
                inFlight.future().complete(value);
                return new IdempotentResult<>(value, false);
            } catch (RuntimeException e) {
                // 执行失败：释放键，允许客户端以同键同内容重试
                store.remove(key, inFlight);
                inFlight.future().completeExceptionally(e);
                throw e;
            }
        }

        // 未抢到：先比对指纹。无论首个请求进行中、已成功还是刚失败，
        // 同键不同内容都按冲突拒绝，绝不当成新请求放行。
        if (!existing.fingerprint().equals(fingerprint)) {
            throw new ApiException(ErrorCode.IDEMPOTENCY_KEY_CONFLICT, HttpStatus.CONFLICT,
                    "幂等键已被使用，但请求内容不一致");
        }

        // 内容一致：等待首个请求的结果，拿到与其一致的结论
        return new IdempotentResult<>((T) await(existing.future()), true);
    }

    private Object await(CompletableFuture<Object> future) {
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
            // 首个请求执行失败：键已被释放。与首个请求保持一致的对外结论：
            // 业务异常（如依赖临时不可用）原样传递，其余按内部错误处理。
            if (e.getCause() instanceof ApiException apiException) {
                throw apiException;
            }
            throw new ApiException(ErrorCode.INTERNAL_ERROR, HttpStatus.INTERNAL_SERVER_ERROR,
                    "服务内部错误");
        }
    }
}
