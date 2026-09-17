package com.gongu.server.domain.payment.service;

import com.gongu.server.domain.order.entity.Order;
import com.gongu.server.domain.order.entity.OrderStatus;
import com.gongu.server.domain.payment.domain.Payment;
import com.gongu.server.domain.payment.domain.PaymentHistoryTrigger;
import com.gongu.server.domain.payment.domain.PaymentStatus;
import com.gongu.server.domain.payment.dto.response.VerifyPaymentResponse;
import com.gongu.server.domain.payment.repository.PaymentRepository;
import com.gongu.server.domain.user.entity.User;
import com.gongu.server.global.exception.BusinessException;
import com.gongu.server.global.exception.InfraException;
import com.gongu.server.global.exception.errorcode.PaymentErrorCode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

@ExtendWith(MockitoExtension.class)
class PaymentExpireServiceTest {

    @Mock
    private PaymentRepository paymentRepository;

    @Mock
    private PaymentService paymentService;

    @Mock
    private PaymentExpireReconciler reconciler;

    @InjectMocks
    private PaymentExpireService paymentExpireService;

    private static final int MAX_ATTEMPTS = 3;

    @Test
    @DisplayName("PG가_PAID를_확인하면_completePayment로_정상_확정되고_reconciler는_건드리지_않는다")
    void reconcileExpiredPayment_PG_PAID_확인_시_정상_확정() {
        // given
        User user = user(1L);
        Order order = order(1L, user, 10_000L);
        Payment payment = payment(order);
        given(paymentRepository.findById(1L)).willReturn(Optional.of(payment));
        given(paymentService.completePayment("pay-uuid", PaymentHistoryTrigger.EXPIRY_SCHEDULER))
                .willReturn(new VerifyPaymentResponse(1L, "pay-uuid", 10_000L, PaymentStatus.PAID, null, OrderStatus.PAID));

        // when
        paymentExpireService.reconcileExpiredPayment(1L, MAX_ATTEMPTS);

        // then
        verify(paymentService).completePayment("pay-uuid", PaymentHistoryTrigger.EXPIRY_SCHEDULER);
        verifyNoInteractions(reconciler);
    }

    @Test
    @DisplayName("PG가_결제_안됨을_확정하면_reconciler에_주문_취소를_위임한다")
    void reconcileExpiredPayment_PG_결제_안됨_확정_시_주문_취소_위임() {
        // given
        User user = user(1L);
        Order order = order(1L, user, 10_000L);
        Payment payment = payment(order);

        given(paymentRepository.findById(1L)).willReturn(Optional.of(payment));
        willThrow(new BusinessException(PaymentErrorCode.PAYMENT_NOT_COMPLETED))
                .given(paymentService).completePayment("pay-uuid", PaymentHistoryTrigger.EXPIRY_SCHEDULER);

        // when
        paymentExpireService.reconcileExpiredPayment(1L, MAX_ATTEMPTS);

        // then
        verify(reconciler).cancelOrderAfterPgConfirmedUnpaid(1L);
    }

    @Test
    @DisplayName("PG_조회_자체가_실패(InfraException)하면_reconciler에_재시도_기록을_위임하고_예외를_전파한다")
    void reconcileExpiredPayment_PG_조회_실패_InfraException_시_재시도_기록_위임() {
        // given
        User user = user(1L);
        Order order = order(1L, user, 10_000L);
        Payment payment = payment(order);

        given(paymentRepository.findById(1L)).willReturn(Optional.of(payment));
        willThrow(new InfraException(PaymentErrorCode.PAYMENT_PG_UNAVAILABLE))
                .given(paymentService).completePayment("pay-uuid", PaymentHistoryTrigger.EXPIRY_SCHEDULER);

        // when & then
        assertThatThrownBy(() -> paymentExpireService.reconcileExpiredPayment(1L, MAX_ATTEMPTS))
                .isInstanceOf(InfraException.class);

        verify(reconciler).recordInconclusiveAttempt(1L, MAX_ATTEMPTS);
    }

    @Test
    @DisplayName("PG_응답이_비어_판정_불가(BusinessException_PAYMENT_PG_UNAVAILABLE)하면_reconciler에_결제_확정_안됨_정산을_위임하고_예외를_전파하지_않는다")
    void reconcileExpiredPayment_PG_응답_없음_판정_불가_시_결제_정산_위임() {
        // given
        User user = user(1L);
        Order order = order(1L, user, 10_000L);
        Payment payment = payment(order);

        given(paymentRepository.findById(1L)).willReturn(Optional.of(payment));
        willThrow(new BusinessException(PaymentErrorCode.PAYMENT_PG_UNAVAILABLE))
                .given(paymentService).completePayment("pay-uuid", PaymentHistoryTrigger.EXPIRY_SCHEDULER);

        // when & then
        assertThatCode(() -> paymentExpireService.reconcileExpiredPayment(1L, MAX_ATTEMPTS))
                .doesNotThrowAnyException();

        verify(reconciler).settleUnconfirmedPayment(1L, 1L);
    }

