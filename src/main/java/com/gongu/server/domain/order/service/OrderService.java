package com.gongu.server.domain.order.service;

import com.gongu.server.domain.order.dto.response.ArriveProductResponse;
import com.gongu.server.domain.order.dto.response.OrderDetailResponse;
import com.gongu.server.domain.order.dto.response.OrderSummaryResponse;
import com.gongu.server.domain.order.dto.response.ReceiveOrderResponse;
import com.gongu.server.domain.order.entity.Order;
import com.gongu.server.domain.order.entity.OrderItem;
import com.gongu.server.domain.order.entity.OrderStatus;
import com.gongu.server.domain.order.repository.OrderItemRepository;
import com.gongu.server.domain.order.repository.OrderRepository;
import com.gongu.server.domain.product.dto.ProductCacheDto;
import com.gongu.server.domain.product.entity.Product;
import com.gongu.server.domain.product.entity.ProductStatus;
import com.gongu.server.domain.product.repository.ProductRepository;
import com.gongu.server.domain.product.service.ProductCacheService;
import com.gongu.server.domain.product.service.StockRedisService;
import com.gongu.server.domain.store.entity.StoreAdmin;
import com.gongu.server.domain.store.repository.StoreAdminRepository;
import com.gongu.server.domain.store.repository.UserStoreRepository;
import com.gongu.server.domain.store.service.UserStoreCacheService;
import com.gongu.server.domain.user.entity.User;
import com.gongu.server.domain.user.repository.UserRepository;
import com.gongu.server.global.exception.BusinessException;
import com.gongu.server.global.exception.errorcode.OrderErrorCode;
import com.gongu.server.global.exception.errorcode.ProductErrorCode;
import com.gongu.server.global.exception.errorcode.StoreErrorCode;
import com.gongu.server.global.exception.errorcode.UserErrorCode;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Timer;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
@Slf4j
public class OrderService {

    private final UserRepository userRepository;
    private final ProductRepository productRepository;
    private final OrderRepository orderRepository;
    private final OrderItemRepository orderItemRepository;
    private final StoreAdminRepository storeAdminRepository;
    private final UserStoreRepository userStoreRepository;
    private final StockRedisService stockRedisService;
    private final ProductCacheService productCacheService;
    private final UserStoreCacheService userStoreCacheService;
    @Qualifier("orderCreatedCounter")
    private final Counter orderCreatedCounter;
    @Qualifier("lockWaitOrderTimer")
    private final Timer lockWaitOrderTimer;

    @Transactional
    public OrderDetailResponse createOrder(Long userId, Long productId, int quantity) {
        User user = userRepository.findByIdAndDeletedAtIsNull(userId)
                .orElseThrow(() -> new BusinessException(UserErrorCode.USER_NOT_FOUND));

        ProductCacheDto product = productCacheService.getProduct(productId);

        if (!userStoreCacheService.existsByUserAndStore(userId, product.storeId())) {
            throw new BusinessException(ProductErrorCode.PRODUCT_NOT_FOUND);
        }

        if (product.status() != ProductStatus.ACTIVE) {
            throw new BusinessException(ProductErrorCode.INVALID_PRODUCT_STATUS);
        }
        if (quantity <= 0) {
            throw new BusinessException(ProductErrorCode.INVALID_PRODUCT_DATA);
        }
        stockRedisService.reserveStock(productId, quantity);

        try {
            long totalPrice = product.price() * quantity;

            Order order = Order.create(user, totalPrice);
            orderRepository.save(order);

            Product productReference = productRepository.getReferenceById(productId);
            OrderItem item = OrderItem.create(order, productReference, Long.valueOf(quantity));
            orderItemRepository.save(item);

            orderCreatedCounter.increment();
            return OrderDetailResponse.of(order, List.of(item));
        } catch (Exception e) {
            log.error("createOrder 보상 실행: productId={}, quantity={}", productId, quantity, e);
            stockRedisService.releaseStock(productId, quantity);
            throw e;
        }
    }

    @Transactional
    public void cancelOrder(Long userId, Long orderId, String reason) {
        User user = userRepository.findByIdAndDeletedAtIsNull(userId)
                .orElseThrow(() -> new BusinessException(UserErrorCode.USER_NOT_FOUND));

        Order order = lockWaitOrderTimer
                .record(() -> orderRepository.findByIdWithLock(orderId))
                .orElseThrow(() -> new BusinessException(OrderErrorCode.ORDER_NOT_FOUND));
        if (!order.getUser().getId().equals(user.getId())) {
            throw new BusinessException(OrderErrorCode.ORDER_NOT_FOUND);
        }

        order.cancel(reason);

        List<OrderItem> items = orderItemRepository.findAllByOrder(order);
        items.forEach(item ->
                stockRedisService.releaseStock(item.getProduct().getId(), Math.toIntExact(item.getQuantity()))
        );
    }

