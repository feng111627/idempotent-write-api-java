package com.github.feng111627.idempotentwriteapijava.order;

/**
 * 订单实体。
 */
public class Order {

    private final String orderId;
    private final String productId;
    private final int quantity;
    private final String status;

    public Order(String orderId, String productId, int quantity, String status) {
        this.orderId = orderId;
        this.productId = productId;
        this.quantity = quantity;
        this.status = status;
    }

    public String getOrderId() {
        return orderId;
    }

    public String getProductId() {
        return productId;
    }

    public int getQuantity() {
        return quantity;
    }

    public String getStatus() {
        return status;
    }
}
