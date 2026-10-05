package com.github.feng111627.idempotentwriteapijava.order;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;

/**
 * 下单请求体（保持既有字段不变）。
 */
public record OrderRequest(
        @NotBlank String itemId,
        @Min(1) int quantity
) {
}
