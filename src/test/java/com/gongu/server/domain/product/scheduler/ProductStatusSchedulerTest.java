package com.gongu.server.domain.product.scheduler;

import com.gongu.server.domain.product.entity.Product;
import com.gongu.server.domain.product.entity.ProductStatus;
import com.gongu.server.domain.product.repository.ProductRepository;
import com.gongu.server.domain.store.entity.Store;
import com.gongu.server.global.exception.BusinessException;
import com.gongu.server.global.exception.errorcode.ProductErrorCode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.doAnswer;

@ExtendWith(MockitoExtension.class)
class ProductStatusSchedulerTest {

    @Mock
    private ProductRepository productRepository;

    @Mock
    private ProductStatusTransactionHelper productStatusTransactionHelper;

    @InjectMocks
    private ProductStatusScheduler scheduler;

    private final LocalDateTime startAt = LocalDateTime.of(2026, 5, 1, 10, 0);
    private final LocalDateTime endAt   = LocalDateTime.of(2026, 5, 2, 10, 0);

    @Test
    @DisplayName("activateUpcomingProducts_startAt_도래한_UPCOMING_상품_ACTIVE_전이")
    void activateUpcomingProducts_startAt_도래한_UPCOMING_상품_ACTIVE_전이() {
        // given
        Store store = createStore("테스트매장");
        Product product1 = createProduct(store, "상품1", ProductStatus.UPCOMING);
        Product product2 = createProduct(store, "상품2", ProductStatus.UPCOMING);
        setProductId(product1, 1L);
        setProductId(product2, 2L);
        Map<Long, Product> productsById = Map.of(
                product1.getId(), product1,
                product2.getId(), product2
        );

        given(productRepository.findActivatableUpcomingProducts(
                eq(ProductStatus.UPCOMING), any(LocalDateTime.class)))
                .willReturn(List.of(product1, product2));
        doAnswer(invocation -> {
            Long productId = invocation.getArgument(0);
            productsById.get(productId).activate();
            return null;
        }).when(productStatusTransactionHelper).activateOne(any(Long.class));

        // when
        scheduler.activateUpcomingProducts();

        // then
        assertThat(product1.getStatus()).isEqualTo(ProductStatus.ACTIVE);
        assertThat(product2.getStatus()).isEqualTo(ProductStatus.ACTIVE);
    }

    @Test
    @DisplayName("activateUpcomingProducts_대상_없으면_아무것도_하지_않음")
    void activateUpcomingProducts_대상_없으면_아무것도_하지_않음() {
        // given
        given(productRepository.findActivatableUpcomingProducts(
                eq(ProductStatus.UPCOMING), any(LocalDateTime.class)))
                .willReturn(List.of());

        // when & then (예외 없이 정상 종료)
        scheduler.activateUpcomingProducts();
    }

    @Test
    @DisplayName("activateUpcomingProducts_1개_실패_시_나머지_성공")
    void activateUpcomingProducts_1개_실패_시_나머지_성공() {
        // given
        Store store = createStore("테스트매장");
        Product product1 = createProduct(store, "상품1", ProductStatus.UPCOMING);
        Product product2 = createProduct(store, "상품2", ProductStatus.UPCOMING);
        Product product3 = createProduct(store, "상품3", ProductStatus.UPCOMING);
        setProductId(product1, 1L);
        setProductId(product2, 2L);
        setProductId(product3, 3L);
        Map<Long, Product> productsById = Map.of(
                product1.getId(), product1,
                product2.getId(), product2,
                product3.getId(), product3
        );

        given(productRepository.findActivatableUpcomingProducts(
                eq(ProductStatus.UPCOMING), any(LocalDateTime.class)))
                .willReturn(List.of(product1, product2, product3));
        doAnswer(invocation -> {
            Long productId = invocation.getArgument(0);
            Product product = productsById.get(productId);
            if (product == product2) {
                throw new BusinessException(ProductErrorCode.INVALID_PRODUCT_STATUS);
            }

            product.activate();
            return null;
        }).when(productStatusTransactionHelper).activateOne(any(Long.class));

        // when
        scheduler.activateUpcomingProducts();

        // then
        assertThat(product1.getStatus()).isEqualTo(ProductStatus.ACTIVE);
        assertThat(product2.getStatus()).isEqualTo(ProductStatus.UPCOMING);
        assertThat(product3.getStatus()).isEqualTo(ProductStatus.ACTIVE);
    }

    private Store createStore(String name) {
        return Store.create(name, "서울시 강남구", "02-1234-5678");
    }

    private Product createProduct(Store store, String name, ProductStatus status) {
        return Product.create(store, name, "상품 설명", 10_000L, 100, status, startAt, endAt);
    }

    private void setProductId(Product product, Long id) {
        ReflectionTestUtils.setField(product, "id", id);
    }
}
