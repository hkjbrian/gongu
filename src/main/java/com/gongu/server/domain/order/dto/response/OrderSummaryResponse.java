package com.gongu.server.domain.order.dto.response;

import com.gongu.server.domain.order.entity.Order;
import com.gongu.server.domain.order.entity.OrderItem;
import com.gongu.server.domain.order.entity.OrderStatus;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;

import java.time.LocalDateTime;

@Getter
@Builder
@AllArgsConstructor(access = AccessLevel.PRIVATE)
public class OrderSummaryResponse {

    private Long orderId;
    private String productName;
    private Long quantity;
    private Long totalPrice;
    private OrderStatus status;
    private LocalDateTime createdAt;

    public static OrderSummaryResponse of(Order order, OrderItem item) {
        return OrderSummaryResponse.builder()
                .orderId(order.getId())
                .productName(item.getProduct().getName())
                .quantity(item.getQuantity())
                .totalPrice(order.getTotalPrice())
                .status(order.getStatus())
                .createdAt(order.getCreatedAt())
                .build();
    }
}
