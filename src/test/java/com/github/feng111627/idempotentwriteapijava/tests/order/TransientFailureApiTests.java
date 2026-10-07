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
import com.github.feng111627.idempotentwriteapijava.dependency.OrderDependencyGateway;
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
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 依赖临时不可用（可恢复失败）链路的集成测试。
 * 通过 {@link OrderDependencyGateway} 在本地稳定制造故障，不依赖任何外部服务。
 */
@SpringBootTest
@AutoConfigureMockMvc
class TransientFailureApiTests {

    private static final Logger log = LoggerFactory.getLogger(TransientFailureApiTests.class);
    private static final String URL = "/api/orders";
    private static final String BODY = "{\"itemId\":\"item-1\",\"quantity\":2}";
    private static final String OTHER_BODY = "{\"itemId\":\"item-2\",\"quantity\":2}";

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private ObjectMapper objectMapper;
    @Autowired
    private OrderService orderService;
    @Autowired
    private OrderDependencyGateway dependencyGateway;

    @AfterEach
    void recoverDependency() {
        dependencyGateway.recover();
    }

    @Test
    @DisplayName("临时故障后同键重试：失败不算成功，重试真正执行且只落地一份订单")
    void transientFailureThenRetrySucceeds() throws Exception {
        String key = "key-" + UUID.randomUUID();
        long createdBefore = orderService.createdCount();
        long failuresBefore = dependencyGateway.failureCount();

        // 1. 制造临时故障：本次请求返回 503，不算成功、不算重复提交
        dependencyGateway.simulateOutage();
        MvcResult failure = postJson(key, BODY)
                .andExpect(status().isServiceUnavailable())
                .andExpect(header().exists("Retry-After"))
                .andReturn();
        JsonNode error = read(failure);
        long createdAfterFailure = orderService.createdCount();
        log.info("[临时故障重试] 故障期响应: status=503, code={}, retryable={}, 创建数变化={}",
                error.get("code").asText(), error.get("retryable").asBoolean(),
                createdAfterFailure - createdBefore);

        assertThat(error.get("code").asText()).isEqualTo("SERVICE_UNAVAILABLE");
        assertThat(error.get("retryable").asBoolean()).isTrue();
        assertThat(createdAfterFailure - createdBefore).isZero();
        assertThat(dependencyGateway.failureCount() - failuresBefore).isEqualTo(1);

        // 2. 依赖恢复后同键同内容重试：真正执行成功，返回真实结果
        dependencyGateway.recover();
        MvcResult retry = postJson(key, BODY).andExpect(status().isOk()).andReturn();
        JsonNode retryBody = read(retry);
        log.info("[临时故障重试] 重试结果: orderId={}, replayed={}, 创建数变化={}",
                retryBody.get("orderId").asText(), retryBody.get("replayed").asBoolean(),
                orderService.createdCount() - createdBefore);

        assertThat(retryBody.get("replayed").asBoolean()).isFalse();
        assertThat(orderService.createdCount() - createdBefore).isEqualTo(1);

        // 3. 再次重复提交：重放首次成功结果，整条链路只落地一份订单
        MvcResult replay = postJson(key, BODY).andExpect(status().isOk()).andReturn();
        JsonNode replayBody = read(replay);
        log.info("[临时故障重试] 重放结果: orderId={}, replayed={}, 总创建数={}",
                replayBody.get("orderId").asText(), replayBody.get("replayed").asBoolean(),
                orderService.createdCount() - createdBefore);

        assertThat(replayBody.get("orderId").asText()).isEqualTo(retryBody.get("orderId").asText());
        assertThat(replayBody.get("replayed").asBoolean()).isTrue();
        assertThat(orderService.createdCount() - createdBefore).isEqualTo(1);
        log.info("[临时故障重试] 通过：故障未占死键，重试成功且副作用仅一份");
    }

    @Test
    @DisplayName("并发下故障与重试：同键并发请求全部得到一致结论，最终只落地一份副作用")
    void concurrentFailureAndRetryLandsOnce() throws Exception {
        String key = "key-" + UUID.randomUUID();
        long createdBefore = orderService.createdCount();
        int threads = 12;

        // 1. 故障期间并发打入：全部得到 503，零副作用
        dependencyGateway.simulateOutage();
        List<MvcResult> failures = fireConcurrently(key, threads);
        long unavailable = failures.stream()
                .filter(r -> r.getResponse().getStatus() == 503).count();
        log.info("[并发故障重试] 故障期并发数={}, 503 数={}, 创建数变化={}",
                threads, unavailable, orderService.createdCount() - createdBefore);

        assertThat(unavailable).isEqualTo(threads);
        assertThat(orderService.createdCount() - createdBefore).isZero();

        // 2. 恢复后同键并发重试：全部 200 且 orderId 一致，副作用仅一份
        dependencyGateway.recover();
        List<MvcResult> retries = fireConcurrently(key, threads);
        List<String> orderIds = new ArrayList<>();
        for (MvcResult r : retries) {
            assertThat(r.getResponse().getStatus()).isEqualTo(200);
            orderIds.add(read(r).get("orderId").asText());
        }
        long created = orderService.createdCount() - createdBefore;
        log.info("[并发故障重试] 重试并发数={}, 不同 orderId 数={}, 实际创建数={}",
                threads, orderIds.stream().distinct().count(), created);

        assertThat(orderIds.stream().distinct()).hasSize(1);
        assertThat(created).isEqualTo(1);
        log.info("[并发故障重试] 通过：并发故障结论一致，重试后副作用仅一份");
    }

