package com.gongu.server.global.exception;

import com.gongu.server.global.common.ErrorResponse;
import com.gongu.server.global.exception.errorcode.PaymentErrorCode;
import io.github.resilience4j.bulkhead.Bulkhead;
import io.github.resilience4j.bulkhead.BulkheadFullException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("GlobalExceptionHandler")
class GlobalExceptionHandlerTest {

    private final GlobalExceptionHandler handler = new GlobalExceptionHandler();

    @Test
    @DisplayName("BulkheadFullException → 503 PAYMENT_PG_UNAVAILABLE")
    void bulkheadFull_returns503() {
        Bulkhead bulkhead = Bulkhead.ofDefaults("t");
        BulkheadFullException ex = BulkheadFullException.createBulkheadFullException(bulkhead);

        ResponseEntity<ErrorResponse> res = handler.handleBulkheadFull(ex);

        assertThat(res.getStatusCode().value()).isEqualTo(503);
        assertThat(res.getBody()).isNotNull();
        assertThat(res.getBody().getCode()).isEqualTo(PaymentErrorCode.PAYMENT_PG_UNAVAILABLE.getCode());
    }
}
