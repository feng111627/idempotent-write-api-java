package com.github.feng111627.idempotentwriteapijava.order;

import com.github.feng111627.idempotentwriteapijava.api.CreateOrderRequest;
import com.github.feng111627.idempotentwriteapijava.api.OrderResponse;
import com.github.feng111627.idempotentwriteapijava.error.ApiException;
import com.github.feng111627.idempotentwriteapijava.error.ErrorCode;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.util.UUID;

/**
 * 订单业务逻辑：真正产生副作用的地方（落库）。
 */
@Service
public class OrderService {

    /** 用于本地复现"服务暂时处理不了"的特殊商品号。 */
    public static final String UNAVAILABLE_PRODUCT = "UNAVAILABLE";

    private final OrderRepository orderRepository;

    public OrderService(OrderRepository orderRepository) {
        this.orderRepository = orderRepository;
    }

    public OrderResponse createOrder(CreateOrderRequest request) {
        validate(request);

        if (UNAVAILABLE_PRODUCT.equals(request.productId())) {
            // 模拟下游暂时不可用，用于验证统一的 503 错误返回
            throw new ApiException(ErrorCode.SERVICE_UNAVAILABLE, HttpStatus.SERVICE_UNAVAILABLE,
                    "service temporarily unable to process the request, please retry later");
        }

        Order order = new Order(UUID.randomUUID().toString(), request.productId(), request.quantity(), "CREATED");
        orderRepository.save(order);
        return new OrderResponse(order.getOrderId(), order.getProductId(), order.getQuantity(), order.getStatus(), false);
    }

    private void validate(CreateOrderRequest request) {
        if (request == null) {
            throw invalid("request body must not be empty");
        }
        if (request.productId() == null || request.productId().isBlank()) {
            throw invalid("productId must not be blank");
        }
        if (request.quantity() == null || request.quantity() <= 0) {
            throw invalid("quantity must be a positive integer");
        }
    }

    private ApiException invalid(String message) {
        return new ApiException(ErrorCode.INVALID_ARGUMENT, HttpStatus.BAD_REQUEST, message);
    }
}
