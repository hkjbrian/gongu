package com.gongu.server.domain.product.controller;

import com.gongu.server.domain.product.dto.CreateProductRequest;
import com.gongu.server.domain.product.dto.ProductDetailResponse;
import com.gongu.server.domain.product.dto.ProductSummaryResponse;
import com.gongu.server.domain.product.dto.UpdateProductRequest;
import com.gongu.server.domain.product.service.ProductService;
import com.gongu.server.global.common.ApiResponse;
import com.gongu.server.global.security.UserPrincipal;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
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

    @PostMapping
    @PreAuthorize("hasRole('STORE_ADMIN')")
    public ResponseEntity<ApiResponse<ProductDetailResponse>> createProduct(
            @AuthenticationPrincipal UserPrincipal userPrincipal,
            @Valid @RequestBody CreateProductRequest request) {
        ProductDetailResponse result = productService.createProduct(userPrincipal.id(), request);
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.success(result));
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasRole('STORE_ADMIN')")
    public ResponseEntity<ApiResponse<ProductDetailResponse>> updateProduct(
            @AuthenticationPrincipal UserPrincipal userPrincipal,
            @PathVariable Long id,
            @Valid @RequestBody UpdateProductRequest request) {
        ProductDetailResponse result = productService.updateProduct(userPrincipal.id(), id, request);
        return ResponseEntity.ok(ApiResponse.success(result));
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("hasRole('STORE_ADMIN')")
    public ResponseEntity<Void> closeProduct(
            @AuthenticationPrincipal UserPrincipal userPrincipal,
            @PathVariable Long id) {
        productService.closeProduct(userPrincipal.id(), id);
        return ResponseEntity.noContent().build();
    }
}
