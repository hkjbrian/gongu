package com.gongu.server.domain.order.dto.response;

import com.gongu.server.domain.order.entity.Order;
import com.gongu.server.domain.order.entity.OrderItem;
import com.gongu.server.domain.order.entity.OrderStatus;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;

import java.time.LocalDateTime;
import java.util.List;

@Getter
@Builder
@AllArgsConstructor(access = AccessLevel.PRIVATE)
public class OrderDetailResponse {

    private Long orderId;
    private OrderStatus status;
    private Long totalPrice;
    private LocalDateTime cancelledAt;
    private String cancelReason;
    private LocalDateTime createdAt;
    private List<OrderItemResponse> items;

    public static OrderDetailResponse of(Order order, List<OrderItem> items) {
        return OrderDetailResponse.builder()
                .orderId(order.getId())
                .status(order.getStatus())
                .totalPrice(order.getTotalPrice())
                .cancelledAt(order.getCancelledAt())
                .cancelReason(order.getCancelReason())
                .createdAt(order.getCreatedAt())
                .items(items.stream()
                        .map(OrderItemResponse::of)
                        .toList())
                .build();
    }
}
