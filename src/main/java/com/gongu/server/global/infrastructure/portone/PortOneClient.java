package com.gongu.server.global.infrastructure.portone;

import com.gongu.server.global.exception.BusinessException;
import com.gongu.server.global.exception.InfraException;
import com.gongu.server.global.exception.errorcode.PaymentErrorCode;
import com.gongu.server.global.infrastructure.portone.dto.PortOnePaymentResponse;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import io.github.resilience4j.retry.annotation.Retry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;

import java.util.Map;

@Slf4j
@Component
@RequiredArgsConstructor
public class PortOneClient {

    private final RestClient portOneRestClient;

    /**
     * 4xx → BusinessException 즉시 전파 (CB/Retry ignore-exceptions 설정으로 장애 집계 제외)
     * 5xx·네트워크 오류 → @Retry 재시도 → 소진 시 CB fallback → InfraException
     */
    @CircuitBreaker(name = "portone", fallbackMethod = "getPaymentFallback")
    @Retry(name = "portone")
    public PortOnePaymentResponse getPayment(String paymentId) {
        try {
            return portOneRestClient.get()
                    .uri("/payments/{paymentId}", paymentId)
                    .retrieve()
                    .body(PortOnePaymentResponse.class);
        } catch (HttpClientErrorException e) {
            log.warn("PortOne getPayment client error: paymentId={}, status={}", paymentId, e.getStatusCode());
            throw new BusinessException(PaymentErrorCode.PAYMENT_NOT_FOUND);
        }
        // HttpServerErrorException, ResourceAccessException 등은 자연 전파 → @Retry 동작
    }

    private PortOnePaymentResponse getPaymentFallback(String paymentId, Exception e) {
        log.error("PortOne circuit open or retry exhausted for getPayment: paymentId={}", paymentId, e);
        throw new InfraException(PaymentErrorCode.PAYMENT_PG_UNAVAILABLE);
    }

    @CircuitBreaker(name = "portone", fallbackMethod = "cancelPaymentFallback")
    @Retry(name = "portone")
    public PortOnePaymentResponse cancelPayment(String paymentId, String reason) {
        try {
            return portOneRestClient.post()
                    .uri("/payments/{paymentId}/cancel", paymentId)
                    .body(Map.of("reason", reason))
                    .retrieve()
                    .body(PortOnePaymentResponse.class);
        } catch (HttpClientErrorException e) {
            log.warn("PortOne cancelPayment client error: paymentId={}, status={}", paymentId, e.getStatusCode());
            if (e.getStatusCode() == HttpStatus.NOT_FOUND) {
                throw new BusinessException(PaymentErrorCode.PAYMENT_NOT_FOUND);
            }
            // 409 등 도메인 오류 (이미 취소됨 등)
            throw new BusinessException(PaymentErrorCode.PAYMENT_ALREADY_PROCESSED);
        }
        // HttpServerErrorException, ResourceAccessException 등은 자연 전파 → @Retry 동작
    }

    private PortOnePaymentResponse cancelPaymentFallback(String paymentId, String reason, Exception e) {
        log.warn("PortOne cancelPayment circuit open or retry exhausted: paymentId={}", paymentId, e);
        throw new InfraException(PaymentErrorCode.PAYMENT_PG_UNAVAILABLE);
    }
}
