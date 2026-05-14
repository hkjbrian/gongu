package com.gongu.server.domain.product.service;

import com.gongu.server.domain.product.domain.Product;
import com.gongu.server.domain.product.domain.ProductStatus;
import com.gongu.server.domain.product.repository.ProductRepository;
import com.gongu.server.domain.store.entity.Store;
import com.gongu.server.domain.store.repository.StoreRepository;
import com.gongu.server.global.exception.BusinessException;
import com.gongu.server.global.exception.errorcode.ProductErrorCode;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
class ProductStockConcurrencyTest {

    @Autowired
    private ProductService productService;

    @Autowired
    private ProductRepository productRepository;

    @Autowired
    private StoreRepository storeRepository;

    @AfterEach
    void tearDown() {
        productRepository.deleteAll();
        storeRepository.deleteAll();
    }

    @Test
    @DisplayName("재고_10_스레드_10개_각_1씩_차감_최종재고_0")
    void 재고_10_스레드_10개_각_1씩_차감_최종재고_0() throws InterruptedException {
        // given
        Store store = storeRepository.save(Store.create("테스트매장", "서울시 강남구", "02-1234-5678"));
        Product product = productRepository.save(
                Product.create(store, "테스트상품", "설명", 1000L, 10, ProductStatus.ACTIVE,
                        LocalDateTime.now(), LocalDateTime.now().plusDays(7))
        );
        Long productId = product.getId();

        int threadCount = 10;
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch doneLatch = new CountDownLatch(threadCount);

        // when
        for (int i = 0; i < threadCount; i++) {
            executor.submit(() -> {
                try {
                    startLatch.await();
                    productService.decreaseStock(productId, 1);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    doneLatch.countDown();
                }
            });
        }

        startLatch.countDown();
        doneLatch.await();
        executor.shutdown();

        // then
        Product result = productRepository.findById(productId).orElseThrow();
        assertThat(result.getRemainingStock()).isEqualTo(0);
    }

    @Test
    @DisplayName("재고_5_스레드_10개_각_1씩_차감_예외_발생_재고_음수_아님")
    void 재고_5_스레드_10개_각_1씩_차감_예외_발생_재고_음수_아님() throws InterruptedException {
        // given
        Store store = storeRepository.save(Store.create("테스트매장2", "서울시 서초구", "02-9876-5432"));
        Product product = productRepository.save(
                Product.create(store, "재고부족상품", "설명", 2000L, 5, ProductStatus.ACTIVE,
                        LocalDateTime.now(), LocalDateTime.now().plusDays(7))
        );
        Long productId = product.getId();

        int threadCount = 10;
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch doneLatch = new CountDownLatch(threadCount);
        AtomicInteger exceptionCount = new AtomicInteger(0);
        List<Future<?>> futures = new ArrayList<>();

        // when
        for (int i = 0; i < threadCount; i++) {
            futures.add(executor.submit(() -> {
                try {
                    startLatch.await();
                    productService.decreaseStock(productId, 1);
                } catch (BusinessException e) {
                    if (ProductErrorCode.INSUFFICIENT_STOCK.getCode().equals(e.getErrorCode().getCode())) {
                        exceptionCount.incrementAndGet();
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    doneLatch.countDown();
                }
            }));
        }

        startLatch.countDown();
        doneLatch.await();
        executor.shutdown();

        // then
        Product result = productRepository.findById(productId).orElseThrow();
        assertThat(exceptionCount.get()).isGreaterThan(0);
        assertThat(result.getRemainingStock()).isGreaterThanOrEqualTo(0);
    }
}
