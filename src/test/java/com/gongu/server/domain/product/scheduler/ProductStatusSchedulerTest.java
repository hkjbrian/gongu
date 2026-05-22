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

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;

@ExtendWith(MockitoExtension.class)
class ProductStatusSchedulerTest {

    @Mock
    private ProductRepository productRepository;

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

        given(productRepository.findActivatableUpcomingProducts(
                eq(ProductStatus.UPCOMING), any(LocalDateTime.class)))
                .willReturn(List.of(product1, product2));

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
    @DisplayName("activate_UPCOMING_아닌_상품은_CANNOT_ACTIVATE_PRODUCT_예외")
    void activate_UPCOMING_아닌_상품은_CANNOT_ACTIVATE_PRODUCT_예외() {
        // given
        Product activeProduct = createProduct(createStore("매장"), "상품", ProductStatus.ACTIVE);

        // when & then
        assertThatThrownBy(activeProduct::activate)
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode())
                        .isEqualTo(ProductErrorCode.CANNOT_ACTIVATE_PRODUCT));
    }

    private Store createStore(String name) {
        return Store.create(name, "서울시 강남구", "02-1234-5678");
    }

    private Product createProduct(Store store, String name, ProductStatus status) {
        return Product.create(store, name, "상품 설명", 10_000L, 100, status, startAt, endAt);
    }
}
