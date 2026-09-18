package com.gongu.server.domain.payment.service;

import com.gongu.server.domain.order.entity.Order;
import com.gongu.server.domain.order.entity.OrderItem;
import com.gongu.server.domain.order.entity.OrderStatus;
import com.gongu.server.domain.order.repository.OrderItemRepository;
import com.gongu.server.domain.order.repository.OrderRepository;
import com.gongu.server.domain.payment.domain.Payment;
import com.gongu.server.domain.payment.domain.PaymentHistoryTrigger;
import com.gongu.server.domain.payment.domain.PaymentStatus;
import com.gongu.server.domain.payment.repository.PaymentRepository;
import com.gongu.server.domain.product.entity.Product;
import com.gongu.server.domain.product.entity.ProductStatus;
import com.gongu.server.domain.product.service.StockRedisService;
import com.gongu.server.domain.store.entity.Store;
import com.gongu.server.domain.user.entity.User;
import io.micrometer.core.instrument.Counter;
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
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

@ExtendWith(MockitoExtension.class)
class PaymentExpireReconcilerTest {

    @Mock
    private PaymentRepository paymentRepository;

    @Mock
    private OrderRepository orderRepository;

    @Mock
    private OrderItemRepository orderItemRepository;

    @Mock
    private StockRedisService stockRedisService;

    @Mock
    private PaymentHistoryRecorder paymentHistoryRecorder;

    @Mock
    private Counter paymentExpiryReconcileExhaustedCounter;

    @InjectMocks
    private PaymentExpireReconciler reconciler;

    private static final int MAX_ATTEMPTS = 3;

    @Test
    @DisplayName("RESERVED_주문은_취소되고_Redis_재고가_해제된다")
    void cancelOrderAfterPgConfirmedUnpaid_RESERVED_주문_취소_및_재고_해제() {
        // given
        User user = user(1L);
        Store store = store(1L);
        Product product = product(1L, store, 10);
        Order order = order(1L, user, 10_000L);
        OrderItem item = orderItem(order, product, 2L);

        given(orderRepository.findByIdWithLock(1L)).willReturn(Optional.of(order));
        given(orderItemRepository.findAllByOrder(order)).willReturn(List.of(item));

        // when
        reconciler.cancelOrderAfterPgConfirmedUnpaid(1L);

        // then
        assertThat(order.getStatus()).isEqualTo(OrderStatus.CANCELLED);
        verify(stockRedisService).releaseStockAfterCommit(1L, 2);
    }

    @Test
    @DisplayName("이미_RESERVED가_아닌_주문은_건드리지_않는다")
    void cancelOrderAfterPgConfirmedUnpaid_이미_RESERVED_아님_skip() {
        // given
        User user = user(1L);
        Order order = order(1L, user, 10_000L);
        ReflectionTestUtils.setField(order, "status", OrderStatus.PAID);

        given(orderRepository.findByIdWithLock(1L)).willReturn(Optional.of(order));

        // when
        reconciler.cancelOrderAfterPgConfirmedUnpaid(1L);

        // then
        assertThat(order.getStatus()).isEqualTo(OrderStatus.PAID);
        verifyNoInteractions(orderItemRepository);
        verifyNoInteractions(stockRedisService);
    }

    @Test
    @DisplayName("존재하지_않는_주문은_예외_없이_반환한다")
    void cancelOrderAfterPgConfirmedUnpaid_존재하지_않는_주문_예외_없음() {
        // given
        given(orderRepository.findByIdWithLock(999L)).willReturn(Optional.empty());

        // when
        reconciler.cancelOrderAfterPgConfirmedUnpaid(999L);

        // then
        verifyNoInteractions(orderItemRepository);
        verifyNoInteractions(stockRedisService);
    }

