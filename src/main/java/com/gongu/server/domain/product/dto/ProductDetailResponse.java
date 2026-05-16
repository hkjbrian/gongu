package com.gongu.server.domain.product.dto;

import com.gongu.server.domain.product.entity.Product;
import com.gongu.server.domain.product.entity.ProductStatus;

import java.time.LocalDateTime;

public record ProductDetailResponse(
        Long id,
        String name,
        String description,
        Long price,
        int totalStock,
        int remainingStock,
        ProductStatus status,
        LocalDateTime startAt,
        LocalDateTime endAt
) {
    public static ProductDetailResponse from(Product product) {
        return new ProductDetailResponse(
                product.getId(),
                product.getName(),
                product.getDescription(),
                product.getPrice(),
                product.getTotalStock(),
                product.getRemainingStock(),
                product.getStatus(),
                product.getStartAt(),
                product.getEndAt()
        );
    }
}
