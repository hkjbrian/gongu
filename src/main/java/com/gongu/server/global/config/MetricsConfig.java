package com.gongu.server.global.config;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class MetricsConfig {

    private final MeterRegistry meterRegistry;

    public MetricsConfig(MeterRegistry meterRegistry) {
        this.meterRegistry = meterRegistry;
    }

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
    public Counter paymentFailedCounter() {
        return Counter.builder("gongu.payment.failed")
                .description("실패한 결제 수")
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
}
