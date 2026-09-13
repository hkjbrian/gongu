package com.gongu.server.domain.payment.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EntityListeners;
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
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import java.time.LocalDateTime;

@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Entity
@Table(name = "payment_histories")
@EntityListeners(AuditingEntityListener.class)
public class PaymentHistory {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "payment_id", nullable = false)
    private Payment payment;

    @Enumerated(EnumType.STRING)
    @Column(name = "from_status", nullable = false, length = 20)
    private PaymentStatus fromStatus;

    @Enumerated(EnumType.STRING)
    @Column(name = "to_status", nullable = false, length = 20)
    private PaymentStatus toStatus;

    // 컬럼명은 trigger_type — "trigger"는 MySQL 예약어라 그대로 쓰면 매 INSERT/SELECT가 문법 오류가 된다
    // (H2 테스트 DB는 예약어로 취급하지 않아 여기서는 통과하지만 MySQL에서는 실패한다).
    @Enumerated(EnumType.STRING)
    @Column(name = "trigger_type", nullable = false, length = 20)
    private PaymentHistoryTrigger trigger;

    @Column(name = "reason", length = 255)
    private String reason;

    // Product.description과 동일한 컨벤션(@Lob 대신 columnDefinition) — @Lob은 MySQL에서
    // longtext로 매핑되어 ddl.sql의 text 컬럼과 ddl-auto=validate 시 타입 불일치를 일으킨다.
    @Column(name = "pg_raw_response", columnDefinition = "TEXT")
    private String pgRawResponse;

    @CreatedDate
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    private PaymentHistory(Payment payment, PaymentStatus fromStatus, PaymentStatus toStatus,
                            PaymentHistoryTrigger trigger, String reason, String pgRawResponse) {
        this.payment = payment;
        this.fromStatus = fromStatus;
        this.toStatus = toStatus;
        this.trigger = trigger;
        this.reason = reason;
        this.pgRawResponse = pgRawResponse;
    }

    public static PaymentHistory record(Payment payment, PaymentStatus fromStatus, PaymentStatus toStatus,
                                         PaymentHistoryTrigger trigger, String reason, String pgRawResponse) {
        return new PaymentHistory(payment, fromStatus, toStatus, trigger, reason, pgRawResponse);
    }
}
