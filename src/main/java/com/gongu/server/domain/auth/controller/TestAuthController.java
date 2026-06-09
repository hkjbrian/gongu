package com.gongu.server.domain.auth.controller;

import com.gongu.server.domain.auth.dto.response.TokenResponse;
import com.gongu.server.global.common.ApiResponse;
import com.gongu.server.global.security.Role;
import com.gongu.server.global.security.jwt.JwtProvider;
import com.gongu.server.global.security.jwt.RefreshTokenStore;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Profile;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Profile("perf")
@RestController
@RequestMapping("/auth")
@RequiredArgsConstructor
public class TestAuthController {

    private final JwtProvider jwtProvider;
    private final RefreshTokenStore refreshTokenStore;

    private record TestLoginRequest(Long userId) {}

    @PostMapping("/test-login")
    public ResponseEntity<ApiResponse<TokenResponse>> testLogin(@RequestBody TestLoginRequest request) {
        String accessToken = jwtProvider.generateAccessToken(request.userId(), Role.USER);
        String refreshToken = jwtProvider.generateRefreshToken(request.userId(), Role.USER);
        refreshTokenStore.save(request.userId(), Role.USER, refreshToken);
        return ResponseEntity.ok(ApiResponse.success(new TokenResponse(accessToken, refreshToken)));
    }
}
