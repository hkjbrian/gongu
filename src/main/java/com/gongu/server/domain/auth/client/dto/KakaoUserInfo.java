package com.gongu.server.domain.auth.client.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

public record KakaoUserInfo(
        Long id,
        @JsonProperty("kakao_account") KakaoAccount kakaoAccount
) {

    public record KakaoAccount(
            String email,
            Profile profile
    ) {}

    public record Profile(String nickname) {}
}
