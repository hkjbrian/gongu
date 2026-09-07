package com.gongu.server.domain.product.service;

import com.gongu.server.domain.product.dto.ProductCacheDto;
import com.gongu.server.domain.product.entity.Product;
import com.gongu.server.domain.product.repository.ProductRepository;
import com.gongu.server.global.exception.BusinessException;
import com.gongu.server.global.exception.errorcode.ProductErrorCode;
import lombok.RequiredArgsConstructor;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class ProductCacheService {

    private final ProductRepository productRepository;

    @Cacheable(value = "product", key = "#productId")
    public ProductCacheDto getProduct(Long productId) {
        Product product = productRepository.findById(productId)
                .orElseThrow(() -> new BusinessException(ProductErrorCode.PRODUCT_NOT_FOUND));

        return ProductCacheDto.from(product);
    }

    @CacheEvict(value = "product", key = "#productId")
    public void evict(Long productId) {
    }
}
