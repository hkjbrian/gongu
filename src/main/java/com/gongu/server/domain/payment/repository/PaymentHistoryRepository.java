package com.gongu.server.domain.payment.repository;

import com.gongu.server.domain.payment.domain.PaymentHistory;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface PaymentHistoryRepository extends JpaRepository<PaymentHistory, Long> {
    List<PaymentHistory> findByPaymentIdOrderByCreatedAtAsc(Long paymentId);
}
