package com.gongu.server.domain.product.service;

import com.gongu.server.global.exception.BusinessException;
import com.gongu.server.global.exception.errorcode.ProductErrorCode;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class StockRedisService {

    private static final String STOCK_KEY_PREFIX = "stock:product:";

    private final StringRedisTemplate stringRedisTemplate;

    public void initializeStock(Long productId, int totalStock) {
        stringRedisTemplate.opsForValue()
                .set(stockKey(productId), String.valueOf(totalStock));
    }

    public void reserveStock(Long productId, int quantity) {
        String key = stockKey(productId);
        Long result = stringRedisTemplate.opsForValue()
                .decrement(key, quantity);

        if (result == null) {
            throw new BusinessException(ProductErrorCode.PRODUCT_NOT_FOUND);
        }

        if (result < 0) {
            stringRedisTemplate.opsForValue()
                    .increment(key, quantity);
            throw new BusinessException(ProductErrorCode.INSUFFICIENT_STOCK);
        }
    }

    public void releaseStock(Long productId, int quantity) {
        stringRedisTemplate.opsForValue()
                .increment(stockKey(productId), quantity);
    }

    public Long getCurrentStock(Long productId) {
        String value = stringRedisTemplate.opsForValue().get(stockKey(productId));
        return value == null ? null : Long.parseLong(value);
    }

    private String stockKey(Long productId) {
        return STOCK_KEY_PREFIX + productId;
    }
}
