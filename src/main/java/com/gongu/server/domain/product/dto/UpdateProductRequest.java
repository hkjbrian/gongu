package com.gongu.server.domain.product.dto;

import jakarta.validation.constraints.Positive;

import java.time.LocalDateTime;

public record UpdateProductRequest(
        String name,
        String description,
        @Positive Long price,
        @Positive Integer totalStock,
        LocalDateTime startAt,
        LocalDateTime endAt
) {}
