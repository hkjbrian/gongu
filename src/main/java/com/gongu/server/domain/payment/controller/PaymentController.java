package com.gongu.server.domain.payment.controller;

import com.gongu.server.domain.payment.dto.PaymentPrepareResult;
import com.gongu.server.domain.payment.dto.request.PortOneWebhookPayload;
import com.gongu.server.domain.payment.dto.request.PreparePaymentRequest;
import com.gongu.server.domain.payment.dto.request.VerifyPaymentRequest;
import com.gongu.server.domain.payment.dto.response.PreparePaymentResponse;
import com.gongu.server.domain.payment.dto.response.VerifyPaymentResponse;
import com.gongu.server.domain.payment.service.PaymentService;
import com.gongu.server.global.common.ApiResponse;
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

@RestController
@RequestMapping("/payments")
@RequiredArgsConstructor
public class PaymentController {

    private final PaymentService paymentService;

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
            paymentService.completePayment(payload.data().paymentId());
        }
        return ResponseEntity.ok().build();
    }
}
