package com.gongu.server.domain.product.scheduler;

import com.gongu.server.domain.product.entity.Product;
import com.gongu.server.domain.product.entity.ProductStatus;
import com.gongu.server.domain.product.repository.ProductRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

@Component
@RequiredArgsConstructor
public class ProductStatusScheduler {

    private final ProductRepository productRepository;

    @Scheduled(cron = "0 * * * * *")
    @Transactional
    public void activateUpcomingProducts() {
        productRepository
                .findActivatableUpcomingProducts(ProductStatus.UPCOMING, LocalDateTime.now())
                .forEach(Product::activate);
    }
}
