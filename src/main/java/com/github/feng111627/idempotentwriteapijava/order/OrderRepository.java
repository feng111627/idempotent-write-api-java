package com.github.feng111627.idempotentwriteapijava.order;

import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 订单存储（内存实现，便于本地复现与测试）。
 */
@Repository
public class OrderRepository {

    private final List<Order> orders = new CopyOnWriteArrayList<>();

    public Order save(Order order) {
        orders.add(order);
        return order;
    }

    public List<Order> findAll() {
        return List.copyOf(orders);
    }

    public long count() {
        return orders.size();
    }

    /** 仅供测试隔离使用。 */
    public void clear() {
        orders.clear();
    }
}
