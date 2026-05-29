package com.gongu.server.domain.payment.dto.response;

import com.gongu.server.domain.payment.dto.PaymentPrepareResult;

public record PreparePaymentResponse(
        String paymentId,
        Long amount
) {
    public static PreparePaymentResponse of(PaymentPrepareResult result) {
        return new PreparePaymentResponse(result.paymentId(), result.amount());
    }
}
