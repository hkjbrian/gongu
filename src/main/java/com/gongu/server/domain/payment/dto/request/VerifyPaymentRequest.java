package com.gongu.server.domain.payment.dto.request;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

public record VerifyPaymentRequest(
        @NotNull @JsonProperty("order_id") Long orderId,
        @NotBlank @JsonProperty("payment_id") String paymentId
) {}
