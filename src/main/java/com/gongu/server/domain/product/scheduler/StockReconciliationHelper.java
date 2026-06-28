package com.gongu.server.domain.product.scheduler;

import com.gongu.server.domain.order.entity.OrderStatus;
import com.gongu.server.domain.order.repository.OrderItemRepository;
import com.gongu.server.domain.product.entity.Product;
import com.gongu.server.domain.product.repository.ProductRepository;
import com.gongu.server.domain.product.service.StockRedisService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Component
@Slf4j
@RequiredArgsConstructor
public class StockReconciliationHelper {

    private final ProductRepository productRepository;
    private final OrderItemRepository orderItemRepository;
    private final StockRedisService stockRedisService;

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void reconcileOne(Long productId) {
        Product product = productRepository.findById(productId)
                .orElse(null);
        if (product == null) {
            return;
        }

        Long reserved = orderItemRepository.sumQuantityByProductIdAndOrderStatus(productId, OrderStatus.RESERVED);
        int reservedQuantity = reserved == null ? 0 : Math.toIntExact(reserved);
        int correctStock = product.getRemainingStock() - reservedQuantity;
        Long currentStock = stockRedisService.getCurrentStock(productId);

        if (currentStock == null) {
            stockRedisService.initializeStock(productId, correctStock);
            log.warn("[재조정] Redis 키 없음: productId={}, correctStock={}", productId, correctStock);
            return;
        }

        if (currentStock < correctStock) {
            stockRedisService.releaseStock(productId, Math.toIntExact(correctStock - currentStock));
            log.warn(
                    "[재조정] Redis 낮음: productId={}, currentStock={}, correctStock={}",
                    productId,
                    currentStock,
                    correctStock
            );
            return;
        }

        if (currentStock > correctStock) {
            log.warn(
                    "[재조정] Redis 높음(과다): productId={}, currentStock={}, correctStock={}",
                    productId,
                    currentStock,
                    correctStock
            );
        }
    }
}