    @Test
    @DisplayName("한도에_도달하지_않으면_시도_횟수만_증가하고_카운터·이력은_남기지_않는다")
    void recordInconclusiveAttempt_한도_미도달_시_횟수만_증가() {
        // given
        User user = user(1L);
        Order order = order(1L, user, 10_000L);
        Payment payment = payment(order);
        ReflectionTestUtils.setField(payment, "expiryCheckAttempts", 0);

        given(paymentRepository.findByIdWithLock(1L)).willReturn(Optional.of(payment));

        // when
        reconciler.recordInconclusiveAttempt(1L, MAX_ATTEMPTS);

        // then
        assertThat(payment.getExpiryCheckAttempts()).isEqualTo(1);
        verifyNoInteractions(paymentExpiryReconcileExhaustedCounter);
        verifyNoInteractions(paymentHistoryRecorder);
    }

    @Test
    @DisplayName("한도에_도달하면_소진_카운터를_올리고_이력을_남긴다")
    void recordInconclusiveAttempt_한도_도달_시_소진_카운터_및_이력() {
        // given
        User user = user(1L);
        Order order = order(1L, user, 10_000L);
        Payment payment = payment(order);
        ReflectionTestUtils.setField(payment, "expiryCheckAttempts", MAX_ATTEMPTS - 1);

        given(paymentRepository.findByIdWithLock(1L)).willReturn(Optional.of(payment));

        // when
        reconciler.recordInconclusiveAttempt(1L, MAX_ATTEMPTS);

        // then
        assertThat(payment.getExpiryCheckAttempts()).isEqualTo(MAX_ATTEMPTS);
        verify(paymentExpiryReconcileExhaustedCounter).increment();
        verify(paymentHistoryRecorder).record(payment, PaymentStatus.PENDING, PaymentStatus.PENDING,
                PaymentHistoryTrigger.EXPIRY_SCHEDULER, "PG 조회 한도 초과(3회) - 운영자 확인 필요", null);
    }

    @Test
    @DisplayName("그_사이_PENDING을_벗어난_Payment는_증가시키지_않는다")
    void recordInconclusiveAttempt_이미_PENDING_아님_skip() {
        // given
        User user = user(1L);
        Order order = order(1L, user, 10_000L);
        Payment payment = payment(order);
        ReflectionTestUtils.setField(payment, "status", PaymentStatus.PAID);
        ReflectionTestUtils.setField(payment, "expiryCheckAttempts", 0);

        given(paymentRepository.findByIdWithLock(1L)).willReturn(Optional.of(payment));

        // when
        reconciler.recordInconclusiveAttempt(1L, MAX_ATTEMPTS);

        // then
        assertThat(payment.getExpiryCheckAttempts()).isEqualTo(0);
        verifyNoInteractions(paymentExpiryReconcileExhaustedCounter);
        verifyNoInteractions(paymentHistoryRecorder);
    }

    @Test
    @DisplayName("존재하지_않는_Payment는_예외_없이_반환한다")
    void recordInconclusiveAttempt_존재하지_않는_Payment_예외_없음() {
        // given
        given(paymentRepository.findByIdWithLock(999L)).willReturn(Optional.empty());

        // when
        reconciler.recordInconclusiveAttempt(999L, MAX_ATTEMPTS);

        // then
        verifyNoInteractions(paymentExpiryReconcileExhaustedCounter);
        verifyNoInteractions(paymentHistoryRecorder);
    }

