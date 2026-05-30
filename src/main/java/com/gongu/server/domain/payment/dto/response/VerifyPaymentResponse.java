package com.gongu.server.domain.payment.dto.response;

import com.gongu.server.domain.order.entity.Order;
import com.gongu.server.domain.order.entity.OrderStatus;
import com.gongu.server.domain.payment.domain.Payment;
import com.gongu.server.domain.payment.domain.PaymentStatus;

import java.time.LocalDateTime;

public record VerifyPaymentResponse(
        Long orderId,
        String paymentId,
        Long amount,
        PaymentStatus status,
        LocalDateTime paidAt,
        OrderStatus orderStatus
) {
    public static VerifyPaymentResponse of(Order order, Payment payment) {
        return new VerifyPaymentResponse(
                order.getId(),
                payment.getMerchantUid(),
                payment.getAmount(),
                payment.getStatus(),
                payment.getPaidAt(),
                order.getStatus()
        );
    }
}
