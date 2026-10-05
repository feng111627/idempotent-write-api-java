package com.github.feng111627.idempotentwriteapijava.idempotency;

import com.github.feng111627.idempotentwriteapijava.api.CreateOrderRequest;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * 请求内容指纹：对规范化后的请求内容做 SHA-256，
 * 用于判断同一个幂等键下的两次请求内容是否一致。
 */
public final class RequestFingerprint {

    private RequestFingerprint() {
    }

    public static String of(CreateOrderRequest request) {
        String canonical = "productId=" + request.productId() + ";quantity=" + request.quantity();
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(canonical.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            // SHA-256 是 JDK 必备算法，理论上不会到这里
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }
}
