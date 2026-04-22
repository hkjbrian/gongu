package com.gongu.server.domain.auth.client;

import com.gongu.server.domain.auth.client.dto.KakaoUserInfo;
import com.gongu.server.global.exception.InfraException;
import com.gongu.server.global.exception.errorcode.AuthErrorCode;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

@Component
public class KakaoApiClient {

    private final RestClient restClient;

    public KakaoApiClient() {
        this.restClient = RestClient.builder()
                .baseUrl("https://kapi.kakao.com")
                .build();
    }

    public KakaoUserInfo getUserInfo(String kakaoAccessToken) {
        try {
            return restClient.get()
                    .uri("/v2/user/me")
                    .header("Authorization", "Bearer " + kakaoAccessToken)
                    .retrieve()
                    .body(KakaoUserInfo.class);
        } catch (Exception e) {
            throw new InfraException(AuthErrorCode.KAKAO_API_ERROR);
        }
    }
}
