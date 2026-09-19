package com.gongu.server.domain.payment.service;

import com.gongu.server.domain.order.entity.Order;
import com.gongu.server.domain.order.entity.OrderStatus;
import com.gongu.server.domain.order.repository.OrderRepository;
import com.gongu.server.domain.payment.domain.Payment;
import com.gongu.server.domain.payment.domain.PaymentHistoryTrigger;
import com.gongu.server.domain.payment.domain.PaymentStatus;
import com.gongu.server.domain.payment.dto.PaymentPrepareResult;
import com.gongu.server.domain.payment.dto.response.VerifyPaymentResponse;
import com.gongu.server.domain.payment.repository.PaymentRepository;
import com.gongu.server.domain.user.entity.User;
import com.gongu.server.domain.user.repository.UserRepository;
import com.gongu.server.global.exception.BusinessException;
import com.gongu.server.global.exception.InfraException;
import com.gongu.server.global.exception.errorcode.PaymentErrorCode;
import com.gongu.server.global.exception.errorcode.UserErrorCode;
import com.gongu.server.global.infrastructure.portone.PortOneClient;
import com.gongu.server.global.infrastructure.portone.dto.PortOnePaymentResponse;
import com.gongu.server.global.infrastructure.portone.dto.PortOnePaymentResult;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class PaymentServiceTest {

    @Mock
    private UserRepository userRepository;

    @Mock
    private OrderRepository orderRepository;

    @Mock
    private PaymentRepository paymentRepository;

    @Mock
    private PortOneClient portOneClient;

    @Mock
    private PaymentReconciler reconciler;

    private Counter paymentFailedPgErrorCounter;

    private PaymentService paymentService;

    private User user;
    private Order order;

    private static final Long USER_ID = 1L;
    private static final Long ORDER_ID = 1L;
    private static final Long AMOUNT = 10_000L;
    private static final String PAYMENT_ID = "pay-uuid-001";

    @BeforeEach
    void setUp() {
        SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
        paymentFailedPgErrorCounter = Counter.builder("gongu.payment.failed")
                .tag("reason", "pg_error")
                .register(meterRegistry);
        paymentService = new PaymentService(
                userRepository, orderRepository, paymentRepository,
                portOneClient, paymentFailedPgErrorCounter, reconciler
        );

        user = Mockito.mock(User.class);
        lenient().when(user.getId()).thenReturn(USER_ID);

        order = Mockito.mock(Order.class);
        lenient().when(order.getId()).thenReturn(ORDER_ID);
        lenient().when(order.getStatus()).thenReturn(OrderStatus.RESERVED);
        lenient().when(order.getTotalPrice()).thenReturn(AMOUNT);
        lenient().when(order.isOwnedBy(USER_ID)).thenReturn(true);
    }

    // ────────────────────────────────────────────────────────────
    // preparePayment
    // ────────────────────────────────────────────────────────────

    @Test
    @DisplayName("preparePayment_성공")
    void preparePayment_성공() {
        // given
        given(userRepository.findByIdAndDeletedAtIsNull(USER_ID)).willReturn(Optional.of(user));
        given(paymentRepository.existsByOrderIdAndStatusIn(eq(ORDER_ID), any(List.class))).willReturn(false);
        given(orderRepository.findByIdWithLock(ORDER_ID)).willReturn(Optional.of(order));
        Payment savedPayment = Mockito.mock(Payment.class);
        given(paymentRepository.save(any(Payment.class))).willReturn(savedPayment);

        // when
        PaymentPrepareResult result = paymentService.preparePayment(USER_ID, ORDER_ID);

        // then
        assertThat(result).isNotNull();
        assertThat(result.paymentId()).isNotNull().isNotEmpty();
        assertThat(result.amount()).isEqualTo(AMOUNT);
        verify(paymentRepository).save(any(Payment.class));
    }

    @Test
    @DisplayName("preparePayment_사용자_없음")
    void preparePayment_사용자_없음() {
        // given
        given(userRepository.findByIdAndDeletedAtIsNull(USER_ID)).willReturn(Optional.empty());

        // when & then
        assertThatThrownBy(() -> paymentService.preparePayment(USER_ID, ORDER_ID))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode())
                        .isEqualTo(UserErrorCode.USER_NOT_FOUND));

        verify(orderRepository, never()).findByIdWithLock(any());
    }

    @Test
    @DisplayName("preparePayment_활성결제_존재_예외")
    void preparePayment_활성결제_존재_예외() {
        // given
        given(userRepository.findByIdAndDeletedAtIsNull(USER_ID)).willReturn(Optional.of(user));
        given(orderRepository.findByIdWithLock(ORDER_ID)).willReturn(Optional.of(order));
        given(order.isOwnedBy(USER_ID)).willReturn(true);
        given(order.getStatus()).willReturn(OrderStatus.RESERVED);
        given(paymentRepository.existsByOrderIdAndStatusIn(eq(ORDER_ID), any(List.class))).willReturn(true);

        // when & then
        assertThatThrownBy(() -> paymentService.preparePayment(USER_ID, ORDER_ID))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode())
                        .isEqualTo(PaymentErrorCode.PAYMENT_ACTIVE_EXISTS));

        verify(paymentRepository, never()).save(any());
    }

    @Test
    @DisplayName("preparePayment_소유권_불일치")
    void preparePayment_소유권_불일치() {
        // given
        given(userRepository.findByIdAndDeletedAtIsNull(USER_ID)).willReturn(Optional.of(user));
        given(orderRepository.findByIdWithLock(ORDER_ID)).willReturn(Optional.of(order));
        given(order.isOwnedBy(USER_ID)).willReturn(false);

        // when & then
        assertThatThrownBy(() -> paymentService.preparePayment(USER_ID, ORDER_ID))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode())
                        .isEqualTo(PaymentErrorCode.PAYMENT_NOT_ALLOWED));

        verify(paymentRepository, never()).existsByOrderIdAndStatusIn(eq(ORDER_ID), any(List.class));
    }

    @Test
    @DisplayName("preparePayment_주문상태_비RESERVED")
    void preparePayment_주문상태_비RESERVED() {
        // given
        given(userRepository.findByIdAndDeletedAtIsNull(USER_ID)).willReturn(Optional.of(user));
        given(orderRepository.findByIdWithLock(ORDER_ID)).willReturn(Optional.of(order));
        given(order.getStatus()).willReturn(OrderStatus.PAID);

        // when & then
        assertThatThrownBy(() -> paymentService.preparePayment(USER_ID, ORDER_ID))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode())
                        .isEqualTo(PaymentErrorCode.PAYMENT_NOT_ALLOWED));

        verify(paymentRepository, never()).existsByOrderIdAndStatusIn(eq(ORDER_ID), any(List.class));
    }

    // ────────────────────────────────────────────────────────────
    // validateOwnership
    // ────────────────────────────────────────────────────────────

    @Test
    @DisplayName("validateOwnership_성공")
    void validateOwnership_성공() {
        // given
        Payment payment = Mockito.mock(Payment.class);
        given(paymentRepository.findByMerchantUid(PAYMENT_ID)).willReturn(Optional.of(payment));
        given(payment.getOrder()).willReturn(order);
        given(order.isOwnedBy(USER_ID)).willReturn(true);

        // when & then — no exception
        paymentService.validateOwnership(USER_ID, PAYMENT_ID);
    }

    @Test
    @DisplayName("validateOwnership_결제_없음")
    void validateOwnership_결제_없음() {
        // given
        given(paymentRepository.findByMerchantUid(PAYMENT_ID)).willReturn(Optional.empty());

        // when & then
        assertThatThrownBy(() -> paymentService.validateOwnership(USER_ID, PAYMENT_ID))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode())
                        .isEqualTo(PaymentErrorCode.PAYMENT_NOT_FOUND));
    }

    @Test
    @DisplayName("validateOwnership_소유권_불일치")
    void validateOwnership_소유권_불일치() {
        // given
        Payment payment = Mockito.mock(Payment.class);
        given(paymentRepository.findByMerchantUid(PAYMENT_ID)).willReturn(Optional.of(payment));
        given(payment.getOrder()).willReturn(order);
        given(order.isOwnedBy(USER_ID)).willReturn(false);

        // when & then
        assertThatThrownBy(() -> paymentService.validateOwnership(USER_ID, PAYMENT_ID))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode())
                        .isEqualTo(PaymentErrorCode.PAYMENT_NOT_ALLOWED));
    }

    // ────────────────────────────────────────────────────────────
    // completePayment — 코디네이터: 선조회 판단 + reconciler 위임
    // ────────────────────────────────────────────────────────────

    @Test
    @DisplayName("completePayment_PENDING_주문_RESERVED_PG_선조회_후_reconciler에_결과_전달")
    void completePayment_PENDING_선조회_후_위임() {
        // given
        Payment payment = Mockito.mock(Payment.class);
        given(paymentRepository.findByMerchantUid(PAYMENT_ID)).willReturn(Optional.of(payment));
        given(payment.getStatus()).willReturn(PaymentStatus.PENDING);
        given(payment.getOrder()).willReturn(order);
        given(orderRepository.findById(ORDER_ID)).willReturn(Optional.of(order));

        PortOnePaymentResponse portOneResponse = new PortOnePaymentResponse(
                PAYMENT_ID, "PAID", new PortOnePaymentResponse.Amount(AMOUNT), OffsetDateTime.now());
        String rawBody = "{\"id\":\"" + PAYMENT_ID + "\",\"status\":\"PAID\"}";
        PortOnePaymentResult prefetched = new PortOnePaymentResult(portOneResponse, rawBody);
        given(portOneClient.getPayment(PAYMENT_ID)).willReturn(prefetched);

        VerifyPaymentResponse expected = new VerifyPaymentResponse(
                ORDER_ID, PAYMENT_ID, AMOUNT, PaymentStatus.PAID, null, OrderStatus.PAID);
        given(reconciler.completePayment(PAYMENT_ID, PaymentHistoryTrigger.CLIENT_VERIFY, prefetched))
                .willReturn(expected);

        // when
        VerifyPaymentResponse result = paymentService.completePayment(PAYMENT_ID, PaymentHistoryTrigger.CLIENT_VERIFY);

        // then
        assertThat(result).isEqualTo(expected);
        ArgumentCaptor<PortOnePaymentResult> captor = ArgumentCaptor.forClass(PortOnePaymentResult.class);
        verify(reconciler).completePayment(eq(PAYMENT_ID), eq(PaymentHistoryTrigger.CLIENT_VERIFY), captor.capture());
        assertThat(captor.getValue()).isSameAs(prefetched);
    }

    @Test
    @DisplayName("completePayment_이미_PAID면_락_없이_조기_반환하고_PG도_reconciler도_호출하지_않는다")
    void completePayment_이미_PAID_조기반환() {
        // given
        Payment payment = Mockito.mock(Payment.class);
        given(paymentRepository.findByMerchantUid(PAYMENT_ID)).willReturn(Optional.of(payment));
        given(payment.getStatus()).willReturn(PaymentStatus.PAID);
        given(payment.getOrder()).willReturn(order);
        given(payment.getMerchantUid()).willReturn(PAYMENT_ID);
        given(payment.getAmount()).willReturn(AMOUNT);
        given(orderRepository.findById(ORDER_ID)).willReturn(Optional.of(order));

        // when
        VerifyPaymentResponse result = paymentService.completePayment(PAYMENT_ID, PaymentHistoryTrigger.CLIENT_VERIFY);

        // then
        assertThat(result).isNotNull();
        assertThat(result.paymentId()).isEqualTo(PAYMENT_ID);
        verify(portOneClient, never()).getPayment(anyString());
        verify(reconciler, never()).completePayment(anyString(), any(), any());
    }

    @Test
    @DisplayName("completePayment_주문이_CANCELLED면_선조회를_건너뛰고_reconciler에_null로_위임")
    void completePayment_주문_CANCELLED_선조회_생략() {
        // given
        Payment payment = Mockito.mock(Payment.class);
        given(paymentRepository.findByMerchantUid(PAYMENT_ID)).willReturn(Optional.of(payment));
        given(payment.getStatus()).willReturn(PaymentStatus.PENDING);
        given(payment.getOrder()).willReturn(order);
        given(order.getStatus()).willReturn(OrderStatus.CANCELLED);
        given(orderRepository.findById(ORDER_ID)).willReturn(Optional.of(order));

        VerifyPaymentResponse expected = new VerifyPaymentResponse(
                ORDER_ID, PAYMENT_ID, AMOUNT, PaymentStatus.REFUNDED, null, OrderStatus.CANCELLED);
        given(reconciler.completePayment(eq(PAYMENT_ID), eq(PaymentHistoryTrigger.CLIENT_VERIFY), isNull()))
                .willReturn(expected);

        // when
        VerifyPaymentResponse result = paymentService.completePayment(PAYMENT_ID, PaymentHistoryTrigger.CLIENT_VERIFY);

        // then
        assertThat(result).isEqualTo(expected);
        verify(portOneClient, never()).getPayment(anyString());
        verify(reconciler).completePayment(eq(PAYMENT_ID), eq(PaymentHistoryTrigger.CLIENT_VERIFY), isNull());
    }

    @Test
    @DisplayName("completePayment_Payment_없으면_선조회_없이_reconciler에_null로_위임")
    void completePayment_Payment_없음_선조회_생략() {
        // given
        given(paymentRepository.findByMerchantUid(PAYMENT_ID)).willReturn(Optional.empty());
        given(reconciler.completePayment(eq(PAYMENT_ID), eq(PaymentHistoryTrigger.CLIENT_VERIFY), isNull()))
                .willThrow(new BusinessException(PaymentErrorCode.PAYMENT_NOT_FOUND));

        // when & then
        assertThatThrownBy(() -> paymentService.completePayment(PAYMENT_ID, PaymentHistoryTrigger.CLIENT_VERIFY))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode())
                        .isEqualTo(PaymentErrorCode.PAYMENT_NOT_FOUND));

        verify(portOneClient, never()).getPayment(anyString());
    }

    @Test
    @DisplayName("completePayment_선조회_PG_InfraException이면_reconciler_호출_없이_예외_전파하고_카운터를_올린다")
    void completePayment_선조회_실패_reconciler_미호출() {
        // given
        Payment payment = Mockito.mock(Payment.class);
        given(paymentRepository.findByMerchantUid(PAYMENT_ID)).willReturn(Optional.of(payment));
        given(payment.getStatus()).willReturn(PaymentStatus.PENDING);
        given(payment.getOrder()).willReturn(order);
        given(orderRepository.findById(ORDER_ID)).willReturn(Optional.of(order));
        given(portOneClient.getPayment(PAYMENT_ID))
                .willThrow(new InfraException(PaymentErrorCode.PAYMENT_PG_UNAVAILABLE));

        // when & then
        assertThatThrownBy(() -> paymentService.completePayment(PAYMENT_ID, PaymentHistoryTrigger.CLIENT_VERIFY))
                .isInstanceOf(InfraException.class);

        assertThat(paymentFailedPgErrorCounter.count()).isEqualTo(1.0);
        verify(reconciler, never()).completePayment(anyString(), any(), any());
    }

    @Test
    @DisplayName("completePayment_결제상태가_PAID_PENDING이_아니면_선조회_없이_reconciler에_null로_위임")
    void completePayment_상태_FAILED_선조회_생략() {
        // given
        Payment payment = Mockito.mock(Payment.class);
        given(paymentRepository.findByMerchantUid(PAYMENT_ID)).willReturn(Optional.of(payment));
        given(payment.getStatus()).willReturn(PaymentStatus.FAILED);

        VerifyPaymentResponse expected = new VerifyPaymentResponse(
                ORDER_ID, PAYMENT_ID, AMOUNT, PaymentStatus.FAILED, null, OrderStatus.CANCELLED);
        given(reconciler.completePayment(eq(PAYMENT_ID), eq(PaymentHistoryTrigger.CLIENT_VERIFY), isNull()))
                .willReturn(expected);

        // when
        VerifyPaymentResponse result = paymentService.completePayment(PAYMENT_ID, PaymentHistoryTrigger.CLIENT_VERIFY);

        // then
        assertThat(result).isEqualTo(expected);
        verify(portOneClient, never()).getPayment(anyString());
        verify(reconciler).completePayment(eq(PAYMENT_ID), eq(PaymentHistoryTrigger.CLIENT_VERIFY), isNull());
    }

    @Test
    @DisplayName("completePayment_PENDING이지만_주문을_찾지_못하면_선조회_없이_reconciler에_null로_위임")
    void completePayment_PENDING_주문없음_선조회_생략() {
        // given
        Payment payment = Mockito.mock(Payment.class);
        given(paymentRepository.findByMerchantUid(PAYMENT_ID)).willReturn(Optional.of(payment));
        given(payment.getStatus()).willReturn(PaymentStatus.PENDING);
        given(payment.getOrder()).willReturn(order);
        given(orderRepository.findById(ORDER_ID)).willReturn(Optional.empty());

        VerifyPaymentResponse expected = new VerifyPaymentResponse(
                ORDER_ID, PAYMENT_ID, AMOUNT, PaymentStatus.PENDING, null, OrderStatus.RESERVED);
        given(reconciler.completePayment(eq(PAYMENT_ID), eq(PaymentHistoryTrigger.CLIENT_VERIFY), isNull()))
                .willReturn(expected);

        // when
        VerifyPaymentResponse result = paymentService.completePayment(PAYMENT_ID, PaymentHistoryTrigger.CLIENT_VERIFY);

        // then
        assertThat(result).isEqualTo(expected);
        verify(portOneClient, never()).getPayment(anyString());
        verify(reconciler).completePayment(eq(PAYMENT_ID), eq(PaymentHistoryTrigger.CLIENT_VERIFY), isNull());
    }
}
