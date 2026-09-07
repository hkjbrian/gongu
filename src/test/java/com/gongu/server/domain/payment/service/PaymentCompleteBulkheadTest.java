package com.gongu.server.domain.payment.service;

import io.github.resilience4j.bulkhead.Bulkhead;
import io.github.resilience4j.bulkhead.BulkheadFullException;
import io.github.resilience4j.bulkhead.BulkheadRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest(properties = {
        "portone.api-secret=test-secret",
        "portone.base-url=https://api.portone.test"
})
@DisplayName("completePayment Bulkhead (#222)")
class PaymentCompleteBulkheadTest {

    @Autowired
    private PaymentService paymentService;
    @Autowired
    private BulkheadRegistry bulkheadRegistry;

    private final List<Runnable> releasers = new ArrayList<>();

    @AfterEach
    void releaseAll() {
        releasers.forEach(Runnable::run);
        releasers.clear();
    }

    @Test
    @DisplayName("payment-complete permit이 모두 점유되면 completePayment는 BulkheadFullException을 던진다")
    void completePayment_rejectsWhenBulkheadFull() {
        Bulkhead bulkhead = bulkheadRegistry.bulkhead("payment-complete");
        assertThat(bulkhead.getBulkheadConfig().getMaxConcurrentCalls()).isEqualTo(20);

        for (int i = 0; i < 20; i++) {
            boolean acquired = bulkhead.tryAcquirePermission();
            assertThat(acquired).isTrue();
            releasers.add(bulkhead::releasePermission);
        }

        assertThatThrownBy(() -> paymentService.completePayment("no-such-payment"))
                .isInstanceOf(BulkheadFullException.class);
    }
}
