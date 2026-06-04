package com.gongu.server.domain.payment.repository;

import com.gongu.server.domain.order.entity.OrderStatus;
import com.gongu.server.domain.payment.domain.Payment;
import com.gongu.server.domain.payment.domain.PaymentStatus;
import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

public interface PaymentRepository extends JpaRepository<Payment, Long> {
    Optional<Payment> findByIdempotencyKey(String idempotencyKey);

    Optional<Payment> findByMerchantUid(String merchantUid);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT p FROM Payment p WHERE p.merchantUid = :merchantUid")
    Optional<Payment> findByMerchantUidWithLock(@Param("merchantUid") String merchantUid);

    @Query("SELECT p.id FROM Payment p WHERE p.status = :status AND p.order.status = :orderStatus AND p.order.createdAt < :threshold ORDER BY p.id")
    List<Long> findExpiredPendingPaymentIds(@Param("status") PaymentStatus status, @Param("orderStatus") OrderStatus orderStatus, @Param("threshold") LocalDateTime threshold, Pageable pageable);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT p FROM Payment p JOIN FETCH p.order WHERE p.id = :id")
    Optional<Payment> findByIdWithLock(@Param("id") Long id);

    boolean existsByOrderIdAndStatusIn(Long orderId, List<PaymentStatus> statuses);
}
