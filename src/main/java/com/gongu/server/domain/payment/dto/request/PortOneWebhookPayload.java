package com.gongu.server.domain.payment.dto.request;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/**
 * PortOne V2 웹훅 페이로드.
 * 참고: https://developers.portone.io/docs/ko/v2/api/webhook
 */
public record PortOneWebhookPayload(
        @JsonProperty("type") String type,
        @NotNull @Valid @JsonProperty("data") WebhookData data
) {
    public record WebhookData(
            @NotBlank @JsonProperty("paymentId") String paymentId
    ) {}
}
