package com.gongu.server.domain.product.service;

import com.gongu.server.global.exception.BusinessException;
import com.gongu.server.global.exception.errorcode.ProductErrorCode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

@Slf4j
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

    public void releaseStockAfterCommit(Long productId, int quantity) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            // 방어적 fallback: 활성 트랜잭션이 없으면 즉시 반영
            releaseStock(productId, quantity);
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                try {
                    releaseStock(productId, quantity);
                } catch (Exception e) {
                    log.error("커밋 후 Redis 재고 복원 실패: productId={}, quantity={}", productId, quantity, e);
                }
            }
        });
    }

    public Long getCurrentStock(Long productId) {
        String value = stringRedisTemplate.opsForValue().get(stockKey(productId));
        return value == null ? null : Long.parseLong(value);
    }

    private String stockKey(Long productId) {
        return STOCK_KEY_PREFIX + productId;
    }
}
