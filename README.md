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
- **同键不同内容**：明确拒绝，返回 `409` 与错误码 `IDEMPOTENCY_KEY_CONFLICT`，不会当成新请求执行。内容是否一致按请求体的规范化指纹（SHA-256）判定。指纹在抢占键时即登记，因此即使首个请求还在执行中（包括依赖临时不可用期间），同键不同内容也会被立即按冲突拒绝，而不会被放行。
- **不带幂等键**：保持旧的调用行为，每次请求都直接执行，互不影响。
- **执行失败**：首个请求执行失败时会释放该幂等键，不会把键占死，客户端可用同一键、同一内容安全重试。

## 可恢复失败与重试语义

依赖临时不可用时，请求会被真实拒绝为 `503 SERVICE_UNAVAILABLE`（`retryable=true`）——既不会被当成成功下单，也不会被当成重复提交返回旧结果，也不会产生任何副作用。

失败之后的重试是安全的：

- **同键同内容重试**：故障恢复后用同一键、同一内容重试，会真正执行并返回一次真实结果（`replayed=false`）；整条链路从头到尾只落地一份订单。之后再提交同键请求则按正常重放处理。
- **并发下一致**：同一批同键请求同时到达时，无论首个请求直接成功还是中途遇到临时故障，所有并发请求拿到的结论一致（都成功且结果相同，或都收到 503），副作用至多发生一次。收到 503 的客户端各自重试时，也只会有一个请求真正执行，其余重放它的结果。
- **重试时机**：是否值得原样重试由错误响应的 `retryable` 字段标明（见下表），`503` 之外的错误都需要客户端先修正请求。

## 并发下的保证

同一幂等键的多个请求同时到达时，内部通过 `ConcurrentHashMap.putIfAbsent` 抢占键的所有权：

- 抢到所有权的请求执行真实操作；
- 其余请求先比对内容指纹（不一致立即 `409`），一致则等待同一个结果（最长 10 秒），随后拿到与首个请求完全相同的结论；
- 因此无论并发多少，副作用只发生一次，且所有并发请求的结果一致。

等待超时或首个请求失败时，等待中的请求会收到与首个请求一致的失败结论（临时故障为 `503 SERVICE_UNAVAILABLE`），可稍后重试。

## 错误返回约定

所有错误返回统一结构，不泄露内部异常细节：

```json
{"code": "SERVICE_UNAVAILABLE", "message": "...", "retryable": true, "timestamp": "..."}
```

| HTTP | code | retryable | 含义 |
|------|------|-----------|------|
| 400 | `VALIDATION_ERROR` | false | 入参不合法或请求体无法解析，需修正请求 |
| 409 | `IDEMPOTENCY_KEY_CONFLICT` | false | 幂等键已被使用但请求内容不一致 |
| 503 | `SERVICE_UNAVAILABLE` | true | 依赖临时不可用或等待超时，可原样稍后重试 |
| 500 | `INTERNAL_ERROR` | false | 未预期的内部错误（细节只记服务端日志） |

`retryable` 为新增字段，仅做加法、不改变既有字段；旧的 `code` / `message` / `timestamp` 结构保持不变。

## 兼容性

- 不带 `Idempotency-Key` 的既有调用方式不变，仍按原行为直接执行。
- 成功响应与既有字段不变；错误响应仅新增 `retryable` 字段，既有字段与错误码不变。

## 本地复现临时故障

不依赖任何外部服务即可复现"依赖临时不可用"：

- 启动时打开故障开关：`order.dependency-unavailable=true`（如 `mvn spring-boot:run -Dspring-boot.run.arguments=--order.dependency-unavailable=true`），期间所有下单请求都会收到 `503 SERVICE_UNAVAILABLE`；去掉该配置即恢复，同键重试即可成功。
- `order.create-delay-ms=<毫秒>` 可模拟依赖响应缓慢，用于放大并发竞争窗口。
- 自动化测试通过 `OrderService.setDependencyUnavailable(boolean)` 在运行时切换故障，覆盖"故障->恢复->重试"全链路。

## 测试

```
mvn test
```

- `OrderApiTests`（接口集成测试）：重复提交、同键不同内容、16 线程并发重放、非法入参、旧调用兼容。
- `OrderFailureRecoveryTests`（故障恢复集成测试）：临时故障后同键重试成功且只落地一份订单、并发下失败与重试只落地一次、三类错误可区分且不泄露内部细节、故障期间同键不同内容仍按 409 拒绝。
- `IdempotencyServiceTest`（单元测试）：失败后释放键允许重试、同键不同内容冲突、并发等待首个结果、并发临时故障结论一致且恢复后重试成功、在途期间冲突立即拒绝。

测试日志中以 `[重复提交]`、`[并发重放]`、`[故障后重试]`、`[并发故障重试]`、`[三类错误]`、`[故障期冲突]` 等标签输出每个用例覆盖的场景与结果。