    @Transactional
    public ArriveProductResponse arriveOrder(Long storeAdminId, Long productId) {
        StoreAdmin storeAdmin = storeAdminRepository.findByIdAndDeletedAtIsNull(storeAdminId)
                .orElseThrow(() -> new BusinessException(StoreErrorCode.STORE_ADMIN_NOT_FOUND));

        Product product = productRepository.findByIdAndStore(productId, storeAdmin.getStore())
                .orElseThrow(() -> new BusinessException(ProductErrorCode.PRODUCT_NOT_FOUND));

        int paidCount = orderRepository.countByProductAndStatus(product, OrderStatus.PAID);
        if (paidCount == 0) {
            int arrivedCount = orderRepository.countByProductAndStatus(product, OrderStatus.ARRIVED);
            if (arrivedCount > 0) {
                throw new BusinessException(OrderErrorCode.ARRIVE_NOT_ALLOWED);
            }
            return ArriveProductResponse.of(productId, 0);
        }

        int updated = orderRepository.bulkUpdateStatusByProduct(product, OrderStatus.PAID, OrderStatus.ARRIVED);
        return ArriveProductResponse.of(productId, updated);
    }

    @Transactional
    public ReceiveOrderResponse receiveOrder(Long userId, Long orderId) {
        User user = userRepository.findByIdAndDeletedAtIsNull(userId)
                .orElseThrow(() -> new BusinessException(UserErrorCode.USER_NOT_FOUND));

        Order order = lockWaitOrderTimer
                .record(() -> orderRepository.findByIdWithLock(orderId))
                .orElseThrow(() -> new BusinessException(OrderErrorCode.ORDER_NOT_FOUND));

        if (!order.getUser().getId().equals(user.getId())) {
            throw new BusinessException(OrderErrorCode.ORDER_NOT_FOUND);
        }

        order.receive();
        return ReceiveOrderResponse.of(order);
    }

    public Page<OrderSummaryResponse> getMyOrders(Long userId, OrderStatus status, Pageable pageable) {
        User user = userRepository.findByIdAndDeletedAtIsNull(userId)
                .orElseThrow(() -> new BusinessException(UserErrorCode.USER_NOT_FOUND));

        Page<Order> orders;
        if (status == null) {
            orders = orderRepository.findAllByUserOrderByCreatedAtDesc(user, pageable);
        } else {
            orders = orderRepository.findAllByUserAndStatusOrderByCreatedAtDesc(user, status, pageable);
        }

        return toSummaryPage(orders);
    }

    public OrderDetailResponse getOrder(Long userId, Long orderId) {
        User user = userRepository.findByIdAndDeletedAtIsNull(userId)
                .orElseThrow(() -> new BusinessException(UserErrorCode.USER_NOT_FOUND));

        Order order = orderRepository.findByIdAndUser(orderId, user)
                .orElseThrow(() -> new BusinessException(OrderErrorCode.ORDER_NOT_FOUND));

        List<OrderItem> items = orderItemRepository.findAllByOrder(order);
        return OrderDetailResponse.of(order, items);
    }

    public Page<OrderSummaryResponse> getOrdersByProduct(Long storeAdminId, Long productId, Pageable pageable) {
        StoreAdmin storeAdmin = storeAdminRepository.findByIdAndDeletedAtIsNull(storeAdminId)
                .orElseThrow(() -> new BusinessException(StoreErrorCode.STORE_ADMIN_NOT_FOUND));

        Product product = productRepository.findByIdAndStore(productId, storeAdmin.getStore())
                .orElseThrow(() -> new BusinessException(ProductErrorCode.PRODUCT_NOT_FOUND));

        Page<Order> orders = orderRepository.findAllByProduct(product, pageable);
        return toSummaryPage(orders);
    }

    public Page<OrderSummaryResponse> getOrdersByUser(Long storeAdminId, Long userId, Pageable pageable) {
        StoreAdmin storeAdmin = storeAdminRepository.findByIdAndDeletedAtIsNull(storeAdminId)
                .orElseThrow(() -> new BusinessException(StoreErrorCode.STORE_ADMIN_NOT_FOUND));

        User user = userRepository.findByIdAndDeletedAtIsNull(userId)
                .orElseThrow(() -> new BusinessException(UserErrorCode.USER_NOT_FOUND));

        Page<Order> orders = orderRepository.findAllByUserAndStore(user, storeAdmin.getStore(), pageable);
        return toSummaryPage(orders);
    }

    private Page<OrderSummaryResponse> toSummaryPage(Page<Order> orders) {
        if (orders.isEmpty()) {
            return Page.empty(orders.getPageable());
        }
        List<OrderItem> items =
                orderItemRepository.findAllByOrderInWithProduct(orders.getContent());
        Map<Long, OrderItem> itemByOrderId = items.stream()
                .collect(Collectors.toMap(
                        item -> item.getOrder().getId(),
                        item -> item,
                        (a, b) -> a  // 주문당 OrderItem 1건 보장, 혹시 중복 시 첫 번째 사용
                ));
        return orders.map(order -> {
            OrderItem item = itemByOrderId.get(order.getId());
            if (item == null) {
                throw new BusinessException(OrderErrorCode.ORDER_ITEM_NOT_FOUND);
            }
            return OrderSummaryResponse.of(order, item);
        });
    }
}
