package com.gongu.server.domain.order.repository;

import com.gongu.server.domain.order.entity.Order;
import com.gongu.server.domain.order.entity.OrderStatus;
import com.gongu.server.domain.product.entity.Product;
import com.gongu.server.domain.user.entity.User;
import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

public interface OrderRepository extends JpaRepository<Order, Long> {

    Optional<Order> findByIdAndUser(Long orderId, User user);

    Page<Order> findAllByUserOrderByCreatedAtDesc(User user, Pageable pageable);

    Page<Order> findAllByUserAndStatusOrderByCreatedAtDesc(User user, OrderStatus status, Pageable pageable);

    @Query(value = "SELECT o FROM Order o WHERE o.id IN (SELECT oi.order.id FROM OrderItem oi WHERE oi.product = :product) ORDER BY o.createdAt DESC",
           countQuery = "SELECT COUNT(o) FROM Order o WHERE o.id IN (SELECT oi.order.id FROM OrderItem oi WHERE oi.product = :product)")
    Page<Order> findAllByProduct(@Param("product") Product product, Pageable pageable);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT o FROM Order o WHERE o.id = :id")
    Optional<Order> findByIdWithLock(@Param("id") Long id);
}
