package com.gongu.server.domain.product.entity;

import com.gongu.server.domain.store.entity.Store;
import com.gongu.server.global.exception.BusinessException;
import com.gongu.server.global.exception.errorcode.ProductErrorCode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@ExtendWith(MockitoExtension.class)
class ProductTest {

    @Mock
    private Store store;

    private final LocalDateTime now = LocalDateTime.now();
    private final LocalDateTime later = now.plusDays(7);

    @Test
    @DisplayName("ACTIVE 상태가 아닌 상품의 재고를 차감하면 INVALID_PRODUCT_STATUS 예외가 발생한다")
    void decreaseStock_notActive_throwsException() {
        // given — Product.create() 로 생성 시 UPCOMING 상태 지정
        Product product = Product.create(store, "테스트상품", "설명", 1000L, 10,
                ProductStatus.UPCOMING, now, later);

        // when & then
        assertThatThrownBy(() -> product.decreaseStock(1))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> {
                    BusinessException be = (BusinessException) ex;
                    assertThat(be.getErrorCode().getCode())
                            .isEqualTo(ProductErrorCode.INVALID_PRODUCT_STATUS.getCode());
                });
    }

    @Test
    @DisplayName("재고가 부족하면 INSUFFICIENT_STOCK 예외가 발생한다")
    void decreaseStock_insufficientStock_throwsException() {
        // given — ACTIVE 상태로 생성, 재고 3
        Product product = Product.create(store, "테스트상품", "설명", 1000L, 3,
                ProductStatus.ACTIVE, now, later);

        // when & then
        assertThatThrownBy(() -> product.decreaseStock(5))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> {
                    BusinessException be = (BusinessException) ex;
                    assertThat(be.getErrorCode().getCode())
                            .isEqualTo(ProductErrorCode.INSUFFICIENT_STOCK.getCode());
                });
    }

    @Test
    @DisplayName("UPCOMING 상품을 activate하면 ACTIVE로 전이된다")
    void activate_upcoming_success() {
        // given
        Product product = Product.create(store, "테스트상품", "설명", 1000L, 10,
                ProductStatus.UPCOMING, now, later);

        // when
        product.activate();

        // then
        assertThat(product.getStatus()).isEqualTo(ProductStatus.ACTIVE);
    }

    @Test
    @DisplayName("UPCOMING이 아닌 상품을 activate하면 CANNOT_ACTIVATE_PRODUCT 예외가 발생한다")
    void activate_notUpcoming_throwsException() {
        // given
        Product product = Product.create(store, "테스트상품", "설명", 1000L, 10,
                ProductStatus.ACTIVE, now, later);

        // when & then
        assertThatThrownBy(product::activate)
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode())
                        .isEqualTo(ProductErrorCode.CANNOT_ACTIVATE_PRODUCT));
    }

    @Test
    @DisplayName("ACTIVE 상태 상품의 재고가 정상 차감된다")
    void decreaseStock_success() {
        // given — ACTIVE 상태로 생성, 재고 10
        Product product = Product.create(store, "테스트상품", "설명", 1000L, 10,
                ProductStatus.ACTIVE, now, later);

        // when
        product.decreaseStock(3);

        // then
        assertThat(product.getRemainingStock()).isEqualTo(7);
    }

    @Test
    @DisplayName("재고가 딱 맞게 소진되면 SOLD_OUT으로 전이된다")
    void decreaseStock_재고_소진_시_SOLD_OUT_전이() {
        // given — ACTIVE 상태로 생성, 재고 10
        Product product = Product.create(store, "테스트상품", "설명", 1000L, 10,
                ProductStatus.ACTIVE, now, later);

        // when
        product.decreaseStock(10);

        // then
        assertThat(product.getStatus()).isEqualTo(ProductStatus.SOLD_OUT);
    }

    @Test
    @DisplayName("재고가 남아 있으면 SOLD_OUT으로 전이되지 않는다")
    void decreaseStock_재고_남음_SOLD_OUT_전이_없음() {
        // given — ACTIVE 상태로 생성, 재고 10
        Product product = Product.create(store, "테스트상품", "설명", 1000L, 10,
                ProductStatus.ACTIVE, now, later);

        // when
        product.decreaseStock(3);

        // then
        assertThat(product.getStatus()).isEqualTo(ProductStatus.ACTIVE);
    }

    @Test
    @DisplayName("SOLD_OUT 상태에서 재고를 복원하면 ACTIVE로 전이된다")
    void restoreStock_SOLD_OUT_상태에서_재고_복원_시_ACTIVE_전이() {
        // given — ACTIVE 상품의 재고를 모두 차감해 SOLD_OUT 상태로 만든다
        Product product = Product.create(store, "테스트상품", "설명", 1000L, 10,
                ProductStatus.ACTIVE, now, later);
        product.decreaseStock(10);

        // when
        product.restoreStock(3);

        // then
        assertThat(product.getStatus()).isEqualTo(ProductStatus.ACTIVE);
        assertThat(product.getRemainingStock()).isEqualTo(3);
    }

    @Test
    @DisplayName("재고가 남은 ACTIVE 상품을 soldOut 처리하면 INVALID_PRODUCT_DATA 예외가 발생한다")
    void soldOut_재고_남은_상태에서_호출_시_예외() {
        // given — ACTIVE 상태로 생성, 재고 10
        Product product = Product.create(store, "테스트상품", "설명", 1000L, 10,
                ProductStatus.ACTIVE, now, later);

        // when & then
        assertThatThrownBy(product::soldOut)
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode())
                        .isEqualTo(ProductErrorCode.INVALID_PRODUCT_DATA));
    }
}
