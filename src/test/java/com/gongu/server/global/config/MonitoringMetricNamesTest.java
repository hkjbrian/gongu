package com.gongu.server.global.config;

import io.micrometer.prometheusmetrics.PrometheusMeterRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.actuate.observability.AutoConfigureObservability;
import org.springframework.boot.test.context.SpringBootTest;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * monitoring/ 의 Grafana 대시보드·Prometheus 알림 규칙이 쓰는 메트릭 이름이 실제 /actuator/prometheus 출력에 있는지 고정한다.
 * 이름은 코드 상의 이름이 아니라 Prometheus 로 노출되는 이름이다 (예: Counter "gongu.order.created" 는
 * Prometheus 클라이언트 1.x 가 "_created" 접미사를 예약어로 취급해 gongu_order_total 로 노출된다).
 */
@SpringBootTest
@AutoConfigureObservability
class MonitoringMetricNamesTest {

    @Autowired private PrometheusMeterRegistry prometheusRegistry;

    @Test
    @DisplayName("대시보드·알림 규칙이 쓰는 메트릭이 Prometheus 출력에 노출된다")
    void exposesMetricsUsedByDashboardAndAlertRules() {
        String scrape = prometheusRegistry.scrape();

        assertThat(scrape).contains(
                // 비즈니스 플로우
                "\ngongu_order_total ",
                "\ngongu_order_expired_total ",
                "\ngongu_payment_completed_total ",
                "gongu_payment_failed_total{reason=\"pg_status_mismatch\"}",
                "gongu_payment_failed_total{reason=\"amount_mismatch\"}",
                // 미결·운영자 확인 대상
                "\ngongu_payment_pending ",
                "\ngongu_payment_pending_oldest_age_seconds ",
                "\ngongu_payment_pg_fetch_under_lock_total ",
                "\ngongu_payment_expiry_reconcile_exhausted_total ",
                // PG 연동 방어 (Resilience4j)
                "resilience4j_circuitbreaker_state{name=\"portone\",state=\"open\"}",
                "resilience4j_circuitbreaker_state{name=\"portone\",state=\"half_open\"}",
                "resilience4j_circuitbreaker_failure_rate{name=\"portone\"}",
                "resilience4j_circuitbreaker_calls_seconds_count{kind=\"failed\",name=\"portone\"}",
                "resilience4j_circuitbreaker_not_permitted_calls_total{kind=\"not_permitted\",name=\"portone\"}",
                "resilience4j_retry_calls_total{kind=\"failed_with_retry\",name=\"portone\"}",
                "resilience4j_bulkhead_available_concurrent_calls{name=\"payment-complete\"}",
                // 인프라
                "hikaricp_connections_pending");
    }
}
