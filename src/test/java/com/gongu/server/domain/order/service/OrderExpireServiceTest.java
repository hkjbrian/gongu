package com.gongu.server.domain.order.service;

import com.gongu.server.domain.order.entity.Order;
import com.gongu.server.domain.order.entity.OrderItem;
import com.gongu.server.domain.order.entity.OrderStatus;
import com.gongu.server.domain.order.repository.OrderItemRepository;
import com.gongu.server.domain.order.repository.OrderRepository;
import com.gongu.server.domain.payment.domain.PaymentStatus;
import com.gongu.server.domain.payment.repository.PaymentRepository;
import com.gongu.server.domain.product.entity.Product;
import com.gongu.server.domain.product.entity.ProductStatus;
import com.gongu.server.domain.product.service.StockRedisService;
import com.gongu.server.domain.store.entity.Store;
import com.gongu.server.domain.user.entity.User;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

@ExtendWith(MockitoExtension.class)
class OrderExpireServiceTest {

    @Mock
    private OrderRepository orderRepository;

    @Mock
    private OrderItemRepository orderItemRepository;

    @Mock
    private StockRedisService stockRedisService;

    @Mock
    private PaymentRepository paymentRepository;

    private Timer lockWaitOrderTimer;
    private OrderExpireService orderExpireService;

    @BeforeEach
    void setUp() {
        SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
        lockWaitOrderTimer = Timer.builder("gongu.db.lock.query_duration")
                .tag("entity", "order")
                .register(meterRegistry);
        orderExpireService = new OrderExpireService(
                orderRepository,
                orderItemRepository,
                stockRedisService,
                paymentRepository,
                lockWaitOrderTimer
        );
    }

    @Test
    @DisplayName("만료된_RESERVED_주문_취소_및_재고_복구")
    void cancelExpiredOrder_만료된_RESERVED_주문_취소_및_재고_복구() {
        // given
        LocalDateTime threshold = LocalDateTime.now().minusMinutes(10);
        User user = user(1L);
        Store store = store(1L);
        // totalStock=12, remainingStock을 10으로 설정 (2개 주문 차감된 상태)
        Product product = product(1L, store, 12);
        ReflectionTestUtils.setField(product, "remainingStock", 10);
        Order order = order(1L, user, 10_000L);
        ReflectionTestUtils.setField(order, "createdAt", threshold.minusMinutes(5));
        OrderItem item = orderItem(order, product, 2L);

        given(orderRepository.findByIdWithLock(1L)).willReturn(Optional.of(order));
        given(orderItemRepository.findAllByOrder(order)).willReturn(List.of(item));

        // when
        orderExpireService.cancelExpiredOrder(1L, threshold);

        // then
        assertThat(order.getStatus()).isEqualTo(OrderStatus.CANCELLED);
        verify(stockRedisService).releaseStock(1L, 2);
    }

    @Test
    @DisplayName("이미_PAID_상태_주문_skip")
    void cancelExpiredOrder_이미_PAID_상태_주문_skip() {
        // given
        LocalDateTime threshold = LocalDateTime.now().minusMinutes(10);
        User user = user(1L);
        Order order = order(1L, user, 10_000L);
        ReflectionTestUtils.setField(order, "status", OrderStatus.PAID);
        ReflectionTestUtils.setField(order, "createdAt", threshold.minusMinutes(5));

        given(orderRepository.findByIdWithLock(1L)).willReturn(Optional.of(order));

        // when
        orderExpireService.cancelExpiredOrder(1L, threshold);

        // then
        assertThat(order.getStatus()).isEqualTo(OrderStatus.PAID);
        verify(orderItemRepository, never()).findAllByOrder(order);
    }

    @Test
    @DisplayName("아직_유효한_RESERVED_주문_skip")
    void cancelExpiredOrder_아직_유효한_RESERVED_주문_skip() {
        // given
        LocalDateTime threshold = LocalDateTime.now().minusMinutes(10);
        User user = user(1L);
        Order order = order(1L, user, 10_000L);
        ReflectionTestUtils.setField(order, "createdAt", threshold.plusMinutes(5));

        given(orderRepository.findByIdWithLock(1L)).willReturn(Optional.of(order));

        // when
        orderExpireService.cancelExpiredOrder(1L, threshold);

        // then
        assertThat(order.getStatus()).isEqualTo(OrderStatus.RESERVED);
        verify(orderItemRepository, never()).findAllByOrder(order);
    }

    @Test
    @DisplayName("존재하지_않는_Order_조기_return_예외_없음")
    void cancelExpiredOrder_존재하지_않는_Order_조기_return_예외_없음() {
        // given
        LocalDateTime threshold = LocalDateTime.now().minusMinutes(10);
        given(orderRepository.findByIdWithLock(999L)).willReturn(Optional.empty());

        // when & then
        assertThatCode(() -> orderExpireService.cancelExpiredOrder(999L, threshold))
                .doesNotThrowAnyException();
        verifyNoInteractions(orderItemRepository);
    }

    @Test
    @DisplayName("락 후 PENDING Payment 존재 시 취소 skip")
    void cancelExpiredOrder_락_후_PENDING_Payment_존재_시_skip() {
        // given
        LocalDateTime threshold = LocalDateTime.now().minusMinutes(10);
        User user = user(1L);
        Order order = order(1L, user, 10_000L);
        ReflectionTestUtils.setField(order, "createdAt", threshold.minusMinutes(5));

        given(orderRepository.findByIdWithLock(1L)).willReturn(Optional.of(order));
        given(paymentRepository.existsByOrderIdAndStatusIn(
                1L, List.of(PaymentStatus.PENDING)
        )).willReturn(true);

        // when
        orderExpireService.cancelExpiredOrder(1L, threshold);

        // then
        assertThat(order.getStatus()).isEqualTo(OrderStatus.RESERVED);
        verify(orderItemRepository, never()).findAllByOrder(order);
    }

    // --- fixture helpers ---

    private User user(Long id) {
        User user = User.of("홍길동" + id, "010-1234-567" + id);
        setId(user, id);
        return user;
    }

    private Store store(Long id) {
        Store store = Store.create("매장" + id, "서울시 강남구", "02-1234-5678");
        setId(store, id);
        return store;
    }

    private Product product(Long id, Store store, int totalStock) {
        Product product = Product.create(
                store, "상품" + id, "상품 설명", 10_000L, totalStock,
                ProductStatus.ACTIVE,
                LocalDateTime.now().minusDays(1), LocalDateTime.now().plusDays(1)
        );
        setId(product, id);
        return product;
    }

    private Order order(Long id, User user, long totalPrice) {
        Order order = Order.create(user, totalPrice);
        setId(order, id);
        return order;
    }

    private OrderItem orderItem(Order order, Product product, Long quantity) {
        OrderItem item = OrderItem.create(order, product, quantity);
        setId(item, 1L);
        return item;
    }

    private void setId(Object target, Long id) {
        ReflectionTestUtils.setField(target, "id", id);
    }
}
