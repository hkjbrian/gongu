package com.gongu.server.domain.auth.controller;

import com.gongu.server.domain.auth.dto.request.KakaoLoginRequest;
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
        TokenResponse tokenResponse = authService.kakaoLogin(request.accessToken());
        return ResponseEntity.ok(ApiResponse.success(tokenResponse));
    }
}
