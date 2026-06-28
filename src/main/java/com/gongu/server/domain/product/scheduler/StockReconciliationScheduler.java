package com.gongu.server.domain.product.scheduler;

import com.gongu.server.domain.product.entity.Product;
import com.gongu.server.domain.product.entity.ProductStatus;
import com.gongu.server.domain.product.repository.ProductRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
@Slf4j
@RequiredArgsConstructor
public class StockReconciliationScheduler {

    private final ProductRepository productRepository;
    private final StockReconciliationHelper reconciliationHelper;

    @Scheduled(cron = "0 */5 * * * *")
    public void reconcile() {
        List<Product> products = productRepository.findAllByStatus(ProductStatus.ACTIVE);

        for (Product product : products) {
            try {
                reconciliationHelper.reconcileOne(product.getId());
            } catch (Exception e) {
                log.error("[재조정] 상품 처리 실패: productId={}", product.getId(), e);
            }
        }
    }
}
