package com.gongu.server.domain.payment.metrics;

import com.gongu.server.domain.payment.domain.PaymentStatus;
import com.gongu.server.domain.payment.repository.PaymentRepository;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.binder.MeterBinder;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;

/**
 * 미결(PENDING) 결제를 게이지로 노출한다. PG 응답을 확인하지 못한 결제는 취소하지 않고 PENDING 으로 남기므로
 * (ADR 결제 확정), PG 장애 때 가장 먼저 늘어나는 지표가 이 둘이다.
 * 게이지 값은 Prometheus scrape 때마다 DB 에서 읽는다 (payments.status 인덱스 사용).
 */
@Slf4j
@Component
public class PendingPaymentMetrics implements MeterBinder {

    private final PaymentRepository paymentRepository;
    private final Clock clock;

    @Autowired
    public PendingPaymentMetrics(PaymentRepository paymentRepository) {
        this(paymentRepository, Clock.systemDefaultZone());
    }

    PendingPaymentMetrics(PaymentRepository paymentRepository, Clock clock) {
        this.paymentRepository = paymentRepository;
        this.clock = clock;
    }

    @Override
    public void bindTo(MeterRegistry registry) {
        Gauge.builder("gongu.payment.pending", this, PendingPaymentMetrics::pendingCount)
                .description("미결(PENDING) 결제 수 — PG 확인이 끝나지 않은 결제")
                .register(registry);
        Gauge.builder("gongu.payment.pending.oldest.age", this, PendingPaymentMetrics::oldestPendingAgeSeconds)
                .description("가장 오래된 미결 결제의 경과 시간(초) — 만료 처리 후에도 남아 있으면 확인 대상")
                .baseUnit("seconds")
                .register(registry);
    }

    private double pendingCount() {
        try {
            return paymentRepository.countByStatus(PaymentStatus.PENDING);
        } catch (RuntimeException e) {
            log.warn("미결 결제 수 조회 실패", e);
            return Double.NaN;
        }
    }

    private double oldestPendingAgeSeconds() {
        try {
            return paymentRepository.findOldestCreatedAtByStatus(PaymentStatus.PENDING)
                    .map(oldest -> (double) Duration.between(oldest, LocalDateTime.now(clock)).toSeconds())
                    .orElse(0.0);
        } catch (RuntimeException e) {
            log.warn("가장 오래된 미결 결제 경과 시간 조회 실패", e);
            return Double.NaN;
        }
    }
}
