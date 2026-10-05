package com.github.feng111627.idempotentwriteapijava.order;

import com.github.feng111627.idempotentwriteapijava.api.CreateOrderRequest;
import com.github.feng111627.idempotentwriteapijava.api.OrderResponse;
import com.github.feng111627.idempotentwriteapijava.idempotency.IdempotencyService;
import com.github.feng111627.idempotentwriteapijava.idempotency.RequestFingerprint;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 订单写接口。
 * <p>
 * 兼容已有调用方式：不带 Idempotency-Key 时行为与原来一致（每次调用都创建新订单）；
 * 带 Idempotency-Key 时按幂等语义处理：同键同内容只生效一次，同键不同内容明确拒绝。
 */
@RestController
@RequestMapping("/api/orders")
public class OrderController {

    public static final String IDEMPOTENCY_KEY_HEADER = "Idempotency-Key";

    private static final Logger log = LoggerFactory.getLogger(OrderController.class);

    private final OrderService orderService;
    private final IdempotencyService idempotencyService;

    public OrderController(OrderService orderService, IdempotencyService idempotencyService) {
        this.orderService = orderService;
        this.idempotencyService = idempotencyService;
    }

    @PostMapping
    public ResponseEntity<OrderResponse> createOrder(
            @RequestHeader(name = IDEMPOTENCY_KEY_HEADER, required = false) String idempotencyKey,
            @RequestBody(required = false) CreateOrderRequest request) {

        if (idempotencyKey == null || idempotencyKey.isBlank() || request == null) {
            // 无幂等键（或请求体为空，交给业务层做参数校验）：保持原有调用行为
            OrderResponse response = orderService.createOrder(request);
            return ResponseEntity.status(HttpStatus.CREATED).body(response);
        }

        log.info("idempotent create-order request received, key={}", idempotencyKey);
        OrderResponse response = idempotencyService.execute(
                idempotencyKey, RequestFingerprint.of(request), () -> orderService.createOrder(request));
        // 重放请求返回 200 与首次相同的结果；首次执行返回 201
        HttpStatus status = response.replayed() ? HttpStatus.OK : HttpStatus.CREATED;
        return ResponseEntity.status(status).body(response);
    }
}
