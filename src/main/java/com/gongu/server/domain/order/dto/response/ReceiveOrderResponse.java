package com.gongu.server.domain.order.dto.response;

import com.gongu.server.domain.order.entity.Order;
import com.gongu.server.domain.order.entity.OrderStatus;

import java.time.LocalDateTime;

public record ReceiveOrderResponse(Long orderId, OrderStatus status, LocalDateTime receivedAt) {

    public static ReceiveOrderResponse of(Order order) {
        return new ReceiveOrderResponse(order.getId(), order.getStatus(), order.getReceivedAt());
    }
}