    @Test
    @DisplayName("三类错误可区分：400/409/503 结构统一、retryable 明确、不泄露内部细节")
    void threeErrorCategoriesAreDistinguishable() throws Exception {
        // 1. 入参不合法 -> 400 VALIDATION_ERROR, 不可重试
        MvcResult validation = postJson("key-" + UUID.randomUUID(),
                "{\"itemId\":\"\",\"quantity\":0}")
                .andExpect(status().isBadRequest()).andReturn();
        JsonNode validationError = read(validation);

        // 2. 同键不同内容 -> 409 IDEMPOTENCY_KEY_CONFLICT, 不可重试
        String conflictKey = "key-" + UUID.randomUUID();
        postJson(conflictKey, BODY).andExpect(status().isOk());
        MvcResult conflict = postJson(conflictKey, OTHER_BODY)
                .andExpect(status().isConflict()).andReturn();
        JsonNode conflictError = read(conflict);

        // 3. 依赖临时不可用 -> 503 SERVICE_UNAVAILABLE, 可重试
        dependencyGateway.simulateOutage();
        MvcResult unavailable = postJson("key-" + UUID.randomUUID(), BODY)
                .andExpect(status().isServiceUnavailable()).andReturn();
        JsonNode unavailableError = read(unavailable);

        log.info("[错误分类] 400: code={}, retryable={}; 409: code={}, retryable={}; 503: code={}, retryable={}",
                validationError.get("code").asText(), validationError.get("retryable").asBoolean(),
                conflictError.get("code").asText(), conflictError.get("retryable").asBoolean(),
                unavailableError.get("code").asText(), unavailableError.get("retryable").asBoolean());

        // 统一结构：都有 code/message/retryable/timestamp
        for (JsonNode error : List.of(validationError, conflictError, unavailableError)) {
            assertThat(error.has("code")).isTrue();
            assertThat(error.has("message")).isTrue();
            assertThat(error.has("retryable")).isTrue();
            assertThat(error.has("timestamp")).isTrue();
            // 不泄露内部异常细节
            assertThat(error.get("message").asText())
                    .doesNotContain("Exception").doesNotContain("com.github");
        }
        // 三类可区分
        assertThat(validationError.get("code").asText()).isEqualTo("VALIDATION_ERROR");
        assertThat(validationError.get("retryable").asBoolean()).isFalse();
        assertThat(conflictError.get("code").asText()).isEqualTo("IDEMPOTENCY_KEY_CONFLICT");
        assertThat(conflictError.get("retryable").asBoolean()).isFalse();
        assertThat(unavailableError.get("code").asText()).isEqualTo("SERVICE_UNAVAILABLE");
        assertThat(unavailableError.get("retryable").asBoolean()).isTrue();
        log.info("[错误分类] 通过：三类错误码与 retryable 明确区分，无内部细节泄露");
    }

    @Test
    @DisplayName("临时不可用期间同键不同内容：仍按冲突拒绝，不放行为新请求")
    void conflictStillRejectedDuringOutage() throws Exception {
        String key = "key-" + UUID.randomUUID();
        long createdBefore = orderService.createdCount();

        dependencyGateway.simulateOutage();
        // 第一次请求因故障失败（键已释放，但内容指纹保留）
        postJson(key, BODY).andExpect(status().isServiceUnavailable());
        // 故障期间同键不同内容：必须按冲突拒绝，而不是当成新请求
        MvcResult conflict = postJson(key, OTHER_BODY)
                .andExpect(status().isConflict()).andReturn();
        JsonNode error = read(conflict);
        log.info("[故障期冲突] code={}, retryable={}, 创建数变化={}",
                error.get("code").asText(), error.get("retryable").asBoolean(),
                orderService.createdCount() - createdBefore);

        assertThat(error.get("code").asText()).isEqualTo("IDEMPOTENCY_KEY_CONFLICT");
        assertThat(orderService.createdCount() - createdBefore).isZero();

        // 恢复后：用正确内容重试仍可成功（指纹保留不影响同内容重试）
        dependencyGateway.recover();
        postJson(key, BODY).andExpect(status().isOk());
        assertThat(orderService.createdCount() - createdBefore).isEqualTo(1);
        log.info("[故障期冲突] 通过：故障期冲突仍拒绝，恢复后同内容重试成功");
    }

    private List<MvcResult> fireConcurrently(String key, int threads) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch ready = new CountDownLatch(threads);
        CountDownLatch go = new CountDownLatch(1);
        List<Future<MvcResult>> futures = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            futures.add(pool.submit(() -> {
                ready.countDown();
                go.await();
                return postJson(key, BODY).andReturn();
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

    private org.springframework.test.web.servlet.ResultActions postJson(String key, String body)
            throws Exception {
        return mockMvc.perform(post(URL)
                .header("Idempotency-Key", key)
                .contentType(MediaType.APPLICATION_JSON).content(body));
    }

    private JsonNode read(MvcResult result) throws Exception {
        return objectMapper.readTree(result.getResponse().getContentAsString());
    }
}
