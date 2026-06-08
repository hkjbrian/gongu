package com.gongu.server.global.config;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
@RequiredArgsConstructor
public class MetricsConfig {

    private final MeterRegistry meterRegistry;

    @Bean
    public Counter orderCreatedCounter() {
        return Counter.builder("gongu.order.created")
                .description("생성된 주문 수")
                .register(meterRegistry);
    }

    @Bean
    public Counter paymentCompletedCounter() {
        return Counter.builder("gongu.payment.completed")
                .description("완료된 결제 수")
                .register(meterRegistry);
    }

    @Bean
    public Counter orderExpiredCounter() {
        return Counter.builder("gongu.order.expired")
                .description("만료 처리된 주문 수")
                .register(meterRegistry);
    }

    @Bean
    public Timer orderExpireDuration() {
        return Timer.builder("gongu.order.expire.duration")
                .description("만료 스케줄러 1회 실행 시간")
                .register(meterRegistry);
    }

    @Bean
    public Timer lockWaitOrderTimer() {
        return Timer.builder("gongu.db.lock.query_duration")
                .tag("entity", "order")
                .register(meterRegistry);
    }

    @Bean
    public Timer lockWaitProductTimer() {
        return Timer.builder("gongu.db.lock.query_duration")
                .tag("entity", "product")
                .register(meterRegistry);
    }

    @Bean
    public Counter paymentFailedOrderExpiredIdempotentCounter() {
        return paymentFailedCounter("order_expired_idempotent");
    }

    @Bean
    public Counter paymentFailedOrderExpiredCancelCounter() {
        return paymentFailedCounter("order_expired_cancel");
    }

    @Bean
    public Counter paymentFailedPgErrorCounter() {
        return paymentFailedCounter("pg_error");
    }

    @Bean
    public Counter paymentFailedPgNullCounter() {
        return paymentFailedCounter("pg_null_response");
    }

    @Bean
    public Counter paymentFailedPgStatusMismatchCounter() {
        return paymentFailedCounter("pg_status_mismatch");
    }

    @Bean
    public Counter paymentFailedAmountMismatchCounter() {
        return paymentFailedCounter("amount_mismatch");
    }

    private Counter paymentFailedCounter(String reason) {
        return Counter.builder("gongu.payment.failed")
                .tag("reason", reason)
                .register(meterRegistry);
    }
}
