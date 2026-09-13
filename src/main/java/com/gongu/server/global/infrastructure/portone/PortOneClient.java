package com.gongu.server.global.infrastructure.portone;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.gongu.server.global.exception.BusinessException;
import com.gongu.server.global.exception.InfraException;
import com.gongu.server.global.exception.errorcode.PaymentErrorCode;
import com.gongu.server.global.infrastructure.portone.dto.PortOnePaymentResponse;
import com.gongu.server.global.infrastructure.portone.dto.PortOnePaymentResult;
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
    private final ObjectMapper objectMapper;

    /**
     * 4xx → BusinessException 즉시 전파 (CB/Retry ignore-exceptions 설정으로 장애 집계 제외)
     * 5xx·네트워크 오류 → @Retry 재시도 → 소진 시 CB fallback → InfraException
     *
     * 이 순서는 application.yml 의 resilience4j.*-aspect-order 에 의존한다 (#213):
     * {@code @CircuitBreaker} 가 @Retry 보다 바깥이어야 재시도가 원본 예외를 보고 동작한다.
     */
    @CircuitBreaker(name = "portone", fallbackMethod = "getPaymentFallback")
    @Retry(name = "portone")
    public PortOnePaymentResult getPayment(String paymentId) {
        try {
            String rawBody = portOneRestClient.get()
                    .uri("/payments/{paymentId}", paymentId)
                    .retrieve()
                    .body(String.class);
            return new PortOnePaymentResult(parseResponse(paymentId, rawBody), rawBody);
        } catch (HttpClientErrorException e) {
            log.warn("PortOne getPayment client error: paymentId={}, status={}", paymentId, e.getStatusCode());
            throw new BusinessException(PaymentErrorCode.PAYMENT_NOT_FOUND);
        }
        // HttpServerErrorException, ResourceAccessException 등은 자연 전파 → @Retry 동작
    }

    private PortOnePaymentResult getPaymentFallback(String paymentId, Exception e) {
        if (e instanceof BusinessException businessException) {
            throw businessException;
        }
        log.error("PortOne circuit open or retry exhausted for getPayment: paymentId={}", paymentId, e);
        throw new InfraException(PaymentErrorCode.PAYMENT_PG_UNAVAILABLE);
    }

    @CircuitBreaker(name = "portone", fallbackMethod = "cancelPaymentFallback")
    @Retry(name = "portone")
    public PortOnePaymentResult cancelPayment(String paymentId, String reason) {
        try {
            String rawBody = portOneRestClient.post()
                    .uri("/payments/{paymentId}/cancel", paymentId)
                    .body(Map.of("reason", reason))
                    .retrieve()
                    .body(String.class);
            return new PortOnePaymentResult(parseResponse(paymentId, rawBody), rawBody);
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

    private PortOnePaymentResult cancelPaymentFallback(String paymentId, String reason, Exception e) {
        if (e instanceof BusinessException businessException) {
            throw businessException;
        }
        log.warn("PortOne cancelPayment circuit open or retry exhausted: paymentId={}", paymentId, e);
        throw new InfraException(PaymentErrorCode.PAYMENT_PG_UNAVAILABLE);
    }

    /**
     * 빈/공백 바디는 {@code null}을 반환한다 (기존 {@code .retrieve().body(PortOnePaymentResponse.class)}가
     * 빈 바디에 대해 Jackson 역직렬화 없이 null을 반환하던 동작 보존 — completePayment의 "PENDING 유지" 분기가 의존).
     * 반면 비어있지 않은데 파싱이 실패하는 경우는 분류되지 않은 unchecked 예외로 전파한다 — 기존에도
     * Jackson이 던지는 예외가 {@code catch (HttpClientErrorException e)}에 잡히지 않고 그대로 위로
     * 전파되어 BusinessException도 InfraException도 아니었다. 여기서 InfraException으로 바꾸면
     * "PG 조회 실패, PENDING 유지" 분기가 이례적 상황(PG가 200으로 비JSON을 반환)을 조용히 삼키게 되어
     * #209 범위 밖의 동작 변경이 된다.
     */
    private PortOnePaymentResponse parseResponse(String paymentId, String rawBody) {
        if (rawBody == null || rawBody.isBlank()) {
            return null;
        }
        try {
            return objectMapper.readValue(rawBody, PortOnePaymentResponse.class);
        } catch (JsonProcessingException e) {
            log.error("PortOne 응답 파싱 실패 — 원본 body 형식이 예상과 다름: paymentId={}", paymentId, e);
            throw new IllegalStateException("PortOne 응답 파싱 실패: paymentId=" + paymentId, e);
        }
    }
}
