package com.gongu.server.global.infrastructure.portone.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.time.LocalDateTime;

public record PortOnePaymentResponse(
    String id,
    String status,
    Amount amount,
    @JsonProperty("paidAt") LocalDateTime paidAt
) {
    public record Amount(Long total) {}
}
