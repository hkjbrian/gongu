package com.gongu.server.domain.payment.service;

import com.gongu.server.domain.order.entity.Order;
import com.gongu.server.domain.order.entity.OrderItem;
import com.gongu.server.domain.order.entity.OrderStatus;
import com.gongu.server.domain.order.repository.OrderItemRepository;
import com.gongu.server.domain.order.repository.OrderRepository;
import com.gongu.server.domain.payment.domain.Payment;
import com.gongu.server.domain.payment.domain.PaymentStatus;
import com.gongu.server.domain.payment.repository.PaymentRepository;
import com.gongu.server.domain.product.entity.Product;
import com.gongu.server.domain.product.entity.ProductStatus;
import com.gongu.server.domain.product.repository.ProductRepository;
import com.gongu.server.domain.store.entity.Store;
import com.gongu.server.domain.user.entity.User;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
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
class PaymentExpireServiceTest {

    @Mock
    private PaymentRepository paymentRepository;

    @Mock
    private OrderRepository orderRepository;

    @Mock
    private OrderItemRepository orderItemRepository;

    @Mock
    private ProductRepository productRepository;

    @InjectMocks
    private PaymentExpireService paymentExpireService;

    @Test
    @DisplayName("만료된_PENDING_Payment_취소_및_Order_취소_재고_복구")
    void cancelExpiredPayment_만료된_PENDING_Payment_취소_및_Order_취소_재고_복구() {
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
        Payment payment = payment(order);

        given(paymentRepository.findByIdWithLock(1L)).willReturn(Optional.of(payment));
        given(orderRepository.findByIdWithLock(1L)).willReturn(Optional.of(order));
        given(orderItemRepository.findAllByOrder(order)).willReturn(List.of(item));
        given(productRepository.findByIdWithLock(1L)).willReturn(Optional.of(product));

        // when
        paymentExpireService.cancelExpiredPayment(1L, threshold);

        // then
        assertThat(payment.getStatus()).isEqualTo(PaymentStatus.CANCELLED);
        assertThat(order.getStatus()).isEqualTo(OrderStatus.CANCELLED);
        assertThat(product.getRemainingStock()).isEqualTo(12);
    }

    @Test
    @DisplayName("이미_PAID된_Payment_skip")
    void cancelExpiredPayment_이미_PAID된_Payment_skip() {
        // given
        LocalDateTime threshold = LocalDateTime.now().minusMinutes(10);
        User user = user(1L);
        Order order = order(1L, user, 10_000L);
        Payment payment = payment(order);
        ReflectionTestUtils.setField(payment, "status", PaymentStatus.PAID);

        given(paymentRepository.findByIdWithLock(1L)).willReturn(Optional.of(payment));

        // when
        paymentExpireService.cancelExpiredPayment(1L, threshold);

        // then
        assertThat(payment.getStatus()).isEqualTo(PaymentStatus.PAID);
        verify(orderRepository, never()).findByIdWithLock(order.getId());
    }

    @Test
    @DisplayName("Order가_이미_PAID_상태_skip")
    void cancelExpiredPayment_Order가_이미_PAID_상태_skip() {
        // given
        LocalDateTime threshold = LocalDateTime.now().minusMinutes(10);
        User user = user(1L);
        Order order = order(1L, user, 10_000L);
        ReflectionTestUtils.setField(order, "status", OrderStatus.PAID);
        ReflectionTestUtils.setField(order, "createdAt", threshold.minusMinutes(5));
        Payment payment = payment(order);

        given(paymentRepository.findByIdWithLock(1L)).willReturn(Optional.of(payment));
        given(orderRepository.findByIdWithLock(1L)).willReturn(Optional.of(order));

        // when
        paymentExpireService.cancelExpiredPayment(1L, threshold);

        // then
        assertThat(order.getStatus()).isEqualTo(OrderStatus.PAID);
        assertThat(payment.getStatus()).isEqualTo(PaymentStatus.PENDING);
        verify(orderItemRepository, never()).findAllByOrder(order);
    }

    @Test
    @DisplayName("아직_유효한_Payment_skip")
    void cancelExpiredPayment_아직_유효한_Payment_skip() {
        // given
        LocalDateTime threshold = LocalDateTime.now().minusMinutes(10);
        User user = user(1L);
        Order order = order(1L, user, 10_000L);
        ReflectionTestUtils.setField(order, "createdAt", threshold.plusMinutes(5));
        Payment payment = payment(order);

        given(paymentRepository.findByIdWithLock(1L)).willReturn(Optional.of(payment));
        given(orderRepository.findByIdWithLock(1L)).willReturn(Optional.of(order));

        // when
        paymentExpireService.cancelExpiredPayment(1L, threshold);

        // then
        assertThat(payment.getStatus()).isEqualTo(PaymentStatus.PENDING);
        assertThat(order.getStatus()).isEqualTo(OrderStatus.RESERVED);
        verify(orderItemRepository, never()).findAllByOrder(order);
    }

    @Test
    @DisplayName("존재하지_않는_Payment_예외_없음")
    void cancelExpiredPayment_존재하지_않는_Payment_예외_없음() {
        // given
        LocalDateTime threshold = LocalDateTime.now().minusMinutes(10);
        given(paymentRepository.findByIdWithLock(999L)).willReturn(Optional.empty());

        // when & then
        assertThatCode(() -> paymentExpireService.cancelExpiredPayment(999L, threshold))
                .doesNotThrowAnyException();
        verifyNoInteractions(orderRepository);
        verifyNoInteractions(orderItemRepository);
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

    private Payment payment(Order order) {
        Payment p = Payment.initiate(order, "idem-key", "pay-uuid", order.getTotalPrice());
        setId(p, 1L);
        return p;
    }

    private void setId(Object target, Long id) {
        ReflectionTestUtils.setField(target, "id", id);
    }
}
