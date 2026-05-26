package com.gongu.server.domain.order.controller;

import com.gongu.server.domain.order.dto.response.ArriveProductResponse;
import com.gongu.server.domain.order.dto.response.OrderSummaryResponse;
import com.gongu.server.domain.order.service.OrderService;
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
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/admin")
@RequiredArgsConstructor
public class AdminOrderController {

    private final OrderService orderService;

    @GetMapping("/products/{productId}/orders")
    @PreAuthorize("hasRole('STORE_ADMIN')")
    public ResponseEntity<ApiResponse<Page<OrderSummaryResponse>>> getOrdersByProduct(
            @PathVariable Long productId,
            @PageableDefault(size = 20) Pageable pageable,
            @AuthenticationPrincipal UserPrincipal userPrincipal) {
        Page<OrderSummaryResponse> result = orderService.getOrdersByProduct(userPrincipal.id(), productId, pageable);
        return ResponseEntity.ok(ApiResponse.success(result));
    }

    @GetMapping("/users/{userId}/orders")
    @PreAuthorize("hasRole('STORE_ADMIN')")
    public ResponseEntity<ApiResponse<Page<OrderSummaryResponse>>> getOrdersByUser(
            @PathVariable Long userId,
            @PageableDefault(size = 20) Pageable pageable,
            @AuthenticationPrincipal UserPrincipal userPrincipal) {
        Page<OrderSummaryResponse> result = orderService.getOrdersByUser(userPrincipal.id(), userId, pageable);
        return ResponseEntity.ok(ApiResponse.success(result));
    }

    @PutMapping("/products/{productId}/arrive")
    @PreAuthorize("hasRole('STORE_ADMIN')")
    public ResponseEntity<ApiResponse<ArriveProductResponse>> arriveOrder(
            @PathVariable Long productId,
            @AuthenticationPrincipal UserPrincipal userPrincipal) {
        ArriveProductResponse result = orderService.arriveOrder(userPrincipal.id(), productId);
        return ResponseEntity.ok(ApiResponse.success(result));
    }
}
