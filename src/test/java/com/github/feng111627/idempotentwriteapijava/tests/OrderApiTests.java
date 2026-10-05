package com.github.feng111627.idempotentwriteapijava.tests;

import com.github.feng111627.idempotentwriteapijava.api.CreateOrderRequest;
import com.github.feng111627.idempotentwriteapijava.api.ErrorResponse;
import com.github.feng111627.idempotentwriteapijava.api.OrderResponse;
import com.github.feng111627.idempotentwriteapijava.error.ErrorCode;
import com.github.feng111627.idempotentwriteapijava.order.OrderController;
import com.github.feng111627.idempotentwriteapijava.order.OrderRepository;
import com.github.feng111627.idempotentwriteapijava.order.OrderService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 订单写接口的幂等性测试：覆盖重复提交、同键不同内容、并发重放、
 * 非法入参、服务暂不可用以及无幂等键的兼容行为。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class OrderApiTests {

    private static final Logger log = LoggerFactory.getLogger(OrderApiTests.class);

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private OrderRepository orderRepository;

    @BeforeEach
    void setUp() {
        orderRepository.clear();
    }

    @Test
    void duplicateSubmitWithSameKeyAndPayloadTakesEffectOnlyOnce() {
        String key = newKey();
        log.info("[重复提交] 场景：同键同内容提交两次，key={}", key);

        ResponseEntity<OrderResponse> first = postOrder(key, new CreateOrderRequest("SKU-1", 2));
        ResponseEntity<OrderResponse> second = postOrder(key, new CreateOrderRequest("SKU-1", 2));

        assertThat(first.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(second.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(second.getBody()).isNotNull();
        assertThat(first.getBody()).isNotNull();
        assertThat(second.getBody().orderId()).isEqualTo(first.getBody().orderId());
        assertThat(first.getBody().replayed()).isFalse();
        assertThat(second.getBody().replayed()).isTrue();
        assertThat(orderRepository.count()).isEqualTo(1);
        log.info("[重复提交] 结果：通过。两次返回同一 orderId={}，实际落库订单数={}",
                first.getBody().orderId(), orderRepository.count());
    }

    @Test
    void sameKeyWithDifferentPayloadIsRejected() {
        String key = newKey();
        log.info("[同键不同内容] 场景：同键但内容对不上，key={}", key);

        ResponseEntity<OrderResponse> first = postOrder(key, new CreateOrderRequest("SKU-1", 2));
        ResponseEntity<ErrorResponse> conflict = postOrderForError(key, new CreateOrderRequest("SKU-1", 99));

        assertThat(first.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(conflict.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(conflict.getBody()).isNotNull();
        assertThat(conflict.getBody().code()).isEqualTo(ErrorCode.IDEMPOTENCY_KEY_CONFLICT.name());
        assertThat(orderRepository.count()).isEqualTo(1);
        log.info("[同键不同内容] 结果：通过。返回 {} {}，未产生第二份副作用，落库订单数={}",
                conflict.getStatusCode().value(), conflict.getBody().code(), orderRepository.count());
    }

    @Test
    void concurrentReplayWithSameKeyExecutesOnlyOnce() throws Exception {
        String key = newKey();
        int threads = 16;
        log.info("[并发重放] 场景：{} 个线程同键同内容同时提交，key={}", threads, key);

        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch ready = new CountDownLatch(threads);
        CountDownLatch fire = new CountDownLatch(1);
        List<Future<ResponseEntity<OrderResponse>>> futures = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            futures.add(pool.submit(() -> {
                ready.countDown();
                fire.await();
                return postOrder(key, new CreateOrderRequest("SKU-CONCURRENT", 1));
            }));
        }
        assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
        fire.countDown();

        Set<String> orderIds = ConcurrentHashMap.newKeySet();
        int created = 0;
        int replayed = 0;
        for (Future<ResponseEntity<OrderResponse>> future : futures) {
            ResponseEntity<OrderResponse> response = future.get(30, TimeUnit.SECONDS);
            assertThat(response.getStatusCode().is2xxSuccessful()).isTrue();
            assertThat(response.getBody()).isNotNull();
            orderIds.add(response.getBody().orderId());
            if (response.getBody().replayed()) {
                replayed++;
            } else {
                created++;
            }
        }
        pool.shutdown();

        assertThat(orderIds).hasSize(1);
        assertThat(created).isEqualTo(1);
        assertThat(replayed).isEqualTo(threads - 1);
        assertThat(orderRepository.count()).isEqualTo(1);
        log.info("[并发重放] 结果：通过。{} 个并发请求 -> 首次执行 {} 次、重放 {} 次、唯一 orderId={}、落库订单数={}",
                threads, created, replayed, orderIds.iterator().next(), orderRepository.count());
    }

    @Test
    void invalidPayloadReturnsUnifiedBadRequest() {
        String key = newKey();
        log.info("[非法入参] 场景：quantity 非法，key={}", key);

        ResponseEntity<ErrorResponse> response = postOrderForError(key, new CreateOrderRequest("SKU-1", 0));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().code()).isEqualTo(ErrorCode.INVALID_ARGUMENT.name());
        assertThat(orderRepository.count()).isZero();
        log.info("[非法入参] 结果：通过。返回 {} {}，落库订单数={}",
                response.getStatusCode().value(), response.getBody().code(), orderRepository.count());
    }

    @Test
    void unavailableServiceReturnsUnifiedErrorWithoutInternalDetailsAndKeyIsReleasedForRetry() {
        String key = newKey();
        log.info("[服务暂不可用] 场景：下游暂时处理不了，key={}", key);

        ResponseEntity<ErrorResponse> unavailable =
                postOrderForError(key, new CreateOrderRequest(OrderService.UNAVAILABLE_PRODUCT, 1));

        assertThat(unavailable.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        assertThat(unavailable.getBody()).isNotNull();
        assertThat(unavailable.getBody().code()).isEqualTo(ErrorCode.SERVICE_UNAVAILABLE.name());
        assertThat(unavailable.getBody().message()).doesNotContain("Exception").doesNotContain("at com.");
        assertThat(orderRepository.count()).isZero();

        // 首次执行失败不烧掉幂等键：同一个键修正内容后可以重试成功
        ResponseEntity<OrderResponse> retry = postOrder(key, new CreateOrderRequest("SKU-1", 1));
        assertThat(retry.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(orderRepository.count()).isEqualTo(1);
        log.info("[服务暂不可用] 结果：通过。返回 {} {} 且无内部细节；同键重试成功，落库订单数={}",
                unavailable.getStatusCode().value(), unavailable.getBody().code(), orderRepository.count());
    }

    @Test
    void requestWithoutIdempotencyKeyKeepsLegacyBehavior() {
        log.info("[兼容老调用] 场景：不带幂等键，每次调用都创建新订单");

        ResponseEntity<OrderResponse> first = postOrder(null, new CreateOrderRequest("SKU-1", 1));
        ResponseEntity<OrderResponse> second = postOrder(null, new CreateOrderRequest("SKU-1", 1));

        assertThat(first.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(second.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(first.getBody()).isNotNull();
        assertThat(second.getBody()).isNotNull();
        assertThat(second.getBody().orderId()).isNotEqualTo(first.getBody().orderId());
        assertThat(orderRepository.count()).isEqualTo(2);
        log.info("[兼容老调用] 结果：通过。两次调用生成两个不同订单，落库订单数={}", orderRepository.count());
    }

    private String newKey() {
        return "test-key-" + UUID.randomUUID();
    }

    private ResponseEntity<OrderResponse> postOrder(String idempotencyKey, CreateOrderRequest request) {
        return restTemplate.postForEntity("/api/orders", entity(idempotencyKey, request), OrderResponse.class);
    }

    private ResponseEntity<ErrorResponse> postOrderForError(String idempotencyKey, CreateOrderRequest request) {
        return restTemplate.postForEntity("/api/orders", entity(idempotencyKey, request), ErrorResponse.class);
    }

    private HttpEntity<CreateOrderRequest> entity(String idempotencyKey, CreateOrderRequest request) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        if (idempotencyKey != null) {
            headers.set(OrderController.IDEMPOTENCY_KEY_HEADER, idempotencyKey);
        }
        return new HttpEntity<>(request, headers);
    }
}
