package com.gongu.server.domain.product.service;

import com.gongu.server.domain.product.dto.ProductCacheDto;
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
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;

@ExtendWith(MockitoExtension.class)
class ProductCacheServiceTest {

    @Mock
    private ProductRepository productRepository;

    @InjectMocks
    private ProductCacheService productCacheService;

    @Test
    @DisplayName("getProduct_캐시미스시_DB조회후DTO반환")
    void getProduct_캐시미스시_DB조회후DTO반환() {
        // given
        Product product = product(1L, store(10L), 20_000L, ProductStatus.ACTIVE);
        given(productRepository.findById(1L)).willReturn(Optional.of(product));

        // when
        ProductCacheDto result = productCacheService.getProduct(1L);

        // then
        assertThat(result.id()).isEqualTo(1L);
        assertThat(result.storeId()).isEqualTo(10L);
        assertThat(result.price()).isEqualTo(20_000L);
        assertThat(result.status()).isEqualTo(ProductStatus.ACTIVE);
    }

    @Test
    @DisplayName("getProduct_존재하지않는상품_예외")
    void getProduct_존재하지않는상품_예외() {
        // given
        given(productRepository.findById(999L)).willReturn(Optional.empty());

        // when & then
        assertThatThrownBy(() -> productCacheService.getProduct(999L))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode())
                        .isEqualTo(ProductErrorCode.PRODUCT_NOT_FOUND));
    }

    private Product product(Long id, Store store, Long price, ProductStatus status) {
        Product product = Product.create(
                store,
                "상품" + id,
                "상품 설명",
                price,
                10,
                status,
                LocalDateTime.now().minusDays(1),
                LocalDateTime.now().plusDays(1)
        );
        setId(product, id);
        return product;
    }

    private Store store(Long id) {
        Store store = Store.create("매장" + id, "서울시 강남구", "02-1234-5678");
        setId(store, id);
        return store;
    }

    private void setId(Object target, Long id) {
        ReflectionTestUtils.setField(target, "id", id);
    }
}
