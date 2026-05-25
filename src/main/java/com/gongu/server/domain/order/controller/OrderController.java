package com.gongu.server.domain.order.controller;

import com.gongu.server.domain.order.dto.request.CancelOrderRequest;
import com.gongu.server.domain.order.dto.request.CreateOrderRequest;
import com.gongu.server.domain.order.dto.response.OrderDetailResponse;
import com.gongu.server.domain.order.dto.response.OrderSummaryResponse;
import com.gongu.server.domain.order.entity.OrderStatus;
import com.gongu.server.domain.order.service.OrderService;
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
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/orders")
@RequiredArgsConstructor
public class OrderController {

    private final OrderService orderService;

    @PostMapping
    @PreAuthorize("hasRole('USER')")
    public ResponseEntity<ApiResponse<OrderDetailResponse>> createOrder(
            @Valid @RequestBody CreateOrderRequest request,
            @AuthenticationPrincipal UserPrincipal userPrincipal) {
        OrderDetailResponse result = orderService.createOrder(userPrincipal.id(), request.productId(), request.quantity());
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.success(result));
    }

    @GetMapping("/me")
    @PreAuthorize("hasRole('USER')")
    public ResponseEntity<ApiResponse<Page<OrderSummaryResponse>>> getMyOrders(
            @RequestParam(required = false) OrderStatus status,
            @PageableDefault(size = 20) Pageable pageable,
            @AuthenticationPrincipal UserPrincipal userPrincipal) {
        Page<OrderSummaryResponse> result = orderService.getMyOrders(userPrincipal.id(), status, pageable);
        return ResponseEntity.ok(ApiResponse.success(result));
    }

    @GetMapping("/{orderId}")
    @PreAuthorize("hasRole('USER')")
    public ResponseEntity<ApiResponse<OrderDetailResponse>> getOrder(
            @PathVariable Long orderId,
            @AuthenticationPrincipal UserPrincipal userPrincipal) {
        OrderDetailResponse result = orderService.getOrder(userPrincipal.id(), orderId);
        return ResponseEntity.ok(ApiResponse.success(result));
    }

    @PostMapping("/{orderId}/cancel")
    @PreAuthorize("hasRole('USER')")
    public ResponseEntity<Void> cancelOrder(
            @PathVariable Long orderId,
            @Valid @RequestBody CancelOrderRequest request,
            @AuthenticationPrincipal UserPrincipal userPrincipal) {
        orderService.cancelOrder(userPrincipal.id(), orderId, request.reason());
        return ResponseEntity.noContent().build();
    }

    @PatchMapping("/{orderId}/receive")
    @PreAuthorize("hasRole('USER')")
    public ResponseEntity<Void> receiveOrder(
            @PathVariable Long orderId,
            @AuthenticationPrincipal UserPrincipal userPrincipal) {
        orderService.receiveOrder(userPrincipal.id(), orderId);
        return ResponseEntity.noContent().build();
    }
}
