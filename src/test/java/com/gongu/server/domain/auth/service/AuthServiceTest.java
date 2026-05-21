package com.gongu.server.domain.auth.service;

import com.gongu.server.domain.auth.client.KakaoApiClient;
import com.gongu.server.domain.auth.client.dto.KakaoUserInfo;
import com.gongu.server.domain.auth.dto.request.StoreAdminLoginRequest;
import com.gongu.server.domain.auth.dto.request.TokenRefreshRequest;
import com.gongu.server.domain.auth.dto.response.AccessTokenResponse;
import com.gongu.server.domain.auth.dto.response.TokenResponse;
import com.gongu.server.domain.store.entity.Store;
import com.gongu.server.domain.store.entity.StoreAdmin;
import com.gongu.server.domain.store.repository.StoreAdminRepository;
import com.gongu.server.domain.user.entity.SocialProvider;
import com.gongu.server.domain.user.entity.User;
import com.gongu.server.domain.user.entity.UserSocial;
import com.gongu.server.domain.user.repository.UserRepository;
import com.gongu.server.domain.user.repository.UserSocialRepository;
import com.gongu.server.global.exception.BusinessException;
import com.gongu.server.global.exception.errorcode.AuthErrorCode;
import com.gongu.server.global.security.Role;
import com.gongu.server.global.security.jwt.JwtProvider;
import com.gongu.server.global.security.jwt.RefreshTokenStore;
import io.jsonwebtoken.JwtException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class AuthServiceTest {

    @Mock
    private KakaoApiClient kakaoApiClient;

    @Mock
    private UserRepository userRepository;

    @Mock
    private UserSocialRepository userSocialRepository;

    @Mock
    private StoreAdminRepository storeAdminRepository;

    @Mock
    private PasswordEncoder passwordEncoder;

    @Mock
    private JwtProvider jwtProvider;

    @Mock
    private RefreshTokenStore refreshTokenStore;

    @InjectMocks
    private AuthService authService;

    @Test
    @DisplayName("storeAdminLogin_이메일_비밀번호_일치_TokenResponse_반환")
    void storeAdminLogin_이메일_비밀번호_일치_TokenResponse_반환() {
        // given
        StoreAdmin storeAdmin = createStoreAdmin(1L);
        StoreAdminLoginRequest request = new StoreAdminLoginRequest("admin@gongu.com", "password");

        given(storeAdminRepository.findByEmailAndIsActiveTrueAndDeletedAtIsNull("admin@gongu.com"))
                .willReturn(Optional.of(storeAdmin));
        given(passwordEncoder.matches("password", "encodedPassword")).willReturn(true);
        given(jwtProvider.generateAccessToken(1L, Role.STORE_ADMIN)).willReturn("access-token");
        given(jwtProvider.generateRefreshToken(1L, Role.STORE_ADMIN)).willReturn("refresh-token");

        // when
        TokenResponse response = authService.storeAdminLogin(request);

        // then
        assertThat(response.accessToken()).isEqualTo("access-token");
        assertThat(response.refreshToken()).isEqualTo("refresh-token");
        verify(refreshTokenStore).save(1L, Role.STORE_ADMIN, "refresh-token");
    }

    @Test
    @DisplayName("storeAdminLogin_존재하지_않는_이메일_INVALID_CREDENTIALS_예외")
    void storeAdminLogin_존재하지_않는_이메일_INVALID_CREDENTIALS_예외() {
        // given
        StoreAdminLoginRequest request = new StoreAdminLoginRequest("missing@gongu.com", "password");

        given(storeAdminRepository.findByEmailAndIsActiveTrueAndDeletedAtIsNull("missing@gongu.com"))
                .willReturn(Optional.empty());
        given(passwordEncoder.matches(eq("password"), any(String.class))).willReturn(false);

        // when & then
        assertThatThrownBy(() -> authService.storeAdminLogin(request))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode())
                        .isEqualTo(AuthErrorCode.INVALID_CREDENTIALS));
        verify(refreshTokenStore, never()).save(any(), any(), any());
    }

    @Test
    @DisplayName("storeAdminLogin_비밀번호_불일치_INVALID_CREDENTIALS_예외")
    void storeAdminLogin_비밀번호_불일치_INVALID_CREDENTIALS_예외() {
        // given
        StoreAdmin storeAdmin = createStoreAdmin(1L);
        StoreAdminLoginRequest request = new StoreAdminLoginRequest("admin@gongu.com", "wrongPassword");

        given(storeAdminRepository.findByEmailAndIsActiveTrueAndDeletedAtIsNull("admin@gongu.com"))
                .willReturn(Optional.of(storeAdmin));
        given(passwordEncoder.matches("wrongPassword", "encodedPassword")).willReturn(false);

        // when & then
        assertThatThrownBy(() -> authService.storeAdminLogin(request))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode())
                        .isEqualTo(AuthErrorCode.INVALID_CREDENTIALS));
        verify(refreshTokenStore, never()).save(any(), any(), any());
    }

    @Test
    @DisplayName("refreshToken_유효한_토큰_AccessTokenResponse_반환")
    void refreshToken_유효한_토큰_AccessTokenResponse_반환() {
        // given
        TokenRefreshRequest request = new TokenRefreshRequest("refresh-token");
        JwtProvider.RefreshTokenClaims claims = new JwtProvider.RefreshTokenClaims(1L, Role.MEMBER);

        given(jwtProvider.parseRefreshToken("refresh-token")).willReturn(claims);
        given(refreshTokenStore.get(1L, Role.MEMBER)).willReturn(Optional.of("refresh-token"));
        given(jwtProvider.generateAccessToken(1L, Role.MEMBER)).willReturn("new-access-token");

        // when
        AccessTokenResponse response = authService.refreshToken(request);

        // then
        assertThat(response.accessToken()).isEqualTo("new-access-token");
    }

    @Test
    @DisplayName("refreshToken_parseRefreshToken_JwtException_INVALID_REFRESH_TOKEN_예외")
    void refreshToken_parseRefreshToken_JwtException_INVALID_REFRESH_TOKEN_예외() {
        // given
        TokenRefreshRequest request = new TokenRefreshRequest("invalid-refresh-token");

        given(jwtProvider.parseRefreshToken("invalid-refresh-token"))
                .willThrow(new JwtException("invalid token"));

        // when & then
        assertThatThrownBy(() -> authService.refreshToken(request))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode())
                        .isEqualTo(AuthErrorCode.INVALID_REFRESH_TOKEN));
    }

    @Test
    @DisplayName("refreshToken_Redis에_토큰_없음_INVALID_REFRESH_TOKEN_예외")
    void refreshToken_Redis에_토큰_없음_INVALID_REFRESH_TOKEN_예외() {
        // given
        TokenRefreshRequest request = new TokenRefreshRequest("refresh-token");
        JwtProvider.RefreshTokenClaims claims = new JwtProvider.RefreshTokenClaims(1L, Role.MEMBER);

        given(jwtProvider.parseRefreshToken("refresh-token")).willReturn(claims);
        given(refreshTokenStore.get(1L, Role.MEMBER)).willReturn(Optional.empty());

        // when & then
        assertThatThrownBy(() -> authService.refreshToken(request))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode())
                        .isEqualTo(AuthErrorCode.INVALID_REFRESH_TOKEN));
    }

    @Test
    @DisplayName("refreshToken_Redis_토큰과_요청_토큰_불일치_INVALID_REFRESH_TOKEN_예외")
    void refreshToken_Redis_토큰과_요청_토큰_불일치_INVALID_REFRESH_TOKEN_예외() {
        // given
        TokenRefreshRequest request = new TokenRefreshRequest("refresh-token");
        JwtProvider.RefreshTokenClaims claims = new JwtProvider.RefreshTokenClaims(1L, Role.MEMBER);

        given(jwtProvider.parseRefreshToken("refresh-token")).willReturn(claims);
        given(refreshTokenStore.get(1L, Role.MEMBER)).willReturn(Optional.of("stored-refresh-token"));

        // when & then
        assertThatThrownBy(() -> authService.refreshToken(request))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode())
                        .isEqualTo(AuthErrorCode.INVALID_REFRESH_TOKEN));
    }

    @Test
    @DisplayName("kakaoLogin_기존_회원_TokenResponse_반환")
    void kakaoLogin_기존_회원_TokenResponse_반환() {
        // given
        KakaoUserInfo userInfo = createKakaoUserInfo(12345L, "홍길동");
        User user = createUser(1L, "홍길동");
        UserSocial userSocial = UserSocial.of(user, SocialProvider.KAKAO, "12345");

        given(kakaoApiClient.getUserInfo("kakao-access-token")).willReturn(userInfo);
        given(userSocialRepository.findByProviderAndSocialId(SocialProvider.KAKAO, "12345"))
                .willReturn(Optional.of(userSocial));
        given(jwtProvider.generateAccessToken(1L, Role.MEMBER)).willReturn("access-token");
        given(jwtProvider.generateRefreshToken(1L, Role.MEMBER)).willReturn("refresh-token");

        // when
        TokenResponse response = authService.kakaoLogin("kakao-access-token");

        // then
        assertThat(response.accessToken()).isEqualTo("access-token");
        assertThat(response.refreshToken()).isEqualTo("refresh-token");
        verify(refreshTokenStore).save(1L, Role.MEMBER, "refresh-token");
        verify(userRepository, never()).save(any(User.class));
        verify(userSocialRepository, never()).save(any(UserSocial.class));
    }

    @Test
    @DisplayName("kakaoLogin_신규_회원_User_UserSocial_저장_후_TokenResponse_반환")
    void kakaoLogin_신규_회원_User_UserSocial_저장_후_TokenResponse_반환() {
        // given
        KakaoUserInfo userInfo = createKakaoUserInfo(12345L, "홍길동");

        given(kakaoApiClient.getUserInfo("kakao-access-token")).willReturn(userInfo);
        given(userSocialRepository.findByProviderAndSocialId(SocialProvider.KAKAO, "12345"))
                .willReturn(Optional.empty());
        given(userRepository.save(any(User.class))).willAnswer(invocation -> {
            User user = invocation.getArgument(0);
            ReflectionTestUtils.setField(user, "id", 1L);
            return user;
        });
        given(userSocialRepository.save(any(UserSocial.class)))
                .willAnswer(invocation -> invocation.getArgument(0));
        given(jwtProvider.generateAccessToken(1L, Role.MEMBER)).willReturn("access-token");
        given(jwtProvider.generateRefreshToken(1L, Role.MEMBER)).willReturn("refresh-token");

        // when
        TokenResponse response = authService.kakaoLogin("kakao-access-token");

        // then
        assertThat(response.accessToken()).isEqualTo("access-token");
        assertThat(response.refreshToken()).isEqualTo("refresh-token");
        ArgumentCaptor<User> userCaptor = ArgumentCaptor.forClass(User.class);
        ArgumentCaptor<UserSocial> userSocialCaptor = ArgumentCaptor.forClass(UserSocial.class);
        verify(userRepository).save(userCaptor.capture());
        verify(userSocialRepository).save(userSocialCaptor.capture());
        assertThat(userCaptor.getValue().getName()).isEqualTo("홍길동");
        assertThat(userSocialCaptor.getValue().getProvider()).isEqualTo(SocialProvider.KAKAO);
        assertThat(userSocialCaptor.getValue().getSocialId()).isEqualTo("12345");
        verify(refreshTokenStore).save(1L, Role.MEMBER, "refresh-token");
    }

    private StoreAdmin createStoreAdmin(Long id) {
        Store store = Store.create("테스트매장", "서울시 강남구", "02-1234-5678");
        StoreAdmin storeAdmin = StoreAdmin.of(store, "admin@gongu.com", "encodedPassword", "관리자");
        ReflectionTestUtils.setField(storeAdmin, "id", id);
        return storeAdmin;
    }

    private User createUser(Long id, String name) {
        User user = User.of(name, "010-1234-5678");
        ReflectionTestUtils.setField(user, "id", id);
        return user;
    }

    private KakaoUserInfo createKakaoUserInfo(Long id, String nickname) {
        return new KakaoUserInfo(
                id,
                new KakaoUserInfo.KakaoAccount(
                        "user@gongu.com",
                        new KakaoUserInfo.Profile(nickname)
                )
        );
    }
}
