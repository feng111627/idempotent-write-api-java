package com.github.feng111627.idempotentwriteapijava.testsupport;

import java.util.Map;

import com.github.feng111627.idempotentwriteapijava.dependency.OrderDependencyGateway;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 本地复现用的测试支撑接口：仅在 test-support.enabled=true 时注册（默认关闭）。
 * 用于手动制造/解除"依赖临时不可用"，验证 503 与重试链路。
 */
@RestController
@RequestMapping("/test-support/dependency")
@ConditionalOnProperty(name = "test-support.enabled", havingValue = "true")
public class TestSupportController {

    private final OrderDependencyGateway gateway;

    public TestSupportController(OrderDependencyGateway gateway) {
        this.gateway = gateway;
    }

    /** 进入持续故障：之后所有下单都返回 503，直到调用 recover */
    @PostMapping("/outage")
    public Map<String, Object> outage() {
        gateway.simulateOutage();
        return status();
    }

    /** 让接下来 n 次下单失败（模拟抖动），之后自动恢复 */
    @PostMapping("/fail-next")
    public Map<String, Object> failNext(@RequestParam(defaultValue = "1") int count) {
        gateway.failNext(count);
        return status();
    }

    /** 从故障中恢复 */
    @PostMapping("/recover")
    public Map<String, Object> recover() {
        gateway.recover();
        return status();
    }

    /** 当前故障注入状态 */
    @GetMapping("/status")
    public Map<String, Object> status() {
        return Map.of("failureCount", gateway.failureCount());
    }
}
