package com.gongu.server.domain.order.service;

import com.gongu.server.domain.order.entity.Order;
import com.gongu.server.domain.order.entity.OrderItem;
import com.gongu.server.domain.order.entity.OrderStatus;
import com.gongu.server.domain.order.repository.OrderItemRepository;
import com.gongu.server.domain.order.repository.OrderRepository;
import com.gongu.server.domain.payment.domain.PaymentStatus;
import com.gongu.server.domain.payment.repository.PaymentRepository;
import com.gongu.server.domain.product.entity.Product;
import com.gongu.server.domain.product.repository.ProductRepository;
import com.gongu.server.global.exception.BusinessException;
import com.gongu.server.global.exception.errorcode.ProductErrorCode;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

@Service
@RequiredArgsConstructor
public class OrderExpireService {

    private final OrderRepository orderRepository;
    private final OrderItemRepository orderItemRepository;
    private final ProductRepository productRepository;
    private final PaymentRepository paymentRepository;
    private final MeterRegistry meterRegistry;

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void cancelExpiredOrder(Long orderId, LocalDateTime threshold) {
        Optional<Order> optionalOrder = Timer.builder("gongu.db.lock.wait")
                .tag("entity", "order")
                .register(meterRegistry)
                .record(() -> orderRepository.findByIdWithLock(orderId));
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

        if (paymentRepository.existsByOrderIdAndStatusIn(orderId, List.of(PaymentStatus.PENDING))) {
            return;
        }

        List<OrderItem> items = orderItemRepository.findAllByOrder(order);
        items.stream()
                .sorted(Comparator.comparingLong(item -> item.getProduct().getId()))
                .forEach(item -> {
                    Product product = Timer.builder("gongu.db.lock.wait")
                            .tag("entity", "product")
                            .register(meterRegistry)
                            .record(() -> productRepository.findByIdWithLock(item.getProduct().getId()))
                            .orElseThrow(() -> new BusinessException(ProductErrorCode.PRODUCT_NOT_FOUND));
                    product.restoreStock(Math.toIntExact(item.getQuantity()));
                });

        order.cancel("결제 시간 초과");
    }
}
