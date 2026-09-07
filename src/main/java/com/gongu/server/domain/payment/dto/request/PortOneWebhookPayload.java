package com.gongu.server.domain.payment.dto.request;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * PortOne V2 웹훅 페이로드.
 * 참고: https://developers.portone.io/opi/ko/integration/webhook/readme-v2
 * 서명 검증 후 {@code PaymentController}에서 ObjectMapper로 직접 파싱한다.
 */
public record PortOneWebhookPayload(
        @JsonProperty("type") String type,
        @JsonProperty("data") WebhookData data
) {
    public record WebhookData(
            @JsonProperty("paymentId") String paymentId
    ) {}
}
