package com.gongu.server.global.infrastructure.portone;

import com.gongu.server.global.exception.BusinessException;
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
        } catch (HttpClientErrorException e) {
            log.warn("PortOne getPayment client error: paymentId={}, status={}", paymentId, e.getStatusCode());
            throw new BusinessException(PaymentErrorCode.PAYMENT_NOT_FOUND);
        } catch (HttpServerErrorException e) {
            log.error("PortOne getPayment server error: paymentId={}, status={}", paymentId, e.getStatusCode());
            throw new InfraException(PaymentErrorCode.PAYMENT_PG_UNAVAILABLE);
        } catch (Exception e) {
            log.error("PortOne getPayment unexpected error: paymentId={}", paymentId, e);
            throw new InfraException(PaymentErrorCode.PAYMENT_PG_UNAVAILABLE);
        }
    }

    public PortOnePaymentResponse cancelPayment(String paymentId, String reason) {
        try {
            return portOneRestClient.post()
                    .uri("/payments/{paymentId}/cancel", paymentId)
                    .body(Map.of("reason", reason))
                    .retrieve()
                    .body(PortOnePaymentResponse.class);
        } catch (HttpClientErrorException e) {
            log.warn("PortOne cancelPayment client error: paymentId={}, status={}", paymentId, e.getStatusCode());
            throw new BusinessException(PaymentErrorCode.PAYMENT_NOT_FOUND);
        } catch (HttpServerErrorException e) {
            log.error("PortOne cancelPayment server error: paymentId={}, status={}", paymentId, e.getStatusCode());
            throw new InfraException(PaymentErrorCode.PAYMENT_PG_UNAVAILABLE);
        } catch (Exception e) {
            log.error("PortOne cancelPayment unexpected error: paymentId={}", paymentId, e);
            throw new InfraException(PaymentErrorCode.PAYMENT_PG_UNAVAILABLE);
        }
    }
}
