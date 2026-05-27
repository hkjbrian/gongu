package com.gongu.server.global.infrastructure.portone.dto;

import java.time.OffsetDateTime;

public record PortOnePaymentResponse(
    String id,
    String status,
    Amount amount,
    OffsetDateTime paidAt
) {
    public record Amount(Long total) {}
}
