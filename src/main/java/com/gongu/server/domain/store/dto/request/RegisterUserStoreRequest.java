package com.gongu.server.domain.store.dto.request;

import jakarta.validation.constraints.NotNull;

public record RegisterUserStoreRequest(
        @NotNull(message = "매장 ID는 필수입니다") Long storeId,
        @NotNull(message = "선호 매장 여부는 필수입니다") Boolean isPreferred
) {}
