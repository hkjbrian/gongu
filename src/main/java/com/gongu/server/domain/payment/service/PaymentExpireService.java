package com.gongu.server.domain.payment.service;

import com.gongu.server.domain.order.entity.Order;
import com.gongu.server.domain.order.entity.OrderItem;
import com.gongu.server.domain.order.entity.OrderStatus;
import com.gongu.server.domain.order.repository.OrderItemRepository;
import com.gongu.server.domain.order.repository.OrderRepository;
import com.gongu.server.domain.payment.domain.Payment;
import com.gongu.server.domain.payment.domain.PaymentStatus;
import com.gongu.server.domain.payment.repository.PaymentRepository;
import com.gongu.server.domain.product.service.StockRedisService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

@Service
@RequiredArgsConstructor
public class PaymentExpireService {

    private final PaymentRepository paymentRepository;
    private final OrderRepository orderRepository;
    private final OrderItemRepository orderItemRepository;
    private final StockRedisService stockRedisService;

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void cancelExpiredPayment(Long paymentId, LocalDateTime threshold) {
        Optional<Payment> optionalPayment = paymentRepository.findByIdWithLock(paymentId);
        if (optionalPayment.isEmpty()) {
            return;
        }

        Payment payment = optionalPayment.get();

        if (payment.getStatus() != PaymentStatus.PENDING) {
            return;
        }

        Optional<Order> optionalOrder = orderRepository.findByIdWithLock(payment.getOrder().getId());
        if (optionalOrder.isEmpty()) {
            return;
        }

        Order order = optionalOrder.get();

        if (order.getStatus() != OrderStatus.RESERVED) {
            return;
        }

        if (!order.getCreatedAt().isBefore(threshold)) {
            return;
        }

        // 만료 시점의 주문은 RESERVED·결제는 PENDING이므로 MySQL remainingStock은 차감된 적이 없다.
        // (차감은 completePayment -> Product.confirmStock()에서만 발생)
        // Redis 예약 재고만 되돌린다. OrderExpireService와 동일한 형태.
        List<OrderItem> items = orderItemRepository.findAllByOrder(order);

        payment.expire();
        order.cancel("결제 시간 초과");
        items.forEach(item ->
                stockRedisService.releaseStock(item.getProduct().getId(), Math.toIntExact(item.getQuantity()))
        );
    }
}
