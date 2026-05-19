package com.gongu.server.domain.order.service;

import com.gongu.server.domain.order.dto.response.OrderDetailResponse;
import com.gongu.server.domain.order.entity.Order;
import com.gongu.server.domain.order.entity.OrderItem;
import com.gongu.server.domain.order.repository.OrderItemRepository;
import com.gongu.server.domain.order.repository.OrderRepository;
import com.gongu.server.domain.product.entity.Product;
import com.gongu.server.domain.product.repository.ProductRepository;
import com.gongu.server.domain.user.entity.User;
import com.gongu.server.domain.user.repository.UserRepository;
import com.gongu.server.global.exception.BusinessException;
import com.gongu.server.global.exception.errorcode.OrderErrorCode;
import com.gongu.server.global.exception.errorcode.ProductErrorCode;
import com.gongu.server.global.exception.errorcode.UserErrorCode;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Comparator;
import java.util.List;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class OrderService {

    private final UserRepository userRepository;
    private final ProductRepository productRepository;
    private final OrderRepository orderRepository;
    private final OrderItemRepository orderItemRepository;

    @Transactional
    public OrderDetailResponse createOrder(Long userId, Long productId, int quantity) {
        User user = userRepository.findByIdAndDeletedAtIsNull(userId)
                .orElseThrow(() -> new BusinessException(UserErrorCode.USER_NOT_FOUND));

        Product product = productRepository.findByIdWithLock(productId)
                .orElseThrow(() -> new BusinessException(ProductErrorCode.PRODUCT_NOT_FOUND));

        product.decreaseStock(quantity);
        long totalPrice = (long) product.getPrice() * quantity;

        Order order = Order.create(user, totalPrice);
        orderRepository.save(order);

        OrderItem item = OrderItem.create(order, product, Long.valueOf(quantity));
        orderItemRepository.save(item);

        return OrderDetailResponse.of(order, List.of(item));
    }

    @Transactional
    public void cancelOrder(Long userId, Long orderId, String reason) {
        User user = userRepository.findByIdAndDeletedAtIsNull(userId)
                .orElseThrow(() -> new BusinessException(UserErrorCode.USER_NOT_FOUND));

        Order order = orderRepository.findByIdAndUser(orderId, user)
                .orElseThrow(() -> new BusinessException(OrderErrorCode.ORDER_NOT_FOUND));

        List<OrderItem> items = orderItemRepository.findAllByOrder(order);
        items.stream()
                .sorted(Comparator.comparingLong(item -> item.getProduct().getId()))
                .forEach(item -> {
                    Product product = productRepository.findByIdWithLock(item.getProduct().getId())
                            .orElseThrow(() -> new BusinessException(ProductErrorCode.PRODUCT_NOT_FOUND));
                    product.restoreStock(Math.toIntExact(item.getQuantity()));
                });

        order.cancel(reason);
    }
}
