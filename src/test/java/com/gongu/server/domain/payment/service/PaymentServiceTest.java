package com.gongu.server.domain.payment.service;

import com.gongu.server.domain.order.entity.Order;
import com.gongu.server.domain.order.entity.OrderStatus;
import com.gongu.server.domain.order.repository.OrderRepository;
import com.gongu.server.domain.payment.domain.Payment;
import com.gongu.server.domain.payment.repository.PaymentRepository;
import com.gongu.server.domain.user.entity.User;
import com.gongu.server.domain.user.repository.UserRepository;
import com.gongu.server.global.exception.BusinessException;
import com.gongu.server.global.exception.InfraException;
import com.gongu.server.global.exception.errorcode.OrderErrorCode;
import com.gongu.server.global.exception.errorcode.PaymentErrorCode;
import com.gongu.server.global.exception.errorcode.UserErrorCode;
import com.gongu.server.global.infrastructure.portone.PortOneClient;
import com.gongu.server.global.infrastructure.portone.dto.PortOnePaymentResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
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

    @Mock
    private PaymentResultCommitter committer;

    @InjectMocks
    private PaymentService paymentService;

    private User user;
    private Order order;

    private static final Long USER_ID = 1L;
    private static final Long ORDER_ID = 1L;
    private static final String IDEMPOTENCY_KEY = "idem-key-001";
    private static final String PAYMENT_ID = "pay-001";
    private static final Long AMOUNT = 10_000L;

    @BeforeEach
    void setUp() {
        user = org.mockito.Mockito.mock(User.class);
        lenient().when(user.getId()).thenReturn(USER_ID);

        order = org.mockito.Mockito.mock(Order.class);
        lenient().when(order.getId()).thenReturn(ORDER_ID);
        lenient().when(order.getUser()).thenReturn(user);
        lenient().when(order.getStatus()).thenReturn(OrderStatus.RESERVED);
        lenient().when(order.getTotalPrice()).thenReturn(AMOUNT);
        lenient().when(order.isOwnedBy(USER_ID)).thenReturn(true);
    }

    @Test
    @DisplayName("verifyPayment_성공_금액일치")
    void verifyPayment_성공_금액일치() {
        // given
        PortOnePaymentResponse portOneResponse = new PortOnePaymentResponse(
                PAYMENT_ID,
                "PAID",
                new PortOnePaymentResponse.Amount(AMOUNT),
                OffsetDateTime.now()
        );

        given(paymentRepository.findByIdempotencyKey(IDEMPOTENCY_KEY)).willReturn(Optional.empty());
        given(userRepository.findByIdAndDeletedAtIsNull(USER_ID)).willReturn(Optional.of(user));
        given(orderRepository.findByIdWithLock(ORDER_ID)).willReturn(Optional.of(order));
        given(paymentRepository.save(any(Payment.class))).willAnswer(inv -> inv.getArgument(0));
        given(portOneClient.getPayment(PAYMENT_ID)).willReturn(portOneResponse);

        // when
        paymentService.verifyPayment(USER_ID, IDEMPOTENCY_KEY, ORDER_ID, PAYMENT_ID);

        // then
        verify(committer).commitConfirm(any(Payment.class), eq(order), eq(AMOUNT), any(LocalDateTime.class));
    }

    @Test
    @DisplayName("verifyPayment_멱등키_중복_예외")
    void verifyPayment_멱등키_중복_예외() {
        // given
        Payment existingPayment = org.mockito.Mockito.mock(Payment.class);
        given(paymentRepository.findByIdempotencyKey(IDEMPOTENCY_KEY)).willReturn(Optional.of(existingPayment));

        // when & then
        assertThatThrownBy(() ->
                paymentService.verifyPayment(USER_ID, IDEMPOTENCY_KEY, ORDER_ID, PAYMENT_ID))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode())
                        .isEqualTo(PaymentErrorCode.PAYMENT_ALREADY_PROCESSED));

        verify(userRepository, never()).findByIdAndDeletedAtIsNull(any());
        verify(orderRepository, never()).findByIdWithLock(any());
    }

    @Test
    @DisplayName("verifyPayment_사용자_없음_예외")
    void verifyPayment_사용자_없음_예외() {
        // given
        given(paymentRepository.findByIdempotencyKey(IDEMPOTENCY_KEY)).willReturn(Optional.empty());
        given(userRepository.findByIdAndDeletedAtIsNull(USER_ID)).willReturn(Optional.empty());

        // when & then
        assertThatThrownBy(() ->
                paymentService.verifyPayment(USER_ID, IDEMPOTENCY_KEY, ORDER_ID, PAYMENT_ID))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode())
                        .isEqualTo(UserErrorCode.USER_NOT_FOUND));

        verify(orderRepository, never()).findByIdWithLock(any());
    }

    @Test
    @DisplayName("verifyPayment_주문_없음_예외")
    void verifyPayment_주문_없음_예외() {
        // given
        given(paymentRepository.findByIdempotencyKey(IDEMPOTENCY_KEY)).willReturn(Optional.empty());
        given(userRepository.findByIdAndDeletedAtIsNull(USER_ID)).willReturn(Optional.of(user));
        given(orderRepository.findByIdWithLock(ORDER_ID)).willReturn(Optional.empty());

        // when & then
        assertThatThrownBy(() ->
                paymentService.verifyPayment(USER_ID, IDEMPOTENCY_KEY, ORDER_ID, PAYMENT_ID))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode())
                        .isEqualTo(OrderErrorCode.ORDER_NOT_FOUND));
    }

    @Test
    @DisplayName("verifyPayment_소유권_불일치_예외")
    void verifyPayment_소유권_불일치_예외() {
        // given
        given(order.isOwnedBy(USER_ID)).willReturn(false);

        given(paymentRepository.findByIdempotencyKey(IDEMPOTENCY_KEY)).willReturn(Optional.empty());
        given(userRepository.findByIdAndDeletedAtIsNull(USER_ID)).willReturn(Optional.of(user));
        given(orderRepository.findByIdWithLock(ORDER_ID)).willReturn(Optional.of(order));

        // when & then
        assertThatThrownBy(() ->
                paymentService.verifyPayment(USER_ID, IDEMPOTENCY_KEY, ORDER_ID, PAYMENT_ID))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode())
                        .isEqualTo(PaymentErrorCode.PAYMENT_NOT_ALLOWED));
    }

    @Test
    @DisplayName("verifyPayment_주문_상태_비정상_예외")
    void verifyPayment_주문_상태_비정상_예외() {
        // given
        given(order.getStatus()).willReturn(OrderStatus.PAID);

        given(paymentRepository.findByIdempotencyKey(IDEMPOTENCY_KEY)).willReturn(Optional.empty());
        given(userRepository.findByIdAndDeletedAtIsNull(USER_ID)).willReturn(Optional.of(user));
        given(orderRepository.findByIdWithLock(ORDER_ID)).willReturn(Optional.of(order));

        // when & then
        assertThatThrownBy(() ->
                paymentService.verifyPayment(USER_ID, IDEMPOTENCY_KEY, ORDER_ID, PAYMENT_ID))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode())
                        .isEqualTo(PaymentErrorCode.PAYMENT_NOT_ALLOWED));
    }

    @Test
    @DisplayName("verifyPayment_금액_불일치_보상처리")
    void verifyPayment_금액_불일치_보상처리() {
        // given
        Long differentAmount = 5_000L;
        PortOnePaymentResponse portOneResponse = new PortOnePaymentResponse(
                PAYMENT_ID,
                "PAID",
                new PortOnePaymentResponse.Amount(differentAmount),
                OffsetDateTime.now()
        );

        given(paymentRepository.findByIdempotencyKey(IDEMPOTENCY_KEY)).willReturn(Optional.empty());
        given(userRepository.findByIdAndDeletedAtIsNull(USER_ID)).willReturn(Optional.of(user));
        given(orderRepository.findByIdWithLock(ORDER_ID)).willReturn(Optional.of(order));
        given(paymentRepository.save(any(Payment.class))).willAnswer(inv -> inv.getArgument(0));
        given(portOneClient.getPayment(PAYMENT_ID)).willReturn(portOneResponse);

        // when & then
        assertThatThrownBy(() ->
                paymentService.verifyPayment(USER_ID, IDEMPOTENCY_KEY, ORDER_ID, PAYMENT_ID))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode())
                        .isEqualTo(PaymentErrorCode.PAYMENT_AMOUNT_MISMATCH));

        verify(committer).commitMismatchCancel(any(Payment.class), eq(order));
        verify(portOneClient).cancelPayment(PAYMENT_ID, "결제 금액 불일치");
    }

    @Test
    @DisplayName("verifyPayment_CB오픈_fail처리")
    void verifyPayment_CB오픈_fail처리() {
        // given
        given(paymentRepository.findByIdempotencyKey(IDEMPOTENCY_KEY)).willReturn(Optional.empty());
        given(userRepository.findByIdAndDeletedAtIsNull(USER_ID)).willReturn(Optional.of(user));
        given(orderRepository.findByIdWithLock(ORDER_ID)).willReturn(Optional.of(order));
        given(paymentRepository.save(any(Payment.class))).willAnswer(inv -> inv.getArgument(0));
        given(portOneClient.getPayment(PAYMENT_ID))
                .willThrow(new InfraException(PaymentErrorCode.PAYMENT_PG_UNAVAILABLE));

        // when & then
        assertThatThrownBy(() ->
                paymentService.verifyPayment(USER_ID, IDEMPOTENCY_KEY, ORDER_ID, PAYMENT_ID))
                .isInstanceOf(InfraException.class);

        verify(committer).commitFail(any(Payment.class));
        verify(order, never()).pay();
        verify(order, never()).cancel(any(String.class));
    }

    @Test
    @DisplayName("verifyPayment_PortOne_status_미완료_예외")
    void verifyPayment_PortOne_status_미완료_예외() {
        // given
        PortOnePaymentResponse portOneResponse = new PortOnePaymentResponse(
                PAYMENT_ID,
                "FAILED",
                new PortOnePaymentResponse.Amount(AMOUNT),
                OffsetDateTime.now()
        );

        given(paymentRepository.findByIdempotencyKey(IDEMPOTENCY_KEY)).willReturn(Optional.empty());
        given(userRepository.findByIdAndDeletedAtIsNull(USER_ID)).willReturn(Optional.of(user));
        given(orderRepository.findByIdWithLock(ORDER_ID)).willReturn(Optional.of(order));
        given(paymentRepository.save(any(Payment.class))).willAnswer(inv -> inv.getArgument(0));
        given(portOneClient.getPayment(PAYMENT_ID)).willReturn(portOneResponse);

        // when & then
        assertThatThrownBy(() ->
                paymentService.verifyPayment(USER_ID, IDEMPOTENCY_KEY, ORDER_ID, PAYMENT_ID))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode())
                        .isEqualTo(PaymentErrorCode.PAYMENT_NOT_COMPLETED));

        verify(committer).commitFail(any(Payment.class));
    }
}
