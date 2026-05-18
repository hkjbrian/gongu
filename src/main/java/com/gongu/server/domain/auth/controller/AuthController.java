package com.gongu.server.domain.auth.controller;

import com.gongu.server.domain.auth.dto.request.KakaoLoginRequest;
import com.gongu.server.domain.auth.dto.request.StoreAdminLoginRequest;
import com.gongu.server.domain.auth.dto.request.TokenRefreshRequest;
import com.gongu.server.domain.auth.dto.response.AccessTokenResponse;
import com.gongu.server.domain.auth.dto.response.TokenResponse;
import com.gongu.server.domain.auth.service.AuthService;
import com.gongu.server.global.common.ApiResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/auth")
@RequiredArgsConstructor
public class AuthController {

    private final AuthService authService;

    @PostMapping("/oauth2/login")
    public ResponseEntity<ApiResponse<TokenResponse>> kakaoLogin(@Valid @RequestBody KakaoLoginRequest request) {
        return ResponseEntity.ok(ApiResponse.success(authService.kakaoLogin(request.accessToken())));
    }

    @PostMapping("/store-admin/login")
    public ResponseEntity<ApiResponse<TokenResponse>> storeAdminLogin(
            @Valid @RequestBody StoreAdminLoginRequest request) {
        return ResponseEntity.ok(ApiResponse.success(authService.storeAdminLogin(request)));
    }

    @PostMapping("/token/refresh")
    public ResponseEntity<ApiResponse<AccessTokenResponse>> tokenRefresh(
            @Valid @RequestBody TokenRefreshRequest request) {
        return ResponseEntity.ok(ApiResponse.success(authService.refreshToken(request)));
    }

}
