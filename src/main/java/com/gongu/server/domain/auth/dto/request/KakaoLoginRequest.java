package com.gongu.server.domain.auth.dto.request;

import jakarta.validation.constraints.NotBlank;

public record KakaoLoginRequest(
        @NotBlank String accessToken  // 프론트엔드가 카카오 SDK로 획득한 카카오 액세스 토큰
) {}
