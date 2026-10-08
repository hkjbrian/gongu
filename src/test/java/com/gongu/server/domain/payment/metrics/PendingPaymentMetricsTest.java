package com.gongu.server.domain.payment.metrics;

import com.gongu.server.domain.payment.domain.PaymentStatus;
import com.gongu.server.domain.payment.repository.PaymentRepository;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;

@ExtendWith(MockitoExtension.class)
class PendingPaymentMetricsTest {

    private static final ZoneId ZONE = ZoneId.of("Asia/Seoul");
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-10-08T03:00:00Z"), ZONE);

    @Mock private PaymentRepository paymentRepository;

    private SimpleMeterRegistry registry;
    // 게이지는 대상 객체를 약한 참조로 잡으므로 테스트 동안 binder 를 필드로 붙들어 둔다.
    private PendingPaymentMetrics metrics;

    @BeforeEach
    void setUp() {
        registry = new SimpleMeterRegistry();
        metrics = new PendingPaymentMetrics(paymentRepository, CLOCK);
        metrics.bindTo(registry);
    }

    @Test
    @DisplayName("미결 결제 건수를 게이지로 노출한다")
    void pendingCount() {
        given(paymentRepository.countByStatus(PaymentStatus.PENDING)).willReturn(7L);

        assertThat(registry.get("gongu.payment.pending").gauge().value()).isEqualTo(7.0);
    }

    @Test
    @DisplayName("가장 오래된 미결 결제의 경과 시간을 초 단위로 노출한다")
    void oldestAgeSeconds() {
        LocalDateTime now = LocalDateTime.now(CLOCK);
        given(paymentRepository.findOldestCreatedAtByStatus(PaymentStatus.PENDING))
                .willReturn(Optional.of(now.minusMinutes(20)));

        assertThat(registry.get("gongu.payment.pending.oldest.age").gauge().value()).isEqualTo(1200.0);
    }

    @Test
    @DisplayName("미결 결제가 없으면 경과 시간은 0")
    void oldestAgeZeroWhenNothingPending() {
        given(paymentRepository.findOldestCreatedAtByStatus(PaymentStatus.PENDING)).willReturn(Optional.empty());

        assertThat(registry.get("gongu.payment.pending.oldest.age").gauge().value()).isZero();
    }

    @Test
    @DisplayName("DB 조회가 실패해도 스크랩이 깨지지 않고 NaN을 돌려준다")
    void nanWhenQueryFails() {
        given(paymentRepository.countByStatus(PaymentStatus.PENDING)).willThrow(new RuntimeException("db down"));
        given(paymentRepository.findOldestCreatedAtByStatus(PaymentStatus.PENDING)).willThrow(new RuntimeException("db down"));

        assertThat(registry.get("gongu.payment.pending").gauge().value()).isNaN();
        assertThat(registry.get("gongu.payment.pending.oldest.age").gauge().value()).isNaN();
    }
}
