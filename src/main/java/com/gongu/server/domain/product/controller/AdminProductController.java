package com.gongu.server.domain.product.controller;

import com.gongu.server.domain.product.dto.ProductDetailResponse;
import com.gongu.server.domain.product.dto.ProductSummaryResponse;
import com.gongu.server.domain.product.service.ProductService;
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
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/admin/products")
@RequiredArgsConstructor
public class AdminProductController {

    private final ProductService productService;

    @GetMapping
    @PreAuthorize("hasRole('STORE_ADMIN')")
    public ResponseEntity<ApiResponse<Page<ProductSummaryResponse>>> getAdminProducts(
            @AuthenticationPrincipal UserPrincipal userPrincipal,
            @PageableDefault(size = 20) Pageable pageable) {
        Page<ProductSummaryResponse> result = productService.getAdminProducts(userPrincipal.id(), pageable);
        return ResponseEntity.ok(ApiResponse.success(result));
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasRole('STORE_ADMIN')")
    public ResponseEntity<ApiResponse<ProductDetailResponse>> getAdminProduct(
            @AuthenticationPrincipal UserPrincipal userPrincipal,
            @PathVariable Long id) {
        ProductDetailResponse result = productService.getAdminProduct(userPrincipal.id(), id);
        return ResponseEntity.ok(ApiResponse.success(result));
    }
}
