package com.gongu.server.domain.payment.domain;

import com.gongu.server.domain.order.entity.Order;
import com.gongu.server.global.exception.BusinessException;
import com.gongu.server.global.exception.errorcode.PaymentErrorCode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PaymentTest {

    private Order order;

    private static final Long AMOUNT = 10_000L;
    private static final String PAYMENT_ID = "pay-uuid-001";
    private static final String IDEMPOTENCY_KEY = "idp-key-001";

    @BeforeEach
    void setUp() {
        order = Mockito.mock(Order.class);
    }

    // ── 헬퍼 ──────────────────────────────────────────────────────────

    private Payment pendingPayment() {
        return Payment.initiate(order, IDEMPOTENCY_KEY, PAYMENT_ID, AMOUNT);
    }

    private Payment paidPayment() {
        Payment p = pendingPayment();
        p.confirm(AMOUNT, LocalDateTime.now());
        return p;
    }

    private Payment refundedPayment() {
        Payment p = pendingPayment();
        p.refund();
        return p;
    }

    private Payment failedPayment() {
        Payment p = pendingPayment();
        p.fail();
        return p;
    }

    // ── initiate() ────────────────────────────────────────────────────

    @Test
    @DisplayName("initiate() — PENDING 상태, 필드 값 검증")
    void initiate_성공() {
        Payment payment = pendingPayment();

        assertThat(payment.getStatus()).isEqualTo(PaymentStatus.PENDING);
        assertThat(payment.getAmount()).isEqualTo(AMOUNT);
        assertThat(payment.getMerchantUid()).isEqualTo(PAYMENT_ID);
        assertThat(payment.getImpUid()).isEqualTo(PAYMENT_ID);
        assertThat(payment.getIdempotencyKey()).isEqualTo(IDEMPOTENCY_KEY);
        assertThat(payment.getOrder()).isSameAs(order);
        assertThat(payment.getPaidAt()).isNull();
        assertThat(payment.getCancelledAt()).isNull();
    }

    // ── confirm() ─────────────────────────────────────────────────────

    @Test
    @DisplayName("confirm() — PENDING + 올바른 금액 → PAID, paidAt 설정")
    void confirm_성공() {
        Payment payment = pendingPayment();
        LocalDateTime paidAt = LocalDateTime.now();

        payment.confirm(AMOUNT, paidAt);

        assertThat(payment.getStatus()).isEqualTo(PaymentStatus.PAID);
        assertThat(payment.getPaidAt()).isEqualTo(paidAt);
    }

    @Test
    @DisplayName("confirm() — PAID 상태에서 호출 → PAYMENT_ALREADY_PROCESSED, 상태 불변")
    void confirm_PAID상태_예외() {
        Payment payment = paidPayment();

        assertThatThrownBy(() -> payment.confirm(AMOUNT, LocalDateTime.now()))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode())
                        .isEqualTo(PaymentErrorCode.PAYMENT_ALREADY_PROCESSED));
        assertThat(payment.getStatus()).isEqualTo(PaymentStatus.PAID);
    }

    @Test
    @DisplayName("confirm() — FAILED 상태에서 호출 → PAYMENT_ALREADY_PROCESSED, 상태 불변")
    void confirm_FAILED상태_예외() {
        Payment payment = failedPayment();

        assertThatThrownBy(() -> payment.confirm(AMOUNT, LocalDateTime.now()))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode())
                        .isEqualTo(PaymentErrorCode.PAYMENT_ALREADY_PROCESSED));
        assertThat(payment.getStatus()).isEqualTo(PaymentStatus.FAILED);
    }

    @Test
    @DisplayName("confirm() — REFUNDED 상태에서 호출 → PAYMENT_ALREADY_PROCESSED, 상태 불변")
    void confirm_REFUNDED상태_예외() {
        Payment payment = refundedPayment();

        assertThatThrownBy(() -> payment.confirm(AMOUNT, LocalDateTime.now()))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode())
                        .isEqualTo(PaymentErrorCode.PAYMENT_ALREADY_PROCESSED));
        assertThat(payment.getStatus()).isEqualTo(PaymentStatus.REFUNDED);
    }

    @Test
    @DisplayName("confirm() — PENDING + 금액 불일치 → PAYMENT_AMOUNT_MISMATCH, 상태 불변")
    void confirm_금액불일치_예외() {
        Payment payment = pendingPayment();

        assertThatThrownBy(() -> payment.confirm(AMOUNT + 1, LocalDateTime.now()))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode())
                        .isEqualTo(PaymentErrorCode.PAYMENT_AMOUNT_MISMATCH));
        assertThat(payment.getStatus()).isEqualTo(PaymentStatus.PENDING);
        assertThat(payment.getPaidAt()).isNull();
    }

    // ── refund() (PENDING) ────────────────────────────────────────────

    @Test
    @DisplayName("refund() — PENDING → REFUNDED, cancelledAt 설정")
    void refund_PENDING_성공() {
        Payment payment = pendingPayment();

        payment.refund();

        assertThat(payment.getStatus()).isEqualTo(PaymentStatus.REFUNDED);
        assertThat(payment.getCancelledAt()).isNotNull();
    }

    @Test
    @DisplayName("refund() — FAILED 상태에서 호출 → PAYMENT_INVALID_STATE_TRANSITION")
    void refund_FAILED상태_예외() {
        Payment payment = failedPayment();

        assertThatThrownBy(payment::refund)
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode())
                        .isEqualTo(PaymentErrorCode.PAYMENT_INVALID_STATE_TRANSITION));
    }

    @Test
    @DisplayName("refund() — REFUNDED 상태에서 호출 → PAYMENT_INVALID_STATE_TRANSITION")
    void refund_REFUNDED상태_예외() {
        Payment payment = refundedPayment();

        assertThatThrownBy(payment::refund)
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode())
                        .isEqualTo(PaymentErrorCode.PAYMENT_INVALID_STATE_TRANSITION));
    }

    // ── refund() (PAID) ───────────────────────────────────────────────

    @Test
    @DisplayName("refund() — PAID → REFUNDED, cancelledAt 설정")
    void refund_PAID_성공() {
        Payment payment = paidPayment();

        payment.refund();

        assertThat(payment.getStatus()).isEqualTo(PaymentStatus.REFUNDED);
        assertThat(payment.getCancelledAt()).isNotNull();
    }

    @Test
    @DisplayName("refund() — CANCELLED 상태에서 호출 → PAYMENT_INVALID_STATE_TRANSITION")
    void refund_CANCELLED상태_예외() {
        Payment payment = pendingPayment();
        payment.expire();

        assertThatThrownBy(payment::refund)
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode())
                        .isEqualTo(PaymentErrorCode.PAYMENT_INVALID_STATE_TRANSITION));
    }

    // ── fail() ────────────────────────────────────────────────────────

    @Test
    @DisplayName("fail() — PENDING → FAILED")
    void fail_성공() {
        Payment payment = pendingPayment();

        payment.fail();

        assertThat(payment.getStatus()).isEqualTo(PaymentStatus.FAILED);
    }

    @Test
    @DisplayName("fail() — PAID 상태에서 호출 → PAYMENT_INVALID_STATE_TRANSITION")
    void fail_PAID상태_예외() {
        Payment payment = paidPayment();

        assertThatThrownBy(payment::fail)
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode())
                        .isEqualTo(PaymentErrorCode.PAYMENT_INVALID_STATE_TRANSITION));
    }

    @Test
    @DisplayName("fail() — CANCELLED 상태에서 호출 → PAYMENT_INVALID_STATE_TRANSITION")
    void fail_CANCELLED상태_예외() {
        Payment payment = refundedPayment();

        assertThatThrownBy(payment::fail)
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode())
                        .isEqualTo(PaymentErrorCode.PAYMENT_INVALID_STATE_TRANSITION));
    }

    @Test
    @DisplayName("fail() — FAILED 상태에서 호출 → PAYMENT_INVALID_STATE_TRANSITION")
    void fail_FAILED상태_예외() {
        Payment payment = failedPayment();

        assertThatThrownBy(payment::fail)
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode())
                        .isEqualTo(PaymentErrorCode.PAYMENT_INVALID_STATE_TRANSITION));
    }
}