    @Test
    @DisplayName("PortOneClient가_4xx를_PAYMENT_NOT_FOUND로_뭉뚱그려_던지면_reconciler에_결제_확정_안됨_정산을_위임한다")
    void reconcileExpiredPayment_PAYMENT_NOT_FOUND_시_결제_정산_위임() {
        // given
        User user = user(1L);
        Order order = order(1L, user, 10_000L);
        Payment payment = payment(order);

        given(paymentRepository.findById(1L)).willReturn(Optional.of(payment));
        willThrow(new BusinessException(PaymentErrorCode.PAYMENT_NOT_FOUND))
                .given(paymentService).completePayment("pay-uuid", PaymentHistoryTrigger.EXPIRY_SCHEDULER);

        // when & then
        assertThatCode(() -> paymentExpireService.reconcileExpiredPayment(1L, MAX_ATTEMPTS))
                .doesNotThrowAnyException();

        verify(reconciler).settleUnconfirmedPayment(1L, 1L);
    }

    @Test
    @DisplayName("completePayment가_ORDER_EXPIRED_REFUNDED로_스스로_정리를_끝낸_경우_reconciler는_아무것도_하지_않는다")
    void reconcileExpiredPayment_ORDER_EXPIRED_REFUNDED_시_reconciler_no_op() {
        // given
        User user = user(1L);
        Order order = order(1L, user, 10_000L);
        Payment payment = payment(order);

        given(paymentRepository.findById(1L)).willReturn(Optional.of(payment));
        willThrow(new BusinessException(PaymentErrorCode.ORDER_EXPIRED_REFUNDED))
                .given(paymentService).completePayment("pay-uuid", PaymentHistoryTrigger.EXPIRY_SCHEDULER);

        // when & then
        assertThatCode(() -> paymentExpireService.reconcileExpiredPayment(1L, MAX_ATTEMPTS))
                .doesNotThrowAnyException();

        verifyNoInteractions(reconciler);
    }

    @Test
    @DisplayName("completePayment가_PAYMENT_AMOUNT_MISMATCH로_스스로_정리를_끝낸_경우_reconciler는_아무것도_하지_않는다")
    void reconcileExpiredPayment_PAYMENT_AMOUNT_MISMATCH_시_reconciler_no_op() {
        // given
        User user = user(1L);
        Order order = order(1L, user, 10_000L);
        Payment payment = payment(order);

        given(paymentRepository.findById(1L)).willReturn(Optional.of(payment));
        willThrow(new BusinessException(PaymentErrorCode.PAYMENT_AMOUNT_MISMATCH))
                .given(paymentService).completePayment("pay-uuid", PaymentHistoryTrigger.EXPIRY_SCHEDULER);

        // when & then
        assertThatCode(() -> paymentExpireService.reconcileExpiredPayment(1L, MAX_ATTEMPTS))
                .doesNotThrowAnyException();

        verifyNoInteractions(reconciler);
    }

    @Test
    @DisplayName("이미_한도를_초과한_Payment는_completePayment도_reconciler도_호출하지_않는다")
    void reconcileExpiredPayment_이미_한도_초과_skip() {
        // given
        User user = user(1L);
        Order order = order(1L, user, 10_000L);
        Payment payment = payment(order);
        ReflectionTestUtils.setField(payment, "expiryCheckAttempts", MAX_ATTEMPTS);

        given(paymentRepository.findById(1L)).willReturn(Optional.of(payment));

        // when
        assertThatCode(() -> paymentExpireService.reconcileExpiredPayment(1L, MAX_ATTEMPTS))
                .doesNotThrowAnyException();

        // then
        verifyNoInteractions(paymentService);
        verifyNoInteractions(reconciler);
    }

    @Test
    @DisplayName("이미_PAID된_Payment는_completePayment를_호출하지_않는다")
    void reconcileExpiredPayment_이미_PAID_skip() {
        // given
        User user = user(1L);
        Order order = order(1L, user, 10_000L);
        Payment payment = payment(order);
        ReflectionTestUtils.setField(payment, "status", PaymentStatus.PAID);

        given(paymentRepository.findById(1L)).willReturn(Optional.of(payment));

        // when
        paymentExpireService.reconcileExpiredPayment(1L, MAX_ATTEMPTS);

        // then
        verifyNoInteractions(paymentService);
        verifyNoInteractions(reconciler);
    }

    @Test
    @DisplayName("존재하지_않는_Payment_예외_없음")
    void reconcileExpiredPayment_존재하지_않는_Payment_예외_없음() {
        // given
        given(paymentRepository.findById(999L)).willReturn(Optional.empty());

        // when & then
        assertThatCode(() -> paymentExpireService.reconcileExpiredPayment(999L, MAX_ATTEMPTS))
                .doesNotThrowAnyException();
        verifyNoInteractions(paymentService);
        verifyNoInteractions(reconciler);
    }

    // --- fixture helpers ---

    private User user(Long id) {
        User user = User.of("홍길동" + id, "010-1234-567" + id);
        setId(user, id);
        return user;
    }

    private Order order(Long id, User user, long totalPrice) {
        Order order = Order.create(user, totalPrice);
        setId(order, id);
        return order;
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
