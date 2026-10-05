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

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 订单写接口的幂等性集成测试。
 */
@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = "order.create-delay-ms=100")
class OrderApiTests {

    private static final Logger log = LoggerFactory.getLogger(OrderApiTests.class);
    private static final String URL = "/api/orders";
    private static final String BODY = "{\"itemId\":\"item-1\",\"quantity\":2}";

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private ObjectMapper objectMapper;
    @Autowired
    private OrderService orderService;

    @Test
    @DisplayName("重复提交：同键同内容只生效一次，返回首次结果")
    void duplicateSubmitReturnsFirstResult() throws Exception {
        String key = "key-" + UUID.randomUUID();
        long before = orderService.createdCount();

        MvcResult first = postJson(key, BODY).andExpect(status().isOk()).andReturn();
        MvcResult second = postJson(key, BODY).andExpect(status().isOk()).andReturn();

        JsonNode firstBody = read(first);
        JsonNode secondBody = read(second);
        log.info("[重复提交] 首次 orderId={}, 重放 orderId={}, 实际创建数={}",
                firstBody.get("orderId").asText(), secondBody.get("orderId").asText(),
                orderService.createdCount() - before);

        assertThat(secondBody.get("orderId").asText()).isEqualTo(firstBody.get("orderId").asText());
        assertThat(firstBody.get("replayed").asBoolean()).isFalse();
        assertThat(secondBody.get("replayed").asBoolean()).isTrue();
        assertThat(orderService.createdCount() - before).isEqualTo(1);
        log.info("[重复提交] 通过：副作用仅发生一次，重放返回首次结果");
    }

    @Test
    @DisplayName("同键不同内容：明确拒绝并返回可区分的错误码")
    void sameKeyDifferentContentRejected() throws Exception {
        String key = "key-" + UUID.randomUUID();
        postJson(key, BODY).andExpect(status().isOk());

        MvcResult conflict = postJson(key, "{\"itemId\":\"item-2\",\"quantity\":2}")
                .andExpect(status().isConflict()).andReturn();
        JsonNode error = read(conflict);
        log.info("[同键不同内容] 返回错误码={}, 消息={}",
                error.get("code").asText(), error.get("message").asText());

        assertThat(error.get("code").asText()).isEqualTo("IDEMPOTENCY_KEY_CONFLICT");
        log.info("[同键不同内容] 通过：返回 409 与 IDEMPOTENCY_KEY_CONFLICT");
    }

    @Test
    @DisplayName("并发重放：同键并发请求只执行一次，全部拿到同一结果")
    void concurrentReplayExecutesOnce() throws Exception {
        String key = "key-" + UUID.randomUUID();
        long before = orderService.createdCount();
        int threads = 16;

        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch ready = new CountDownLatch(threads);
        CountDownLatch go = new CountDownLatch(1);
        List<Future<MvcResult>> futures = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            futures.add(pool.submit(() -> {
                ready.countDown();
                go.await();
                return postJson(key, BODY).andExpect(status().isOk()).andReturn();
            }));
        }
        ready.await();
        go.countDown();

        List<String> orderIds = new ArrayList<>();
        for (Future<MvcResult> f : futures) {
            orderIds.add(read(f.get(30, TimeUnit.SECONDS)).get("orderId").asText());
        }
        pool.shutdown();

        long created = orderService.createdCount() - before;
        log.info("[并发重放] 并发数={}, 不同 orderId 数={}, 实际创建数={}",
                threads, orderIds.stream().distinct().count(), created);

        assertThat(orderIds.stream().distinct()).hasSize(1);
        assertThat(created).isEqualTo(1);
        log.info("[并发重放] 通过：{} 个并发请求只产生一次副作用", threads);
    }

    @Test
    @DisplayName("非法入参：返回统一的 VALIDATION_ERROR")
    void invalidInputReturnsValidationError() throws Exception {
        MvcResult result = postJson("key-" + UUID.randomUUID(), "{\"itemId\":\"\",\"quantity\":0}")
                .andExpect(status().isBadRequest()).andReturn();
        JsonNode error = read(result);
        log.info("[非法入参] 返回错误码={}, 消息={}",
                error.get("code").asText(), error.get("message").asText());

        assertThat(error.get("code").asText()).isEqualTo("VALIDATION_ERROR");
        log.info("[非法入参] 通过：返回 400 与 VALIDATION_ERROR");
    }

    @Test
    @DisplayName("旧调用兼容：不带幂等键时按原行为直接执行")
    void legacyCallWithoutKeyStillWorks() throws Exception {
        long before = orderService.createdCount();

        MvcResult r1 = mockMvc.perform(post(URL)
                        .contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isOk()).andReturn();
        MvcResult r2 = mockMvc.perform(post(URL)
                        .contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isOk()).andReturn();

        log.info("[旧调用兼容] 两次无键调用 orderId 不同={}, 创建数={}",
                !read(r1).get("orderId").asText().equals(read(r2).get("orderId").asText()),
                orderService.createdCount() - before);

        assertThat(read(r1).get("replayed").asBoolean()).isFalse();
        assertThat(read(r1).get("orderId").asText()).isNotEqualTo(read(r2).get("orderId").asText());
        assertThat(orderService.createdCount() - before).isEqualTo(2);
        log.info("[旧调用兼容] 通过：无幂等键时保持原有直接执行行为");
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
