package com.gongu.server.domain.payment.service;

import com.gongu.server.domain.order.entity.Order;
import com.gongu.server.domain.order.entity.OrderStatus;
import com.gongu.server.domain.order.repository.OrderRepository;
import com.gongu.server.domain.payment.domain.Payment;
import com.gongu.server.domain.payment.domain.PaymentStatus;
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
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.time.OffsetDateTime;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
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
    private static final String IDEMPOTENCY_KEY = "idem-key-001";
    private static final String PAYMENT_ID = "pay-001";
    private static final Long AMOUNT = 10_000L;

    @BeforeEach
    void setUp() {
        user = org.mockito.Mockito.mock(User.class);
        given(user.getId()).willReturn(USER_ID);

        order = org.mockito.Mockito.mock(Order.class);
        given(order.getId()).willReturn(ORDER_ID);
        given(order.getUser()).willReturn(user);
        given(order.getStatus()).willReturn(OrderStatus.RESERVED);
        given(order.getTotalPrice()).willReturn(AMOUNT);
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
        paymentService.verifyPayment(USER_ID, IDEMPOTENCY_KEY, ORDER_ID, PAYMENT_ID, AMOUNT);

        // then
        ArgumentCaptor<Payment> paymentCaptor = ArgumentCaptor.forClass(Payment.class);
        verify(paymentRepository).save(paymentCaptor.capture());
        Payment savedPayment = paymentCaptor.getValue();
        assertThat(savedPayment.getStatus()).isEqualTo(PaymentStatus.PAID);

        verify(order).pay();
    }

    @Test
    @DisplayName("verifyPayment_멱등키_중복_예외")
    void verifyPayment_멱등키_중복_예외() {
        // given
        Payment existingPayment = org.mockito.Mockito.mock(Payment.class);
        given(paymentRepository.findByIdempotencyKey(IDEMPOTENCY_KEY)).willReturn(Optional.of(existingPayment));

        // when & then
        assertThatThrownBy(() ->
                paymentService.verifyPayment(USER_ID, IDEMPOTENCY_KEY, ORDER_ID, PAYMENT_ID, AMOUNT))
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
                paymentService.verifyPayment(USER_ID, IDEMPOTENCY_KEY, ORDER_ID, PAYMENT_ID, AMOUNT))
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
                paymentService.verifyPayment(USER_ID, IDEMPOTENCY_KEY, ORDER_ID, PAYMENT_ID, AMOUNT))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode())
                        .isEqualTo(OrderErrorCode.ORDER_NOT_FOUND));
    }

    @Test
    @DisplayName("verifyPayment_소유권_불일치_예외")
    void verifyPayment_소유권_불일치_예외() {
        // given
        User anotherUser = org.mockito.Mockito.mock(User.class);
        given(anotherUser.getId()).willReturn(999L);
        given(order.getUser()).willReturn(anotherUser);

        given(paymentRepository.findByIdempotencyKey(IDEMPOTENCY_KEY)).willReturn(Optional.empty());
        given(userRepository.findByIdAndDeletedAtIsNull(USER_ID)).willReturn(Optional.of(user));
        given(orderRepository.findByIdWithLock(ORDER_ID)).willReturn(Optional.of(order));

        // when & then
        assertThatThrownBy(() ->
                paymentService.verifyPayment(USER_ID, IDEMPOTENCY_KEY, ORDER_ID, PAYMENT_ID, AMOUNT))
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
                paymentService.verifyPayment(USER_ID, IDEMPOTENCY_KEY, ORDER_ID, PAYMENT_ID, AMOUNT))
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
                paymentService.verifyPayment(USER_ID, IDEMPOTENCY_KEY, ORDER_ID, PAYMENT_ID, AMOUNT))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode())
                        .isEqualTo(PaymentErrorCode.PAYMENT_AMOUNT_MISMATCH));

        verify(portOneClient).cancelPayment("pay-001", "결제 금액 불일치");
        verify(order).cancel("결제 금액 불일치");

        ArgumentCaptor<Payment> paymentCaptor = ArgumentCaptor.forClass(Payment.class);
        verify(paymentRepository).save(paymentCaptor.capture());
        Payment savedPayment = paymentCaptor.getValue();
        assertThat(savedPayment.getStatus()).isEqualTo(PaymentStatus.CANCELLED);
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
                paymentService.verifyPayment(USER_ID, IDEMPOTENCY_KEY, ORDER_ID, PAYMENT_ID, AMOUNT))
                .isInstanceOf(InfraException.class);

        ArgumentCaptor<Payment> paymentCaptor = ArgumentCaptor.forClass(Payment.class);
        verify(paymentRepository).save(paymentCaptor.capture());
        Payment savedPayment = paymentCaptor.getValue();
        assertThat(savedPayment.getStatus()).isEqualTo(PaymentStatus.FAILED);
    }
}
