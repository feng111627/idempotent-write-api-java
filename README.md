# idempotent-write-api-java

带幂等写能力的订单服务（Spring Boot 3 / Java 21）。

## 写接口

```
POST /api/orders
Idempotency-Key: <客户端生成的唯一键>   # 可选
Content-Type: application/json

{"itemId": "item-1", "quantity": 2}
```

成功响应 `200 OK`：

```json
{"orderId": "...", "itemId": "item-1", "quantity": 2, "replayed": false}
```

`replayed=false` 表示本次请求真实执行；`replayed=true` 表示本次是重放，返回的是首次执行的结果。

## 幂等语义

- **同键同内容重复提交**：只真实执行一次。后续相同请求返回首次的结果（`orderId` 相同），并标记 `replayed=true`，不会产生第二份副作用。
- **同键不同内容**：明确拒绝，返回 `409` 与错误码 `IDEMPOTENCY_KEY_CONFLICT`，不会当成新请求执行。内容是否一致按请求体的规范化指纹（SHA-256）判定。
- **不带幂等键**：保持旧的调用行为，每次请求都直接执行，互不影响。
- **执行失败**：首个请求执行失败时会释放该幂等键，客户端修正问题后可用同一键重试。

## 并发下的保证

同一幂等键的多个请求同时到达时，内部通过 `ConcurrentHashMap.putIfAbsent` 抢占键的所有权：

- 抢到所有权的请求执行真实操作；
- 其余请求等待同一个结果（最长 10 秒），随后拿到与首个请求完全相同的响应；
- 因此无论并发多少，副作用只发生一次，且所有并发请求的结果一致。

等待超时或首个请求失败时，等待中的请求会收到 `503 SERVICE_UNAVAILABLE`，可稍后重试。

## 错误返回约定

所有错误返回统一结构，不泄露内部异常细节：

```json
{"code": "VALIDATION_ERROR", "message": "...", "timestamp": "..."}
```

| HTTP | code | 含义 |
|------|------|------|
| 400 | `VALIDATION_ERROR` | 入参不合法或请求体无法解析 |
| 409 | `IDEMPOTENCY_KEY_CONFLICT` | 幂等键已被使用但请求内容不一致 |
| 503 | `SERVICE_UNAVAILABLE` | 服务暂时处理不了，可稍后重试 |
| 500 | `INTERNAL_ERROR` | 未预期的内部错误（细节只记服务端日志） |

## 测试

```
mvn test
```

- `OrderApiTests`（接口集成测试）：重复提交、同键不同内容、16 线程并发重放、非法入参、旧调用兼容。
- `IdempotencyServiceTest`（单元测试）：失败后释放键允许重试、同键不同内容冲突、并发等待首个结果。

测试日志中以 `[重复提交]`、`[并发重放]` 等标签输出每个用例覆盖的场景与结果。
