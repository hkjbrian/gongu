package com.gongu.server.domain.payment.service;

import com.gongu.server.domain.order.entity.Order;
import com.gongu.server.domain.order.entity.OrderStatus;
import com.gongu.server.domain.order.repository.OrderRepository;
import com.gongu.server.domain.payment.domain.Payment;
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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
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

    @InjectMocks
    private PaymentService paymentService;

    private User user;
    private Order order;

    private static final Long USER_ID = 1L;
    private static final Long ORDER_ID = 1L;
    private static final Long AMOUNT = 10_000L;
    private static final String PAYMENT_ID = "pay-uuid-001";

    @BeforeEach
    void setUp() {
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
    // completePayment
    // ────────────────────────────────────────────────────────────

    @Test
    @DisplayName("completePayment_성공_금액일치")
    void completePayment_성공_금액일치() {
        // given
        Payment payment = Mockito.mock(Payment.class);
        given(paymentRepository.findByMerchantUidWithLock(PAYMENT_ID)).willReturn(Optional.of(payment));
        given(payment.getStatus()).willReturn(PaymentStatus.PENDING);
        given(payment.getOrder()).willReturn(order);
        given(payment.getMerchantUid()).willReturn(PAYMENT_ID);
        given(payment.getAmount()).willReturn(AMOUNT);
        given(payment.getPaidAt()).willReturn(LocalDateTime.now());
        given(order.getStatus()).willReturn(OrderStatus.RESERVED);
        given(orderRepository.findByIdWithLock(ORDER_ID)).willReturn(Optional.of(order));

        PortOnePaymentResponse portOneResponse = new PortOnePaymentResponse(
                PAYMENT_ID,
                "PAID",
                new PortOnePaymentResponse.Amount(AMOUNT),
                OffsetDateTime.now()
        );
        given(portOneClient.getPayment(PAYMENT_ID)).willReturn(portOneResponse);

        // when
        VerifyPaymentResponse result = paymentService.completePayment(PAYMENT_ID);

        // then
        assertThat(result).isNotNull();
        assertThat(result.paymentId()).isEqualTo(PAYMENT_ID);
        assertThat(result.amount()).isEqualTo(AMOUNT);
        InOrder inOrder = Mockito.inOrder(order, payment);
        inOrder.verify(order).pay();
        inOrder.verify(payment).confirm(eq(AMOUNT), any(LocalDateTime.class));
    }

    @Test
    @DisplayName("completePayment_멱등_이미PAID")
    void completePayment_멱등_이미PAID() {
        // given
        Payment payment = Mockito.mock(Payment.class);
        given(paymentRepository.findByMerchantUidWithLock(PAYMENT_ID)).willReturn(Optional.of(payment));
        given(payment.getStatus()).willReturn(PaymentStatus.PAID);
        given(payment.getOrder()).willReturn(order);
        given(payment.getMerchantUid()).willReturn(PAYMENT_ID);
        given(payment.getAmount()).willReturn(AMOUNT);
        given(payment.getPaidAt()).willReturn(LocalDateTime.now());
        given(order.getStatus()).willReturn(OrderStatus.PAID);

        // when
        VerifyPaymentResponse result = paymentService.completePayment(PAYMENT_ID);

        // then
        assertThat(result).isNotNull();
        assertThat(result.paymentId()).isEqualTo(PAYMENT_ID);
        verify(portOneClient, never()).getPayment(any());
    }

    @Test
    @DisplayName("completePayment_Payment_없음")
    void completePayment_Payment_없음() {
        // given
        given(paymentRepository.findByMerchantUidWithLock(PAYMENT_ID)).willReturn(Optional.empty());

        // when & then
        assertThatThrownBy(() -> paymentService.completePayment(PAYMENT_ID))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode())
                        .isEqualTo(PaymentErrorCode.PAYMENT_NOT_FOUND));
    }

    @Test
    @DisplayName("completePayment_상태_PENDING_아님")
    void completePayment_상태_PENDING_아님() {
        // given
        Payment payment = Mockito.mock(Payment.class);
        given(paymentRepository.findByMerchantUidWithLock(PAYMENT_ID)).willReturn(Optional.of(payment));
        given(payment.getStatus()).willReturn(PaymentStatus.FAILED);
        given(payment.getOrder()).willReturn(order);
        given(order.getStatus()).willReturn(OrderStatus.RESERVED);
        given(orderRepository.findByIdWithLock(ORDER_ID)).willReturn(Optional.of(order));

        // when & then
        assertThatThrownBy(() -> paymentService.completePayment(PAYMENT_ID))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode())
                        .isEqualTo(PaymentErrorCode.PAYMENT_INVALID_STATE_TRANSITION));

        verify(portOneClient, never()).getPayment(any());
    }

    @Test
    @DisplayName("completePayment_서킷브레이커_오픈_503 — PortOne InfraException 전파 + payment.fail() 호출")
    void completePayment_PortOne_InfraException() {
        // given
        Payment payment = Mockito.mock(Payment.class);
        given(paymentRepository.findByMerchantUidWithLock(PAYMENT_ID)).willReturn(Optional.of(payment));
        given(payment.getStatus()).willReturn(PaymentStatus.PENDING);
        given(payment.getOrder()).willReturn(order);
        given(orderRepository.findByIdWithLock(ORDER_ID)).willReturn(Optional.of(order));
        given(portOneClient.getPayment(PAYMENT_ID))
                .willThrow(new InfraException(PaymentErrorCode.PAYMENT_PG_UNAVAILABLE));

        // when & then
        assertThatThrownBy(() -> paymentService.completePayment(PAYMENT_ID))
                .isInstanceOf(InfraException.class);

        verify(payment).fail();
    }

    @Test
    @DisplayName("completePayment_PortOne_status_미완료")
    void completePayment_PortOne_status_미완료() {
        // given
        Payment payment = Mockito.mock(Payment.class);
        given(paymentRepository.findByMerchantUidWithLock(PAYMENT_ID)).willReturn(Optional.of(payment));
        given(payment.getStatus()).willReturn(PaymentStatus.PENDING);
        given(payment.getOrder()).willReturn(order);
        given(orderRepository.findByIdWithLock(ORDER_ID)).willReturn(Optional.of(order));

        PortOnePaymentResponse portOneResponse = new PortOnePaymentResponse(
                PAYMENT_ID,
                "FAILED",
                new PortOnePaymentResponse.Amount(AMOUNT),
                OffsetDateTime.now()
        );
        given(portOneClient.getPayment(PAYMENT_ID)).willReturn(portOneResponse);

        // when & then
        assertThatThrownBy(() -> paymentService.completePayment(PAYMENT_ID))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode())
                        .isEqualTo(PaymentErrorCode.PAYMENT_NOT_COMPLETED));

        verify(payment).fail();
    }

    @Test
    @DisplayName("completePayment_금액불일치_보상처리")
    void completePayment_금액불일치_보상처리() {
        // given
        Payment payment = Mockito.mock(Payment.class);
        given(paymentRepository.findByMerchantUidWithLock(PAYMENT_ID)).willReturn(Optional.of(payment));
        given(payment.getStatus()).willReturn(PaymentStatus.PENDING);
        given(payment.getOrder()).willReturn(order);
        given(orderRepository.findByIdWithLock(ORDER_ID)).willReturn(Optional.of(order));

        Long mismatchAmount = 5_000L;
        PortOnePaymentResponse portOneResponse = new PortOnePaymentResponse(
                PAYMENT_ID,
                "PAID",
                new PortOnePaymentResponse.Amount(mismatchAmount),
                OffsetDateTime.now()
        );
        given(portOneClient.getPayment(PAYMENT_ID)).willReturn(portOneResponse);

        // when & then
        assertThatThrownBy(() -> paymentService.completePayment(PAYMENT_ID))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode())
                        .isEqualTo(PaymentErrorCode.PAYMENT_AMOUNT_MISMATCH));

        verify(payment).refund();
        verify(order).cancel(anyString());
        verify(portOneClient).cancelPayment(eq(PAYMENT_ID), anyString());
    }

    @Test
    @DisplayName("completePayment_ORDER_EXPIRED_환불_성공")
    void completePayment_ORDER_EXPIRED_환불_성공() {
        // given
        Payment payment = Mockito.mock(Payment.class);
        given(paymentRepository.findByMerchantUidWithLock(PAYMENT_ID)).willReturn(Optional.of(payment));
        given(payment.getStatus()).willReturn(PaymentStatus.PENDING);
        given(payment.getOrder()).willReturn(order);
        given(order.getStatus()).willReturn(OrderStatus.CANCELLED);
        given(orderRepository.findByIdWithLock(ORDER_ID)).willReturn(Optional.of(order));
        given(portOneClient.cancelPayment(eq(PAYMENT_ID), anyString()))
                .willReturn(Mockito.mock(PortOnePaymentResponse.class));

        // when & then
        assertThatThrownBy(() -> paymentService.completePayment(PAYMENT_ID))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode())
                        .isEqualTo(PaymentErrorCode.ORDER_EXPIRED_REFUNDED));

        verify(portOneClient).cancelPayment(eq(PAYMENT_ID), anyString());
        verify(payment).refund();
    }

    @Test
    @DisplayName("completePayment_CANCELLED_Payment_ORDER_EXPIRED_환불_성공")
    void completePayment_CANCELLED_Payment_ORDER_EXPIRED_환불_성공() {
        // given
        Payment payment = Mockito.mock(Payment.class);
        given(paymentRepository.findByMerchantUidWithLock(PAYMENT_ID)).willReturn(Optional.of(payment));
        given(payment.getStatus()).willReturn(PaymentStatus.CANCELLED);
        given(payment.getOrder()).willReturn(order);
        given(order.getStatus()).willReturn(OrderStatus.CANCELLED);
        given(orderRepository.findByIdWithLock(ORDER_ID)).willReturn(Optional.of(order));
        given(portOneClient.cancelPayment(eq(PAYMENT_ID), anyString()))
                .willReturn(Mockito.mock(PortOnePaymentResponse.class));

        // when & then
        assertThatThrownBy(() -> paymentService.completePayment(PAYMENT_ID))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode())
                        .isEqualTo(PaymentErrorCode.ORDER_EXPIRED_REFUNDED));

        verify(portOneClient).cancelPayment(eq(PAYMENT_ID), anyString());
        verify(payment).refund();
    }

    @Test
    @DisplayName("completePayment_ORDER_EXPIRED_서킷오픈_InfraException_전파")
    void completePayment_ORDER_EXPIRED_서킷오픈_InfraException_전파() {
        // given
        Payment payment = Mockito.mock(Payment.class);
        given(paymentRepository.findByMerchantUidWithLock(PAYMENT_ID)).willReturn(Optional.of(payment));
        given(payment.getStatus()).willReturn(PaymentStatus.PENDING);
        given(payment.getOrder()).willReturn(order);
        given(order.getStatus()).willReturn(OrderStatus.CANCELLED);
        given(orderRepository.findByIdWithLock(ORDER_ID)).willReturn(Optional.of(order));
        given(portOneClient.cancelPayment(eq(PAYMENT_ID), anyString()))
                .willThrow(new InfraException(PaymentErrorCode.PAYMENT_PG_UNAVAILABLE));

        // when & then
        assertThatThrownBy(() -> paymentService.completePayment(PAYMENT_ID))
                .isInstanceOf(InfraException.class);

        verify(payment, never()).refund();
    }
}
