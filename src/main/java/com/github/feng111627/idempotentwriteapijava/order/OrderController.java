package com.github.feng111627.idempotentwriteapijava.order;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

import com.github.feng111627.idempotentwriteapijava.idempotency.IdempotencyService;
import com.github.feng111627.idempotentwriteapijava.idempotency.IdempotencyService.IdempotentResult;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 订单写接口。支持可选的 Idempotency-Key 头；不带键时保持旧的调用行为（直接执行）。
 */
@RestController
@RequestMapping("/api/orders")
public class OrderController {

    private final OrderService orderService;
    private final IdempotencyService idempotencyService;

    public OrderController(OrderService orderService, IdempotencyService idempotencyService) {
        this.orderService = orderService;
        this.idempotencyService = idempotencyService;
    }

    @PostMapping
    public ResponseEntity<OrderResponse> create(
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            @Validated @RequestBody OrderRequest request) {

        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            // 兼容旧调用：无幂等键时直接执行
            return ResponseEntity.ok(orderService.createOrder(request));
        }

        IdempotentResult<OrderResponse> result = idempotencyService.execute(
                idempotencyKey, fingerprint(request), () -> orderService.createOrder(request));

        OrderResponse response = result.value();
        if (result.replayed()) {
            // 重放：返回首次结果，并标记为重放
            response = new OrderResponse(response.orderId(), response.itemId(),
                    response.quantity(), true);
        }
        return ResponseEntity.ok(response);
    }

    /** 请求内容指纹：对规范化后的内容做 SHA-256，用于检测同键不同内容 */
    static String fingerprint(OrderRequest request) {
        String canonical = request.itemId() + "\n" + request.quantity();
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(canonical.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
