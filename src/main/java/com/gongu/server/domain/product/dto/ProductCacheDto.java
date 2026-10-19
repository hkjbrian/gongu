package com.gongu.server.domain.product.dto;

import com.gongu.server.domain.product.entity.Product;
import com.gongu.server.domain.product.entity.ProductStatus;

public record ProductCacheDto(
        Long id,
        Long storeId,
        Long price,
        ProductStatus status
) {
    public static ProductCacheDto from(Product product) {
        return new ProductCacheDto(
                product.getId(),
                product.getStore().getId(),
                product.getPrice(),
                product.getStatus()
        );
    }
}
