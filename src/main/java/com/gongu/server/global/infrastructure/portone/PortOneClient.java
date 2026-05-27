package com.gongu.server.global.infrastructure.portone;

import com.gongu.server.global.exception.InfraException;
import com.gongu.server.global.exception.errorcode.PaymentErrorCode;
import com.gongu.server.global.infrastructure.portone.dto.PortOnePaymentResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.RestClient;

import java.util.Map;

@Slf4j
@Component
@RequiredArgsConstructor
public class PortOneClient {

    private final RestClient portOneRestClient;

    public PortOnePaymentResponse getPayment(String paymentId) {
        try {
            return portOneRestClient.get()
                    .uri("/payments/{paymentId}", paymentId)
                    .retrieve()
                    .body(PortOnePaymentResponse.class);
        } catch (HttpClientErrorException | HttpServerErrorException e) {
            log.error("PortOne getPayment failed: paymentId={}, status={}", paymentId, e.getStatusCode());
            throw new InfraException(PaymentErrorCode.PAYMENT_PG_UNAVAILABLE);
        } catch (Exception e) {
            log.error("PortOne getPayment unexpected error: paymentId={}", paymentId, e);
            throw new InfraException(PaymentErrorCode.PAYMENT_PG_UNAVAILABLE);
        }
    }

    public void cancelPayment(String paymentId, String reason) {
        try {
            portOneRestClient.post()
                    .uri("/payments/{paymentId}/cancel", paymentId)
                    .body(Map.of("reason", reason))
                    .retrieve()
                    .toBodilessEntity();
        } catch (HttpClientErrorException | HttpServerErrorException e) {
            log.error("PortOne cancelPayment failed: paymentId={}, status={}", paymentId, e.getStatusCode());
            throw new InfraException(PaymentErrorCode.PAYMENT_PG_UNAVAILABLE);
        } catch (Exception e) {
            log.error("PortOne cancelPayment unexpected error: paymentId={}", paymentId, e);
            throw new InfraException(PaymentErrorCode.PAYMENT_PG_UNAVAILABLE);
        }
    }
}
