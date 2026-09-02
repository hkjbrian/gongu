package com.gongu.server.domain.payment.controller;

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
import com.gongu.server.global.security.UserPrincipal;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Set;

@RestController
@RequestMapping("/payments")
@RequiredArgsConstructor
public class PaymentController {

    private final PaymentService paymentService;

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
    public ResponseEntity<Void> receiveWebhook(@Valid @RequestBody PortOneWebhookPayload payload) {
        if ("Transaction.Paid".equals(payload.type())) {
            try {
                paymentService.completePayment(payload.data().paymentId());
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
}
