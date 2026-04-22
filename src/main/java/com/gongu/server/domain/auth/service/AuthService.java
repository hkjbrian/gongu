package com.gongu.server.domain.auth.service;

import com.gongu.server.domain.auth.client.KakaoApiClient;
import com.gongu.server.domain.auth.client.dto.KakaoUserInfo;
import com.gongu.server.domain.auth.dto.request.StoreAdminLoginRequest;
import com.gongu.server.domain.auth.dto.response.TokenResponse;
import com.gongu.server.domain.store.entity.StoreAdmin;
import com.gongu.server.domain.store.repository.StoreAdminRepository;
import com.gongu.server.domain.user.entity.Member;
import com.gongu.server.domain.user.entity.SocialProvider;
import com.gongu.server.domain.user.entity.UserSocial;
import com.gongu.server.domain.user.repository.MemberRepository;
import com.gongu.server.domain.user.repository.UserSocialRepository;
import com.gongu.server.global.exception.BusinessException;
import com.gongu.server.global.exception.errorcode.AuthErrorCode;
import com.gongu.server.global.security.Role;
import com.gongu.server.global.security.jwt.JwtProvider;
import com.gongu.server.global.security.jwt.RefreshTokenStore;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;

@Service
@RequiredArgsConstructor
@Transactional
public class AuthService {

    /**
     * 타이밍 어택 방지용 더미 BCrypt 해시.
     * 이메일이 존재하지 않을 때도 항상 passwordEncoder.matches()를 실행해
     * 응답 시간을 일정하게 유지한다.
     */
    private static final String DUMMY_HASH = "$2a$10$7EqJtq98hPqEX7fNZaFWoOe1F0FQT9VgQHtP5WrNwrQeX9wQZvJ3K";

    private final KakaoApiClient kakaoApiClient;
    private final MemberRepository memberRepository;
    private final UserSocialRepository userSocialRepository;
    private final StoreAdminRepository storeAdminRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtProvider jwtProvider;
    private final RefreshTokenStore refreshTokenStore;

    public TokenResponse kakaoLogin(String kakaoAccessToken) {
        KakaoUserInfo userInfo = kakaoApiClient.getUserInfo(kakaoAccessToken);
        String socialId = String.valueOf(userInfo.id());

        Member member = userSocialRepository.findByProviderAndSocialId(SocialProvider.KAKAO, socialId)
                .map(UserSocial::getMember)
                .orElseGet(() -> registerKakaoMember(userInfo, socialId));

        String accessToken = jwtProvider.generateAccessToken(member.getId(), Role.MEMBER);
        String refreshToken = jwtProvider.generateRefreshToken(member.getId());
        refreshTokenStore.save(member.getId(), Role.MEMBER, refreshToken);

        return new TokenResponse(accessToken, refreshToken);
    }

    public TokenResponse storeAdminLogin(StoreAdminLoginRequest request) {
        // 타이밍 어택 방지: 이메일 미존재 시에도 더미 해시로 BCrypt를 항상 실행해 응답 시간을 일정하게 유지
        Optional<StoreAdmin> adminOpt = storeAdminRepository.findByEmailAndIsActiveTrue(request.email());
        String hashToCheck = adminOpt.map(StoreAdmin::getPassword).orElse(DUMMY_HASH);
        boolean passwordMatches = passwordEncoder.matches(request.password(), hashToCheck);

        if (adminOpt.isEmpty() || !passwordMatches) {
            throw new BusinessException(AuthErrorCode.INVALID_CREDENTIALS);
        }

        StoreAdmin storeAdmin = adminOpt.get();
        String accessToken = jwtProvider.generateAccessToken(storeAdmin.getId(), Role.STORE_ADMIN);
        String refreshToken = jwtProvider.generateRefreshToken(storeAdmin.getId());
        refreshTokenStore.save(storeAdmin.getId(), Role.STORE_ADMIN, refreshToken);

        return new TokenResponse(accessToken, refreshToken);
    }

    /**
     * 신규 카카오 회원 등록.
     * 동시 요청으로 UNIQUE 제약 충돌 시 DataIntegrityViolationException을 잡아
     * 이미 등록된 UserSocial을 재조회한다.
     */
    private Member registerKakaoMember(KakaoUserInfo userInfo, String socialId) {
        String nickname = "";
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
            return userSocialRepository.findByProviderAndSocialId(SocialProvider.KAKAO, socialId)
                    .map(UserSocial::getMember)
                    .orElseThrow(() -> e);
        }
    }
}
