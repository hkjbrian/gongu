package com.gongu.server.domain.order.repository;

import com.gongu.server.domain.order.entity.Order;
import com.gongu.server.domain.order.entity.OrderItem;
import com.gongu.server.domain.order.entity.OrderStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface OrderItemRepository extends JpaRepository<OrderItem, Long> {

    List<OrderItem> findAllByOrder(Order order);

    @Query("SELECT oi FROM OrderItem oi JOIN FETCH oi.product WHERE oi.order IN :orders")
    List<OrderItem> findAllByOrderInWithProduct(@Param("orders") List<Order> orders);

    @Query("SELECT SUM(oi.quantity) FROM OrderItem oi WHERE oi.product.id = :productId AND oi.order.status = :status")
    Long sumQuantityByProductIdAndOrderStatus(@Param("productId") Long productId, @Param("status") OrderStatus status);
}
