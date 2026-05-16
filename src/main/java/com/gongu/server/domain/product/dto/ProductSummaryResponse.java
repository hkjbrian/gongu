package com.gongu.server.domain.product.dto;

import com.gongu.server.domain.product.entity.Product;
import com.gongu.server.domain.product.entity.ProductStatus;

import java.time.LocalDateTime;

public record ProductSummaryResponse(
        Long id,
        String name,
        Long price,
        int remainingStock,
        ProductStatus status,
        LocalDateTime startAt,
        LocalDateTime endAt
) {
    public static ProductSummaryResponse from(Product product) {
        return new ProductSummaryResponse(
                product.getId(),
                product.getName(),
                product.getPrice(),
                product.getRemainingStock(),
                product.getStatus(),
                product.getStartAt(),
                product.getEndAt()
        );
    }
}
