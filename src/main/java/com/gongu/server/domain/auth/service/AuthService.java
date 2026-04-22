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
import org.springframework.dao.DataIntegrityViolationException;
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
                .orElseGet(() -> registerKakaoMember(userInfo, socialId));

        // 3. 토큰 발급 및 저장
        String accessToken = jwtProvider.generateAccessToken(member.getId(), Role.MEMBER);
        String refreshToken = jwtProvider.generateRefreshToken(member.getId());
        refreshTokenStore.save(member.getId(), refreshToken);

        // 4. 토큰 반환
        return new TokenResponse(accessToken, refreshToken);
    }

    /**
     * 신규 카카오 회원 등록.
     * 동시 요청으로 UNIQUE 제약 충돌 시 DataIntegrityViolationException을 잡아
     * 이미 등록된 UserSocial을 재조회한다.
     */
    private Member registerKakaoMember(KakaoUserInfo userInfo, String socialId) {
        String nickname = "";
        // 이메일 미제공 시 socialId 기반 고유 fallback 이메일 사용 (UNIQUE 제약 충족)
        String email = "kakao-" + socialId + "@noemail.local";

        if (userInfo.kakaoAccount() != null) {
            KakaoUserInfo.KakaoAccount account = userInfo.kakaoAccount();
            if (account.profile() != null && account.profile().nickname() != null) {
                nickname = account.profile().nickname();
            }
            if (account.email() != null) {
                email = account.email();
            }
        }

        try {
            Member newMember = memberRepository.save(Member.of(nickname, email, ""));
            userSocialRepository.save(UserSocial.of(newMember, SocialProvider.KAKAO, socialId));
            return newMember;
        } catch (DataIntegrityViolationException e) {
            // 동시 요청으로 인한 중복 삽입 — 이미 등록된 소셜 계정 재조회
            return userSocialRepository.findByProviderAndSocialId(SocialProvider.KAKAO, socialId)
                    .map(UserSocial::getMember)
                    .orElseThrow(() -> e);
        }
    }
}
