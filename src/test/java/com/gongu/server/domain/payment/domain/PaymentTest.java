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

    private Payment cancelledPayment() {
        Payment p = pendingPayment();
        p.cancelByMismatch();
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
    @DisplayName("confirm() — PAID 상태에서 호출 → PAYMENT_ALREADY_PROCESSED")
    void confirm_PAID상태_예외() {
        Payment payment = paidPayment();

        assertThatThrownBy(() -> payment.confirm(AMOUNT, LocalDateTime.now()))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode())
                        .isEqualTo(PaymentErrorCode.PAYMENT_ALREADY_PROCESSED));
    }

    @Test
    @DisplayName("confirm() — FAILED 상태에서 호출 → PAYMENT_ALREADY_PROCESSED")
    void confirm_FAILED상태_예외() {
        Payment payment = failedPayment();

        assertThatThrownBy(() -> payment.confirm(AMOUNT, LocalDateTime.now()))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode())
                        .isEqualTo(PaymentErrorCode.PAYMENT_ALREADY_PROCESSED));
    }

    @Test
    @DisplayName("confirm() — CANCELLED 상태에서 호출 → PAYMENT_ALREADY_PROCESSED")
    void confirm_CANCELLED상태_예외() {
        Payment payment = cancelledPayment();

        assertThatThrownBy(() -> payment.confirm(AMOUNT, LocalDateTime.now()))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode())
                        .isEqualTo(PaymentErrorCode.PAYMENT_ALREADY_PROCESSED));
    }

    @Test
    @DisplayName("confirm() — PENDING + 금액 불일치 → PAYMENT_AMOUNT_MISMATCH")
    void confirm_금액불일치_예외() {
        Payment payment = pendingPayment();

        assertThatThrownBy(() -> payment.confirm(AMOUNT + 1, LocalDateTime.now()))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode())
                        .isEqualTo(PaymentErrorCode.PAYMENT_AMOUNT_MISMATCH));
    }

    // ── cancelByMismatch() ────────────────────────────────────────────

    @Test
    @DisplayName("cancelByMismatch() — PENDING → CANCELLED, cancelledAt 설정")
    void cancelByMismatch_성공() {
        Payment payment = pendingPayment();

        payment.cancelByMismatch();

        assertThat(payment.getStatus()).isEqualTo(PaymentStatus.CANCELLED);
        assertThat(payment.getCancelledAt()).isNotNull();
    }

    @Test
    @DisplayName("cancelByMismatch() — PAID 상태에서 호출 → PAYMENT_INVALID_STATE_TRANSITION")
    void cancelByMismatch_PAID상태_예외() {
        Payment payment = paidPayment();

        assertThatThrownBy(payment::cancelByMismatch)
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode())
                        .isEqualTo(PaymentErrorCode.PAYMENT_INVALID_STATE_TRANSITION));
    }

    // ── cancel() ──────────────────────────────────────────────────────

    @Test
    @DisplayName("cancel() — PAID → CANCELLED, cancelledAt 설정")
    void cancel_성공() {
        Payment payment = paidPayment();

        payment.cancel();

        assertThat(payment.getStatus()).isEqualTo(PaymentStatus.CANCELLED);
        assertThat(payment.getCancelledAt()).isNotNull();
    }

    @Test
    @DisplayName("cancel() — PENDING 상태에서 호출 → PAYMENT_INVALID_STATE_TRANSITION")
    void cancel_PENDING상태_예외() {
        Payment payment = pendingPayment();

        assertThatThrownBy(payment::cancel)
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode())
                        .isEqualTo(PaymentErrorCode.PAYMENT_INVALID_STATE_TRANSITION));
    }

    @Test
    @DisplayName("cancel() — FAILED 상태에서 호출 → PAYMENT_INVALID_STATE_TRANSITION")
    void cancel_FAILED상태_예외() {
        Payment payment = failedPayment();

        assertThatThrownBy(payment::cancel)
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
        Payment payment = cancelledPayment();

        assertThatThrownBy(payment::fail)
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode())
                        .isEqualTo(PaymentErrorCode.PAYMENT_INVALID_STATE_TRANSITION));
    }
}
