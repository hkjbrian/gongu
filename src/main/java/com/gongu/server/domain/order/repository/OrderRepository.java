package com.gongu.server.domain.order.repository;

import com.gongu.server.domain.order.entity.Order;
import com.gongu.server.domain.order.entity.OrderStatus;
import com.gongu.server.domain.product.entity.Product;
import com.gongu.server.domain.store.entity.Store;
import com.gongu.server.domain.user.entity.User;
import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

public interface OrderRepository extends JpaRepository<Order, Long> {

    Optional<Order> findByIdAndUser(Long orderId, User user);

    Page<Order> findAllByUserOrderByCreatedAtDesc(User user, Pageable pageable);

    Page<Order> findAllByUserAndStatusOrderByCreatedAtDesc(User user, OrderStatus status, Pageable pageable);

    @Query(value = "SELECT o FROM Order o WHERE o.id IN (SELECT oi.order.id FROM OrderItem oi WHERE oi.product = :product) ORDER BY o.createdAt DESC",
           countQuery = "SELECT COUNT(o) FROM Order o WHERE o.id IN (SELECT oi.order.id FROM OrderItem oi WHERE oi.product = :product)")
    Page<Order> findAllByProduct(@Param("product") Product product, Pageable pageable);

    @Query("SELECT o FROM Order o WHERE o.id IN (SELECT oi.order.id FROM OrderItem oi WHERE oi.product = :product) AND o.status = :status")
    List<Order> findAllByProductAndStatus(@Param("product") Product product, @Param("status") OrderStatus status);

    @Modifying(clearAutomatically = true)
    @Query("UPDATE Order o SET o.status = :toStatus WHERE o.id IN (SELECT oi.order.id FROM OrderItem oi WHERE oi.product = :product) AND o.status = :fromStatus")
    int bulkUpdateStatusByProduct(@Param("product") Product product, @Param("fromStatus") OrderStatus fromStatus, @Param("toStatus") OrderStatus toStatus);

    @Query("SELECT COUNT(o) FROM Order o WHERE o.id IN (SELECT oi.order.id FROM OrderItem oi WHERE oi.product = :product) AND o.status = :status")
    int countByProductAndStatus(@Param("product") Product product, @Param("status") OrderStatus status);

    @Query(value = "SELECT o FROM Order o WHERE o.user = :user AND o.id IN (SELECT oi.order.id FROM OrderItem oi WHERE oi.product.store = :store) ORDER BY o.createdAt DESC",
           countQuery = "SELECT COUNT(o) FROM Order o WHERE o.user = :user AND o.id IN (SELECT oi.order.id FROM OrderItem oi WHERE oi.product.store = :store)")
    Page<Order> findAllByUserAndStore(@Param("user") User user, @Param("store") Store store, Pageable pageable);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT o FROM Order o JOIN FETCH o.user WHERE o.id = :id")
    Optional<Order> findByIdWithLock(@Param("id") Long id);

    @Query("SELECT o.id FROM Order o WHERE o.status = :status AND o.createdAt < :threshold AND NOT EXISTS (SELECT 1 FROM Payment p WHERE p.order = o AND p.status = 'PENDING') ORDER BY o.id")
    List<Long> findExpiredReservedOrderIds(@Param("status") OrderStatus status, @Param("threshold") LocalDateTime threshold, Pageable pageable);
}
