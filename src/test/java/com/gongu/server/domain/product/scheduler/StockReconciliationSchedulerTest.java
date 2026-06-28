package com.gongu.server.domain.product.scheduler;

import com.gongu.server.domain.order.entity.OrderStatus;
import com.gongu.server.domain.order.repository.OrderItemRepository;
import com.gongu.server.domain.product.entity.Product;
import com.gongu.server.domain.product.entity.ProductStatus;
import com.gongu.server.domain.product.repository.ProductRepository;
import com.gongu.server.domain.product.service.StockRedisService;
import com.gongu.server.domain.store.entity.Store;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

@ExtendWith(MockitoExtension.class)
class StockReconciliationSchedulerTest {

    @Mock
    private ProductRepository productRepository;

    @Mock
    private OrderItemRepository orderItemRepository;

    @Mock
    private StockRedisService stockRedisService;

    @Mock
    private StockReconciliationHelper reconciliationHelper;

    private StockReconciliationHelper helper;
    private StockReconciliationScheduler scheduler;

    @BeforeEach
    void setUp() {
        helper = new StockReconciliationHelper(productRepository, orderItemRepository, stockRedisService);
        scheduler = new StockReconciliationScheduler(productRepository, reconciliationHelper);
    }

    @Test
    @DisplayName("reconcileOne_Redis값_정상_일치")
    void reconcileOne_Redis값_정상_일치() {
        // given
        Product product = product(1L, 10);

        given(productRepository.findById(1L)).willReturn(Optional.of(product));
        given(orderItemRepository.sumQuantityByProductIdAndOrderStatus(1L, OrderStatus.RESERVED)).willReturn(3L);
        given(stockRedisService.getCurrentStock(1L)).willReturn(7L);

        // when
        helper.reconcileOne(1L);

        // then
        verify(stockRedisService, never()).releaseStock(org.mockito.ArgumentMatchers.anyLong(), org.mockito.ArgumentMatchers.anyInt());
        verify(stockRedisService, never()).initializeStock(org.mockito.ArgumentMatchers.anyLong(), org.mockito.ArgumentMatchers.anyInt());
    }

    @Test
    @DisplayName("reconcileOne_Redis_낮음_INCR_보정")
    void reconcileOne_Redis_낮음_INCR_보정() {
        // given
        Product product = product(1L, 10);

        given(productRepository.findById(1L)).willReturn(Optional.of(product));
        given(orderItemRepository.sumQuantityByProductIdAndOrderStatus(1L, OrderStatus.RESERVED)).willReturn(3L);
        given(stockRedisService.getCurrentStock(1L)).willReturn(4L);

        // when
        helper.reconcileOne(1L);

        // then
        verify(stockRedisService).releaseStock(1L, 3);
    }

    @Test
    @DisplayName("reconcileOne_Redis_키_없음_초기화")
    void reconcileOne_Redis_키_없음_초기화() {
        // given
        Product product = product(1L, 10);

        given(productRepository.findById(1L)).willReturn(Optional.of(product));
        given(orderItemRepository.sumQuantityByProductIdAndOrderStatus(1L, OrderStatus.RESERVED)).willReturn(3L);
        given(stockRedisService.getCurrentStock(1L)).willReturn(null);

        // when
        helper.reconcileOne(1L);

        // then
        verify(stockRedisService).initializeStock(1L, 7);
    }

    @Test
    @DisplayName("reconcileOne_Redis_높음_DECR_안함")
    void reconcileOne_Redis_높음_DECR_안함() {
        // given
        Product product = product(1L, 10);

        given(productRepository.findById(1L)).willReturn(Optional.of(product));
        given(orderItemRepository.sumQuantityByProductIdAndOrderStatus(1L, OrderStatus.RESERVED)).willReturn(3L);
        given(stockRedisService.getCurrentStock(1L)).willReturn(9L);

        // when
        helper.reconcileOne(1L);

        // then
        verify(stockRedisService, never()).releaseStock(org.mockito.ArgumentMatchers.anyLong(), org.mockito.ArgumentMatchers.anyInt());
        verify(stockRedisService, never()).initializeStock(org.mockito.ArgumentMatchers.anyLong(), org.mockito.ArgumentMatchers.anyInt());
    }

    @Test
    @DisplayName("reconcileOne_상품_없으면_아무것도_안함")
    void reconcileOne_상품_없으면_아무것도_안함() {
        // given
        given(productRepository.findById(1L)).willReturn(Optional.empty());

        // when
        helper.reconcileOne(1L);

        // then
        verifyNoInteractions(orderItemRepository, stockRedisService);
    }

    @Test
    @DisplayName("reconcile_스케줄러_전체_상품_순회")
    void reconcile_스케줄러_전체_상품_순회() {
        // given
        Product product1 = product(1L, 10);
        Product product2 = product(2L, 20);

        given(productRepository.findAllByStatus(ProductStatus.ACTIVE)).willReturn(List.of(product1, product2));

        // when
        scheduler.reconcile();

        // then
        verify(reconciliationHelper).reconcileOne(1L);
        verify(reconciliationHelper).reconcileOne(2L);
    }

    @Test
    @DisplayName("reconcile_단건_실패해도_나머지_처리")
    void reconcile_단건_실패해도_나머지_처리() {
        // given
        Product product1 = product(1L, 10);
        Product product2 = product(2L, 20);

        given(productRepository.findAllByStatus(ProductStatus.ACTIVE)).willReturn(List.of(product1, product2));
        willThrow(new RuntimeException("재조정 실패")).given(reconciliationHelper).reconcileOne(1L);

        // when
        scheduler.reconcile();

        // then
        verify(reconciliationHelper).reconcileOne(1L);
        verify(reconciliationHelper).reconcileOne(2L);
    }

    private Product product(Long id, int remainingStock) {
        Store store = Store.create("매장" + id, "서울시 강남구", "02-1234-5678");
        Product product = Product.create(
                store,
                "상품" + id,
                "상품 설명",
                10_000L,
                remainingStock,
                ProductStatus.ACTIVE,
                LocalDateTime.now().minusDays(1),
                LocalDateTime.now().plusDays(1)
        );
        ReflectionTestUtils.setField(product, "id", id);
        return product;
    }
}