    @Test
    @DisplayName("PENDING_Payment와_RESERVED_Order는_한_트랜잭션에서_함께_정산된다")
    void settleUnconfirmedPayment_PENDING_결제와_RESERVED_주문_함께_정산() {
        // given
        User user = user(1L);
        Store store = store(1L);
        Product product = product(1L, store, 10);
        Order order = order(1L, user, 10_000L);
        OrderItem item = orderItem(order, product, 2L);
        Payment payment = payment(order);

        given(paymentRepository.findByIdWithLock(1L)).willReturn(Optional.of(payment));
        given(orderRepository.findByIdWithLock(1L)).willReturn(Optional.of(order));
        given(orderItemRepository.findAllByOrder(order)).willReturn(List.of(item));

        // when
        reconciler.settleUnconfirmedPayment(1L, 1L);

        // then
        assertThat(payment.getStatus()).isEqualTo(PaymentStatus.CANCELLED);
        verify(paymentHistoryRecorder).record(payment, PaymentStatus.PENDING, PaymentStatus.CANCELLED,
                PaymentHistoryTrigger.EXPIRY_SCHEDULER, "PG 미확인 - 만료 취소", null);
        assertThat(order.getStatus()).isEqualTo(OrderStatus.CANCELLED);
        verify(stockRedisService).releaseStockAfterCommit(1L, 2);
    }

    @Test
    @DisplayName("이미_PENDING을_벗어난_Payment는_건드리지_않지만_RESERVED_Order는_그대로_정리한다")
    void settleUnconfirmedPayment_이미_PENDING_아닌_Payment는_skip_주문은_정리() {
        // given — 레이스: completePayment 등 다른 경로가 이미 Payment를 처리했지만 Order는 아직 RESERVED로 남은 상황
        User user = user(1L);
        Store store = store(1L);
        Product product = product(1L, store, 10);
        Order order = order(1L, user, 10_000L);
        OrderItem item = orderItem(order, product, 2L);
        Payment payment = payment(order);
        ReflectionTestUtils.setField(payment, "status", PaymentStatus.PAID);

        given(paymentRepository.findByIdWithLock(1L)).willReturn(Optional.of(payment));
        given(orderRepository.findByIdWithLock(1L)).willReturn(Optional.of(order));
        given(orderItemRepository.findAllByOrder(order)).willReturn(List.of(item));

        // when
        reconciler.settleUnconfirmedPayment(1L, 1L);

        // then
        assertThat(payment.getStatus()).isEqualTo(PaymentStatus.PAID);
        verifyNoInteractions(paymentHistoryRecorder);
        assertThat(order.getStatus()).isEqualTo(OrderStatus.CANCELLED);
        verify(stockRedisService).releaseStockAfterCommit(1L, 2);
    }

    @Test
    @DisplayName("존재하지_않는_주문은_Payment_정산과_무관하게_예외_없이_반환한다")
    void settleUnconfirmedPayment_존재하지_않는_주문_예외_없음() {
        // given
        User user = user(1L);
        Order order = order(1L, user, 10_000L);
        Payment payment = payment(order);

        given(paymentRepository.findByIdWithLock(1L)).willReturn(Optional.of(payment));
        given(orderRepository.findByIdWithLock(1L)).willReturn(Optional.empty());

        // when
        reconciler.settleUnconfirmedPayment(1L, 1L);

        // then
        assertThat(payment.getStatus()).isEqualTo(PaymentStatus.CANCELLED);
        verifyNoInteractions(orderItemRepository);
        verifyNoInteractions(stockRedisService);
    }

    @Test
    @DisplayName("이미_RESERVED가_아닌_주문은_건드리지_않지만_Payment는_정산한다")
    void settleUnconfirmedPayment_이미_RESERVED_아닌_주문은_skip_결제는_정산() {
        // given
        User user = user(1L);
        Order order = order(1L, user, 10_000L);
        ReflectionTestUtils.setField(order, "status", OrderStatus.PAID);
        Payment payment = payment(order);

        given(paymentRepository.findByIdWithLock(1L)).willReturn(Optional.of(payment));
        given(orderRepository.findByIdWithLock(1L)).willReturn(Optional.of(order));

        // when
        reconciler.settleUnconfirmedPayment(1L, 1L);

        // then
        assertThat(payment.getStatus()).isEqualTo(PaymentStatus.CANCELLED);
        assertThat(order.getStatus()).isEqualTo(OrderStatus.PAID);
        verifyNoInteractions(orderItemRepository);
        verifyNoInteractions(stockRedisService);
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
