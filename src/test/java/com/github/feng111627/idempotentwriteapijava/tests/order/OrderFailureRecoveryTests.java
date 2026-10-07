package com.github.feng111627.idempotentwriteapijava.tests.order;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.feng111627.idempotentwriteapijava.order.OrderService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 临时故障与恢复链路集成测试：故障可真实触发、失败可安全重试、三类错误可区分。
 */
@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = "order.create-delay-ms=300")
class OrderFailureRecoveryTests {

    private static final Logger log = LoggerFactory.getLogger(OrderFailureRecoveryTests.class);
    private static final String URL = "/api/orders";
    private static final String BODY = "{\"itemId\":\"item-1\",\"quantity\":2}";

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private ObjectMapper objectMapper;
    @Autowired
    private OrderService orderService;

    @AfterEach
    void resetFailureSwitch() {
        orderService.setDependencyUnavailable(false);
    }

    @Test
    @DisplayName("临时故障：503 可重试且不泄露内部细节，恢复后同键重试成功且只落地一份订单")
    void temporaryFailureThenRetrySucceeds() throws Exception {
        String key = "key-" + UUID.randomUUID();
        long before = orderService.createdCount();

        // 1. 打开故障开关：请求被真实拒绝为 503，不是成功也不是重放
        orderService.setDependencyUnavailable(true);
        MvcResult failed = postJson(key, BODY).andExpect(status().isServiceUnavailable()).andReturn();
        JsonNode error = read(failed);
        log.info("[故障后重试] 故障期响应 code={}, retryable={}, message={}",
                error.get("code").asText(), error.get("retryable").asBoolean(),
                error.get("message").asText());

        assertThat(error.get("code").asText()).isEqualTo("SERVICE_UNAVAILABLE");
        assertThat(error.get("retryable").asBoolean()).isTrue();
        assertThat(error.get("message").asText()).doesNotContain("Exception").doesNotContain("依赖注入");
        assertThat(failed.getResponse().getContentAsString()).doesNotContain("at com.github");
        assertThat(orderService.createdCount() - before).isZero();

        // 2. 故障恢复：同键同内容重试，真正执行成功（键未被占死）
        orderService.setDependencyUnavailable(false);
        MvcResult retried = postJson(key, BODY).andExpect(status().isOk()).andReturn();
        JsonNode retriedBody = read(retried);
        log.info("[故障后重试] 恢复后重试 orderId={}, replayed={}, 累计创建数={}",
                retriedBody.get("orderId").asText(), retriedBody.get("replayed").asBoolean(),
                orderService.createdCount() - before);

        assertThat(retriedBody.get("replayed").asBoolean()).isFalse();
        assertThat(orderService.createdCount() - before).isEqualTo(1);

        // 3. 再次同键提交：重放首次成功结果，不产生新副作用
        MvcResult replayed = postJson(key, BODY).andExpect(status().isOk()).andReturn();
        JsonNode replayedBody = read(replayed);
        assertThat(replayedBody.get("orderId").asText()).isEqualTo(retriedBody.get("orderId").asText());
        assertThat(replayedBody.get("replayed").asBoolean()).isTrue();
        assertThat(orderService.createdCount() - before).isEqualTo(1);
        log.info("[故障后重试] 通过：故障->恢复->重试成功，全程只落地一份订单");
    }

    @Test
    @DisplayName("并发故障与重试：故障期并发请求一致 503，恢复后并发重试只落地一次且结论一致")
    void concurrentFailureThenConcurrentRetryLandsOnce() throws Exception {
        String key = "key-" + UUID.randomUUID();
        long before = orderService.createdCount();
        int threads = 12;

        // 1. 故障期间并发打进来：全部 503，零副作用
        orderService.setDependencyUnavailable(true);
        List<MvcResult> outageResults = concurrentPost(key, BODY, threads);
        long outage503 = outageResults.stream()
                .filter(r -> r.getResponse().getStatus() == 503).count();
        log.info("[并发故障重试] 故障期 {} 个并发请求中 503 数={}, 创建数={}",
                threads, outage503, orderService.createdCount() - before);
        assertThat(outage503).isEqualTo(threads);
        assertThat(orderService.createdCount() - before).isZero();

        // 2. 恢复后同一批请求并发重试：全部 200、同一 orderId、只创建一次
        orderService.setDependencyUnavailable(false);
        List<MvcResult> retryResults = concurrentPost(key, BODY, threads);
        List<String> orderIds = new ArrayList<>();
        long okCount = 0;
        for (MvcResult r : retryResults) {
            if (r.getResponse().getStatus() == 200) {
                okCount++;
                orderIds.add(read(r).get("orderId").asText());
            }
        }
        long created = orderService.createdCount() - before;
        log.info("[并发故障重试] 恢复后 200 数={}, 不同 orderId 数={}, 总创建数={}",
                okCount, orderIds.stream().distinct().count(), created);

        assertThat(okCount).isEqualTo(threads);
        assertThat(orderIds.stream().distinct()).hasSize(1);
        assertThat(created).isEqualTo(1);
        log.info("[并发故障重试] 通过：并发下失败与重试全程只落地一份订单");
    }

