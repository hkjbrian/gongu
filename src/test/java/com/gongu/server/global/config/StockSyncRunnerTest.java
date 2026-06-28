package com.gongu.server.global.config;

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

import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class StockSyncRunnerTest {

    @Mock
    private ProductRepository productRepository;

    @Mock
    private OrderItemRepository orderItemRepository;

    @Mock
    private StockRedisService stockRedisService;

    private StockSyncRunner stockSyncRunner;

    @BeforeEach
    void setUp() {
        stockSyncRunner = new StockSyncRunner(productRepository, orderItemRepository, stockRedisService);
    }

    @Test
    @DisplayName("startup_sync_ACTIVE_상품_Redis_정상_세팅")
    void startup_sync_ACTIVE_상품_Redis_정상_세팅() {
        // given
        Product product1 = product(1L, 10);
        Product product2 = product(2L, 20);

        given(productRepository.findAllByStatus(ProductStatus.ACTIVE)).willReturn(List.of(product1, product2));
        given(orderItemRepository.sumQuantityByProductIdAndOrderStatus(1L, OrderStatus.RESERVED)).willReturn(3L);
        given(orderItemRepository.sumQuantityByProductIdAndOrderStatus(2L, OrderStatus.RESERVED)).willReturn(5L);

        // when
        stockSyncRunner.run(null);

        // then
        verify(stockRedisService).initializeStock(1L, 7);
        verify(stockRedisService).initializeStock(2L, 15);
    }

    @Test
    @DisplayName("startup_sync_RESERVED_주문_없는_경우_remainingStock_그대로")
    void startup_sync_RESERVED_주문_없는_경우_remainingStock_그대로() {
        // given
        Product product = product(1L, 10);

        given(productRepository.findAllByStatus(ProductStatus.ACTIVE)).willReturn(List.of(product));
        given(orderItemRepository.sumQuantityByProductIdAndOrderStatus(1L, OrderStatus.RESERVED)).willReturn(null);

        // when
        stockSyncRunner.run(null);

        // then
        verify(stockRedisService).initializeStock(1L, 10);
    }

    @Test
    @DisplayName("startup_sync_ACTIVE_상품_없으면_아무것도_안함")
    void startup_sync_ACTIVE_상품_없으면_아무것도_안함() {
        // given
        given(productRepository.findAllByStatus(ProductStatus.ACTIVE)).willReturn(List.of());

        // when
        stockSyncRunner.run(null);

        // then
        verify(stockRedisService, never()).initializeStock(org.mockito.ArgumentMatchers.anyLong(), org.mockito.ArgumentMatchers.anyInt());
    }

    @Test
    @DisplayName("startup_sync_단건_실패해도_나머지_처리_계속")
    void startup_sync_단건_실패해도_나머지_처리_계속() {
        // given
        Product product1 = product(1L, 10);
        Product product2 = product(2L, 20);

        given(productRepository.findAllByStatus(ProductStatus.ACTIVE)).willReturn(List.of(product1, product2));
        given(orderItemRepository.sumQuantityByProductIdAndOrderStatus(1L, OrderStatus.RESERVED)).willReturn(3L);
        given(orderItemRepository.sumQuantityByProductIdAndOrderStatus(2L, OrderStatus.RESERVED)).willReturn(5L);
        willThrow(new RuntimeException("Redis 실패")).given(stockRedisService).initializeStock(1L, 7);

        // when
        stockSyncRunner.run(null);

        // then
        verify(stockRedisService).initializeStock(1L, 7);
        verify(stockRedisService).initializeStock(2L, 15);
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
