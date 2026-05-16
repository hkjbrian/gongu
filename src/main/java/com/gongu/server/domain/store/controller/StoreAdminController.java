package com.gongu.server.domain.store.controller;

import com.gongu.server.domain.store.dto.response.AdminUserResponse;
import com.gongu.server.domain.store.service.StoreAdminService;
import com.gongu.server.global.common.ApiResponse;
import com.gongu.server.global.security.UserPrincipal;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/admin")
@RequiredArgsConstructor
public class StoreAdminController {

    private final StoreAdminService storeAdminService;

    @GetMapping("/members")
    @PreAuthorize("hasRole('STORE_ADMIN')")
    public ResponseEntity<ApiResponse<Page<AdminUserResponse>>> getUsers(
            @AuthenticationPrincipal UserPrincipal userPrincipal,
            @RequestParam(required = false) String name,
            @PageableDefault(size = 20) Pageable pageable) {
        Page<AdminUserResponse> result = storeAdminService.getUsers(userPrincipal.id(), name, pageable);
        return ResponseEntity.ok(ApiResponse.success(result));
    }
}
