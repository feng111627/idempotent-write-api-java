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
- **执行失败**：首个请求执行失败时会释放该幂等键，键不会被失败占死，客户端可用同一键重试。

## 可恢复失败与重试语义（本轮新增）

依赖临时不可用（如下游抽风）时，请求返回 `503 SERVICE_UNAVAILABLE`，这是一次**可恢复失败**：

- 这次请求**不算成功**（没有订单产生），也**不会被当成重复提交**返回任何缓存结果；
- 幂等键**不会被失败占死**：依赖恢复后，用**同一个键、同样的内容**重试，会真正执行并返回真实结果（`replayed=false`）；
- 从故障到重试成功，整条链路**只落地一份订单**；重试成功后的重复提交仍按重放处理（`replayed=true`，同一 `orderId`）；
- 失败时键被释放，但**内容指纹会保留**：临时不可用期间同键不同内容的请求仍按 `409` 冲突拒绝，不会被当成新请求放行；
- `503` 响应带 `Retry-After: 1` 头，且错误体中 `retryable=true`，客户端可据此决定稍后重试。

### 本地复现与验证

不依赖任何外部服务即可稳定制造故障。启动时打开测试支撑接口（默认关闭）：

```
mvn spring-boot:run -Dspring-boot.run.arguments="--server.port=8080 --test-support.enabled=true"
```

然后：

```bash
# 1. 制造持续故障（也可用 POST /test-support/dependency/fail-next?count=2 模拟抖动）
curl -X POST localhost:8080/test-support/dependency/outage

# 2. 故障期下单 -> 503 SERVICE_UNAVAILABLE, retryable=true, 带 Retry-After 头
curl -i -X POST localhost:8080/api/orders -H 'Idempotency-Key: k1' \
  -H 'Content-Type: application/json' -d '{"itemId":"item-1","quantity":2}'

# 3. 故障期同键不同内容 -> 409 IDEMPOTENCY_KEY_CONFLICT（仍按冲突拒绝）
curl -X POST localhost:8080/api/orders -H 'Idempotency-Key: k1' \
  -H 'Content-Type: application/json' -d '{"itemId":"item-2","quantity":2}'

# 4. 恢复依赖
curl -X POST localhost:8080/test-support/dependency/recover

# 5. 同键同内容重试 -> 200, replayed=false（真实执行，只落地一份订单）
# 6. 再次重复 -> 200, replayed=true（重放同一 orderId）
```

## 并发下的保证

同一幂等键的多个请求同时到达时，内部通过 `ConcurrentHashMap.putIfAbsent` 抢占键的所有权：

- 抢到所有权的请求执行真实操作；
- 其余请求等待同一个结果（最长 10 秒），随后拿到与首个请求完全相同的响应；
- 因此无论并发多少，副作用只发生一次，且所有并发请求的结果一致。

并发期间遇到临时故障时，同键的所有并发请求拿到一致的失败结论（`503`），且没有任何副作用落地；恢复后重试，整批请求最终只产生一份订单、拿到同一个 `orderId`。

等待超时或首个请求失败时，等待中的请求会收到 `503 SERVICE_UNAVAILABLE`，可稍后重试。

## 错误返回约定

所有错误返回统一结构，不泄露内部异常细节：

```json
{"code": "SERVICE_UNAVAILABLE", "message": "...", "retryable": true, "timestamp": "..."}
```

`retryable` 表示这次失败值不值得重试：`true` 说明是临时故障，稍后重试可能成功；`false` 说明原样重试无意义，需修正请求。

| HTTP | code | retryable | 含义 |
|------|------|-----------|------|
| 400 | `VALIDATION_ERROR` | false | 入参不合法或请求体无法解析 |
| 409 | `IDEMPOTENCY_KEY_CONFLICT` | false | 幂等键已被使用但请求内容不一致 |
| 503 | `SERVICE_UNAVAILABLE` | true | 依赖临时不可用等服务暂时处理不了的情形，可稍后重试（带 `Retry-After` 头） |
| 500 | `INTERNAL_ERROR` | false | 未预期的内部错误（细节只记服务端日志） |

## 兼容性说明

- 不带 `Idempotency-Key` 的旧调用方式不变：每次请求直接执行；
- 成功响应字段不变（`orderId`/`itemId`/`quantity`/`replayed`）；
- 错误体在原有 `code`/`message`/`timestamp` 基础上**新增** `retryable` 字段，原有字段与含义不变；
- 错误码集合不变，本轮只是让 `SERVICE_UNAVAILABLE` 能被真实触发并附带 `Retry-After` 头。

## 测试

```
mvn test
```

- `OrderApiTests`（接口集成测试）：重复提交、同键不同内容、16 线程并发重放、非法入参、旧调用兼容。
- `TransientFailureApiTests`（可恢复失败链路）：临时故障后同键重试成功且只落地一份订单、并发下故障与重试只产生一份副作用且结论一致、三类错误可区分且不泄露内部细节、故障期同键不同内容仍按冲突拒绝。
- `IdempotencyServiceTest`（单元测试）：失败后释放键允许重试、失败后指纹保留（冲突仍拒绝、同内容可重试）、同键不同内容冲突、并发等待首个结果。

测试日志中以 `[重复提交]`、`[临时故障重试]`、`[并发故障重试]`、`[错误分类]`、`[故障期冲突]` 等标签输出每个用例覆盖的场景与结果。