    @Test
    @DisplayName("三类错误可区分：400/409/503 各有明确 code 与 retryable，统一结构不泄露内部细节")
    void threeErrorKindsAreDistinguishable() throws Exception {
        // 入参不合法 -> 400 VALIDATION_ERROR, retryable=false
        MvcResult bad = postJson("key-" + UUID.randomUUID(), "{\"itemId\":\"\",\"quantity\":0}")
                .andExpect(status().isBadRequest()).andReturn();
        JsonNode badError = read(bad);

        // 同键不同内容 -> 409 IDEMPOTENCY_KEY_CONFLICT, retryable=false
        String conflictKey = "key-" + UUID.randomUUID();
        postJson(conflictKey, BODY).andExpect(status().isOk());
        MvcResult conflict = postJson(conflictKey, "{\"itemId\":\"item-2\",\"quantity\":2}")
                .andExpect(status().isConflict()).andReturn();
        JsonNode conflictError = read(conflict);

        // 依赖临时不可用 -> 503 SERVICE_UNAVAILABLE, retryable=true
        orderService.setDependencyUnavailable(true);
        MvcResult unavailable = postJson("key-" + UUID.randomUUID(), BODY)
                .andExpect(status().isServiceUnavailable()).andReturn();
        JsonNode unavailableError = read(unavailable);

        log.info("[三类错误] 400: code={}, retryable={} | 409: code={}, retryable={} | 503: code={}, retryable={}",
                badError.get("code").asText(), badError.get("retryable").asBoolean(),
                conflictError.get("code").asText(), conflictError.get("retryable").asBoolean(),
                unavailableError.get("code").asText(), unavailableError.get("retryable").asBoolean());

        assertThat(badError.get("code").asText()).isEqualTo("VALIDATION_ERROR");
        assertThat(badError.get("retryable").asBoolean()).isFalse();
        assertThat(conflictError.get("code").asText()).isEqualTo("IDEMPOTENCY_KEY_CONFLICT");
        assertThat(conflictError.get("retryable").asBoolean()).isFalse();
        assertThat(unavailableError.get("code").asText()).isEqualTo("SERVICE_UNAVAILABLE");
        assertThat(unavailableError.get("retryable").asBoolean()).isTrue();

        // 统一结构：都有 code/message/retryable/timestamp，且不含内部异常细节
        for (MvcResult r : List.of(bad, conflict, unavailable)) {
            JsonNode body = read(r);
            assertThat(body.has("code")).isTrue();
            assertThat(body.has("message")).isTrue();
            assertThat(body.has("retryable")).isTrue();
            assertThat(body.has("timestamp")).isTrue();
            assertThat(r.getResponse().getContentAsString())
                    .doesNotContain("Exception").doesNotContain("at com.github");
        }
        log.info("[三类错误] 通过：三类错误码与 retryable 清晰可区分，结构统一无内部细节");
    }

    @Test
    @DisplayName("故障期间：首个请求在途时同键不同内容仍按 409 冲突拒绝")
    void conflictStillRejectedDuringOutage() throws Exception {
        String key = "key-" + UUID.randomUUID();

        // 首个请求在途（create-delay-ms=300 提供窗口），执行期间打开故障开关
        ExecutorService pool = Executors.newSingleThreadExecutor();
        Future<MvcResult> inFlight = pool.submit(() -> postJson(key, BODY).andReturn());
        Thread.sleep(100); // 确保首个请求已进入执行窗口
        orderService.setDependencyUnavailable(true);

        // 同键不同内容：即使服务正临时不可用，也按冲突拒绝而非放行
        MvcResult conflict = postJson(key, "{\"itemId\":\"item-2\",\"quantity\":2}")
                .andExpect(status().isConflict()).andReturn();
        JsonNode conflictError = read(conflict);
        log.info("[故障期冲突] 在途期间同键不同内容: code={}, retryable={}",
                conflictError.get("code").asText(), conflictError.get("retryable").asBoolean());
        assertThat(conflictError.get("code").asText()).isEqualTo("IDEMPOTENCY_KEY_CONFLICT");

        // 在途的首个请求因故障失败为 503；恢复后同键同内容仍可重试成功
        MvcResult first = inFlight.get(10, TimeUnit.SECONDS);
        pool.shutdown();
        log.info("[故障期冲突] 在途首个请求状态={}", first.getResponse().getStatus());
        assertThat(first.getResponse().getStatus()).isEqualTo(503);

        orderService.setDependencyUnavailable(false);
        postJson(key, BODY).andExpect(status().isOk());
        log.info("[故障期冲突] 通过：故障期冲突检测不失效，恢复后同键可重试");
    }

    private List<MvcResult> concurrentPost(String key, String body, int threads) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch ready = new CountDownLatch(threads);
        CountDownLatch go = new CountDownLatch(1);
        List<Future<MvcResult>> futures = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            futures.add(pool.submit(() -> {
                ready.countDown();
                go.await();
                return postJson(key, body).andReturn();
            }));
        }
        ready.await();
        go.countDown();
        List<MvcResult> results = new ArrayList<>();
        for (Future<MvcResult> f : futures) {
            results.add(f.get(30, TimeUnit.SECONDS));
        }
        pool.shutdown();
        return results;
    }

    private ResultActions postJson(String key, String body) throws Exception {
        return mockMvc.perform(post(URL)
                .header("Idempotency-Key", key)
                .contentType(MediaType.APPLICATION_JSON).content(body));
    }

    private JsonNode read(MvcResult result) throws Exception {
        return objectMapper.readTree(result.getResponse().getContentAsString());
    }
}
