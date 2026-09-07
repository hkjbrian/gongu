package com.gongu.server.domain.payment.controller;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.gongu.server.domain.payment.dto.PaymentPrepareResult;
import com.gongu.server.domain.payment.dto.request.PortOneWebhookPayload;
import com.gongu.server.domain.payment.dto.request.PreparePaymentRequest;
import com.gongu.server.domain.payment.dto.request.VerifyPaymentRequest;
import com.gongu.server.domain.payment.dto.response.PreparePaymentResponse;
import com.gongu.server.domain.payment.dto.response.VerifyPaymentResponse;
import com.gongu.server.domain.payment.service.PaymentService;
import com.gongu.server.global.common.ApiResponse;
import com.gongu.server.global.exception.BusinessException;
import com.gongu.server.global.exception.ErrorCode;
import com.gongu.server.global.exception.errorcode.PaymentErrorCode;
import com.gongu.server.global.infrastructure.portone.PortOneWebhookVerifier;
import com.gongu.server.global.security.UserPrincipal;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Set;

@Slf4j
@RestController
@RequestMapping("/payments")
@RequiredArgsConstructor
public class PaymentController {

    private final PaymentService paymentService;
    private final PortOneWebhookVerifier portOneWebhookVerifier;
    private final ObjectMapper objectMapper;

    /**
     * 재처리해도 결과가 달라지지 않는 터미널/이미처리 결과 코드.
     * PortOne 웹훅 재시도를 멈추기 위해 200을 반환한다.
     */
    private static final Set<ErrorCode> WEBHOOK_TERMINAL_CODES = Set.of(
            PaymentErrorCode.ORDER_EXPIRED_REFUNDED,
            PaymentErrorCode.PAYMENT_NOT_COMPLETED,
            PaymentErrorCode.PAYMENT_INVALID_STATE_TRANSITION,
            PaymentErrorCode.PAYMENT_AMOUNT_MISMATCH
    );

    @PostMapping("/prepare")
    @PreAuthorize("hasRole('USER')")
    public ResponseEntity<ApiResponse<PreparePaymentResponse>> preparePayment(
            @Valid @RequestBody PreparePaymentRequest request,
            @AuthenticationPrincipal UserPrincipal userPrincipal) {
        PaymentPrepareResult result = paymentService.preparePayment(userPrincipal.id(), request.orderId());
        return ResponseEntity.ok(ApiResponse.success(PreparePaymentResponse.of(result)));
    }

    @PostMapping("/verify")
    @PreAuthorize("hasRole('USER')")
    public ResponseEntity<ApiResponse<VerifyPaymentResponse>> verifyPayment(
            @Valid @RequestBody VerifyPaymentRequest request,
            @AuthenticationPrincipal UserPrincipal userPrincipal) {
        paymentService.validateOwnership(userPrincipal.id(), request.paymentId());
        VerifyPaymentResponse result = paymentService.completePayment(request.paymentId());
        return ResponseEntity.ok(ApiResponse.success(result));
    }

    @PostMapping("/webhook")
    public ResponseEntity<Void> receiveWebhook(
            @RequestBody(required = false) String rawBody,
            @RequestHeader(value = "webhook-id", required = false) String webhookId,
            @RequestHeader(value = "webhook-timestamp", required = false) String webhookTimestamp,
            @RequestHeader(value = "webhook-signature", required = false) String webhookSignature) {

        // 서명 검증은 요청 바디가 UTF-8임을 전제한다 (PortOne JSON은 UTF-8).
        // server.servlet.encoding.charset 을 바꾸면 서명 대상 바이트가 어긋나 검증이 깨진다.
        portOneWebhookVerifier.verify(rawBody, webhookId, webhookTimestamp, webhookSignature);

        PortOneWebhookPayload payload = parseWebhookPayload(rawBody);

        if ("Transaction.Paid".equals(payload.type())) {
            String paymentId = payload.data() == null ? null : payload.data().paymentId();
            if (!StringUtils.hasText(paymentId)) {
                log.warn("웹훅 페이로드에 paymentId 없음");
                throw new BusinessException(PaymentErrorCode.WEBHOOK_VERIFICATION_FAILED);
            }
            try {
                paymentService.completePayment(paymentId);
            } catch (BusinessException e) {
                if (WEBHOOK_TERMINAL_CODES.contains(e.getErrorCode())) {
                    // 재처리해도 결과가 동일한 확정 상태 — PortOne 재시도를 멈추기 위해 200 반환
                    return ResponseEntity.ok().build();
                }
                // 판정 불가(PAYMENT_PG_UNAVAILABLE) 등 — 재시도가 의미 있으므로 비2xx 전파
                throw e;
            }
        }
        return ResponseEntity.ok().build();
    }

    private PortOneWebhookPayload parseWebhookPayload(String rawBody) {
        try {
            PortOneWebhookPayload payload = objectMapper.readValue(rawBody, PortOneWebhookPayload.class);
            if (payload == null) {
                log.warn("웹훅 페이로드가 null 로 파싱됨");
                throw new BusinessException(PaymentErrorCode.WEBHOOK_VERIFICATION_FAILED);
            }
            return payload;
        } catch (JsonProcessingException e) {
            log.warn("웹훅 페이로드 파싱 실패", e);
            throw new BusinessException(PaymentErrorCode.WEBHOOK_VERIFICATION_FAILED);
        }
    }
}
