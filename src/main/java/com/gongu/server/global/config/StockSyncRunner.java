package com.gongu.server.global.config;

import com.gongu.server.domain.order.entity.OrderStatus;
import com.gongu.server.domain.order.repository.OrderItemRepository;
import com.gongu.server.domain.product.entity.Product;
import com.gongu.server.domain.product.entity.ProductStatus;
import com.gongu.server.domain.product.repository.ProductRepository;
import com.gongu.server.domain.product.service.StockRedisService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Component
@Slf4j
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class StockSyncRunner implements ApplicationRunner {

    private final ProductRepository productRepository;
    private final OrderItemRepository orderItemRepository;
    private final StockRedisService stockRedisService;

    @Override
    public void run(ApplicationArguments args) {
        List<Product> products = productRepository.findAllByStatus(ProductStatus.ACTIVE);
        int processedCount = 0;

        for (Product product : products) {
            try {
                Long reserved = orderItemRepository.sumQuantityByProductIdAndOrderStatus(
                        product.getId(),
                        OrderStatus.RESERVED
                );
                int reservedQuantity = reserved == null ? 0 : Math.toIntExact(reserved);
                int correctStock = product.getRemainingStock() - reservedQuantity;

                stockRedisService.initializeStock(product.getId(), correctStock);
                processedCount++;
            } catch (Exception e) {
                log.error("[StockSyncRunner] startup sync 상품 처리 실패: productId={}", product.getId(), e);
            }
        }

        log.info("[StockSyncRunner] startup sync 완료: {}개 상품 Redis 재고 재계산", processedCount);
    }
}
