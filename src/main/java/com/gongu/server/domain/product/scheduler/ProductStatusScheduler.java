package com.gongu.server.domain.product.scheduler;

import com.gongu.server.domain.product.entity.Product;
import com.gongu.server.domain.product.entity.ProductStatus;
import com.gongu.server.domain.product.repository.ProductRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.List;

@Slf4j
@Component
@RequiredArgsConstructor
public class ProductStatusScheduler {

    private final ProductRepository productRepository;
    private final ProductStatusTransactionHelper productStatusTransactionHelper;

    @Scheduled(cron = "0 * * * * *")
    public void activateUpcomingProducts() {
        List<Product> products = productRepository.findActivatableUpcomingProducts(ProductStatus.UPCOMING, LocalDateTime.now());

        for (Product product : products) {
            try {
                productStatusTransactionHelper.activateOne(product.getId());
            } catch (Exception e) {
                log.error("상품 활성화 실패: id={}", product.getId(), e);
            }
        }
    }
}
