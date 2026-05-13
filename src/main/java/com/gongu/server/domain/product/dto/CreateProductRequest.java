package com.gongu.server.domain.product.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

import java.time.LocalDateTime;

public record CreateProductRequest(
        @NotBlank String name,
        @NotBlank String description,
        @Positive Long price,
        @Positive int totalStock,
        @NotNull LocalDateTime startAt,
        @NotNull LocalDateTime endAt
) {}
