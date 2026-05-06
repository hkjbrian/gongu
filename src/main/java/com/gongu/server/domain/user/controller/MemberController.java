package com.gongu.server.domain.user.controller;

import com.gongu.server.domain.store.dto.request.RegisterMemberStoreRequest;
import com.gongu.server.domain.store.dto.response.RegisterMemberStoreResponse;
import com.gongu.server.domain.store.service.StoreService;
import com.gongu.server.global.common.ApiResponse;
import com.gongu.server.global.security.UserPrincipal;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/members")
@RequiredArgsConstructor
public class MemberController {

    private final StoreService storeService;

    @PostMapping("/me/stores")
    @PreAuthorize("hasRole('MEMBER')")
    public ResponseEntity<ApiResponse<RegisterMemberStoreResponse>> registerMemberStore(
            @AuthenticationPrincipal UserPrincipal principal,
            @RequestBody @Valid RegisterMemberStoreRequest request) {
        RegisterMemberStoreResponse response = storeService.registerMemberStore(principal.id(), request);
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.success(response));
    }
}
