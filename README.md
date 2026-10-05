# idempotent-write-api-java

带幂等写能力的订单服务（Spring Boot 3 / Java 21）。

## 写接口

```
POST /api/orders
Content-Type: application/json
Idempotency-Key: <可选，客户端生成的唯一键>

{"productId": "SKU-1", "quantity": 2}
```

成功响应：

```json
{"orderId": "...", "productId": "SKU-1", "quantity": 2, "status": "CREATED", "replayed": false}
```

- 首次真正执行返回 `201 Created`，`replayed=false`。
- 幂等重放返回 `200 OK`，`replayed=true`，其余字段与首次完全一致。

## 幂等语义

- **同键同内容**：同一个 `Idempotency-Key` 携带相同请求体重复提交时，副作用只生效一次；
  后续请求拿到的是首次执行的结果（`replayed=true`），不会产生第二份数据。
  内容一致性通过请求体的规范化指纹（SHA-256）判定。
- **同键不同内容**：明确拒绝，返回 `409 IDEMPOTENCY_KEY_CONFLICT`，不会当成新请求执行。
- **首次执行失败**：失败不会烧掉幂等键。键会被释放，客户端可用同一个键重试
  （例如修正请求内容后重试）。
- **不带幂等键**：保持既有调用行为不变——每次调用都创建新订单，老客户端无需改造。

## 并发下的保证

同一个键的多个请求几乎同时到达时，只有抢到键的那一个请求会真正执行；
其余同内容请求会等待首次执行完成并重放其结果，不会产生第二份副作用。
同键不同内容的并发请求会被拒绝（409）。

该保证由 `IdempotencyService` 中的原子占位（`ConcurrentHashMap.putIfAbsent`）
加结果共享（`CompletableFuture`）实现，可用并发测试复现：

```
mvn test -Dtest=OrderApiTests#concurrentReplayWithSameKeyExecutesOnlyOnce
```

16 个线程同键同内容同时提交，断言：全部 2xx、首次执行恰好 1 次、
重放 15 次、orderId 唯一、落库订单数为 1。

> 说明：当前幂等记录与订单存储均为内存实现，面向本地复现与测试；
> 生产化时可替换为带唯一约束的持久化存储，语义保持不变。

## 错误返回约定

所有错误统一返回 `{code, message}`，不携带内部异常类型、堆栈等细节
（内部细节只记录在服务端日志中）。

| HTTP | code | 含义 |
|------|------|------|
| 400 | `INVALID_ARGUMENT` | 入参非法（缺字段、quantity 非正整数、请求体缺失或格式错误） |
| 409 | `IDEMPOTENCY_KEY_CONFLICT` | 同一幂等键被用于不同的请求内容 |
| 503 | `SERVICE_UNAVAILABLE` | 服务暂时处理不了，可稍后重试（幂等键不会被烧掉） |
| 500 | `INTERNAL_ERROR` | 未预期的内部错误 |

## 测试

```
mvn test
```

`OrderApiTests` 覆盖：重复提交、同键不同内容、并发重放、非法入参、
服务暂不可用（含失败后同键重试）、无幂等键的兼容行为。
每个用例的日志都会打印场景与结果（如 `[并发重放] 结果：通过。...`）。
