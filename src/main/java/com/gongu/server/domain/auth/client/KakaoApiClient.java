package com.gongu.server.domain.auth.client;

import com.gongu.server.domain.auth.client.dto.KakaoUserInfo;
import com.gongu.server.global.exception.InfraException;
import com.gongu.server.global.exception.errorcode.AuthErrorCode;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

@Component
public class KakaoApiClient {

    private final RestClient restClient;

    /**
     * Spring Boot가 자동 구성한 RestClient.Builder를 주입받아 사용한다.
     * application.yml의 spring.http.client.connect-timeout / read-timeout 설정이 적용된다.
     */
    public KakaoApiClient(RestClient.Builder restClientBuilder) {
        this.restClient = restClientBuilder
                .baseUrl("https://kapi.kakao.com")
                .build();
    }

    public KakaoUserInfo getUserInfo(String kakaoAccessToken) {
        try {
            KakaoUserInfo userInfo = restClient.get()
                    .uri("/v2/user/me")
                    .header("Authorization", "Bearer " + kakaoAccessToken)
                    .retrieve()
                    .body(KakaoUserInfo.class);

            // id가 null이면 카카오로부터 유효한 응답이 아님
            if (userInfo == null || userInfo.id() == null) {
                throw new InfraException(AuthErrorCode.KAKAO_API_ERROR);
            }

            return userInfo;
        } catch (InfraException e) {
            throw e;
        } catch (Exception e) {
            throw new InfraException(AuthErrorCode.KAKAO_API_ERROR);
        }
    }
}
