package com.gongu.server.domain.auth.service;

import com.gongu.server.domain.auth.client.KakaoApiClient;
import com.gongu.server.domain.auth.client.dto.KakaoUserInfo;
import com.gongu.server.domain.auth.dto.response.TokenResponse;
import com.gongu.server.domain.user.entity.Member;
import com.gongu.server.domain.user.entity.SocialProvider;
import com.gongu.server.domain.user.entity.UserSocial;
import com.gongu.server.domain.user.repository.MemberRepository;
import com.gongu.server.domain.user.repository.UserSocialRepository;
import com.gongu.server.global.security.Role;
import com.gongu.server.global.security.jwt.JwtProvider;
import com.gongu.server.global.security.jwt.RefreshTokenStore;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Transactional
public class AuthService {

    private final KakaoApiClient kakaoApiClient;
    private final MemberRepository memberRepository;
    private final UserSocialRepository userSocialRepository;
    private final JwtProvider jwtProvider;
    private final RefreshTokenStore refreshTokenStore;

    public TokenResponse kakaoLogin(String kakaoAccessToken) {
        // 1. 카카오 사용자 정보 조회
        KakaoUserInfo userInfo = kakaoApiClient.getUserInfo(kakaoAccessToken);

        String socialId = String.valueOf(userInfo.id());

        // 2. UserSocial 조회
        Member member = userSocialRepository.findByProviderAndSocialId(SocialProvider.KAKAO, socialId)
                .map(UserSocial::getMember)
                .orElseGet(() -> {
                    // 3-A. 신규 회원 가입
                    String nickname = "";
                    String email = "";

                    if (userInfo.kakaoAccount() != null) {
                        KakaoUserInfo.KakaoAccount account = userInfo.kakaoAccount();

                        if (account.profile() != null && account.profile().nickname() != null) {
                            nickname = account.profile().nickname();
                        }
                        if (account.email() != null) {
                            email = account.email();
                        }
                    }

                    Member newMember = memberRepository.save(Member.of(nickname, email, ""));
                    userSocialRepository.save(UserSocial.of(newMember, SocialProvider.KAKAO, socialId));
                    return newMember;
                });

        // 4-6. 토큰 발급 및 저장
        String accessToken = jwtProvider.generateAccessToken(member.getId(), Role.MEMBER);
        String refreshToken = jwtProvider.generateRefreshToken(member.getId());
        refreshTokenStore.save(member.getId(), refreshToken);

        // 7. 토큰 반환
        return new TokenResponse(accessToken, refreshToken);
    }
}
