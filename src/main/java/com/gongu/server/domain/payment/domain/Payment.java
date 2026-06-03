package com.gongu.server.domain.payment.domain;

import com.gongu.server.domain.order.entity.Order;
import com.gongu.server.global.common.BaseEntity;
import com.gongu.server.global.exception.BusinessException;
import com.gongu.server.global.exception.errorcode.PaymentErrorCode;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
@Builder(access = AccessLevel.PRIVATE)
@Entity
@Table(name = "payments")
public class Payment extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "order_id", nullable = false)
    private Order order;

    @Column(name = "idempotency_key", nullable = false, unique = true)
    private String idempotencyKey;

    // PortOne V2에서는 merchant-generated UUID(paymentId)가 단일 식별자로 사용된다.
    // V1의 imp_uid(PG 발급)와 merchant_uid(서버 생성)의 이분법이 성립하지 않으므로,
    // DDL 컬럼명은 레거시 명명이며 두 컬럼 모두 동일한 paymentId를 저장한다.
    @Column(name = "imp_uid", nullable = false, unique = true)
    private String impUid;

    @Column(name = "merchant_uid", nullable = false, unique = true)
    private String merchantUid;

    @Column(name = "amount", nullable = false)
    private Long amount;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private PaymentStatus status;

    @Column(name = "paid_at")
    private LocalDateTime paidAt;

    @Column(name = "cancelled_at")
    private LocalDateTime cancelledAt;

    public static Payment initiate(Order order, String idempotencyKey, String paymentId, Long amount) {
        return Payment.builder()
                .order(order)
                .idempotencyKey(idempotencyKey)
                .impUid(paymentId)
                .merchantUid(paymentId)
                .amount(amount)
                .status(PaymentStatus.PENDING)
                .build();
    }

    public void confirm(Long portOneAmount, LocalDateTime paidAt) {
        if (this.status != PaymentStatus.PENDING) {
            throw new BusinessException(PaymentErrorCode.PAYMENT_ALREADY_PROCESSED);
        }
        if (!this.amount.equals(portOneAmount)) {
            throw new BusinessException(PaymentErrorCode.PAYMENT_AMOUNT_MISMATCH);
        }
        this.status = PaymentStatus.PAID;
        this.paidAt = paidAt;
    }

    public void refund() {
        if (this.status != PaymentStatus.PENDING && this.status != PaymentStatus.PAID) {
            throw new BusinessException(PaymentErrorCode.PAYMENT_INVALID_STATE_TRANSITION);
        }
        this.status = PaymentStatus.REFUNDED;
        this.cancelledAt = LocalDateTime.now();
    }

    public void expire() {
        if (this.status != PaymentStatus.PENDING) {
            throw new BusinessException(PaymentErrorCode.PAYMENT_INVALID_STATE_TRANSITION);
        }
        this.status = PaymentStatus.CANCELLED;
        this.cancelledAt = LocalDateTime.now();
    }

    public void fail() {
        if (this.status != PaymentStatus.PENDING) {
            throw new BusinessException(PaymentErrorCode.PAYMENT_INVALID_STATE_TRANSITION);
        }
        this.status = PaymentStatus.FAILED;
    }
}
