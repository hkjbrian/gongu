package com.gongu.server.domain.payment.repository;

import com.gongu.server.domain.payment.domain.PaymentHistory;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface PaymentHistoryRepository extends JpaRepository<PaymentHistory, Long> {

    // created_at은 datetime(초 단위)이라 같은 초 안에 두 전이가 기록되면 정렬이 불안정하다 —
    // id를 2차 정렬 기준으로 둬 append-only 삽입 순서를 보장한다.
    // h.payment.id 비교는 FK 컬럼만 참조하므로 Hibernate가 payments 조인 없이 처리한다
    // (파생 쿼리 메서드 findByPaymentId(...)는 실제로 payments를 조인하는 것으로 확인됨).
    @Query("SELECT h FROM PaymentHistory h WHERE h.payment.id = :paymentId ORDER BY h.createdAt ASC, h.id ASC")
    List<PaymentHistory> findByPaymentIdOrderByCreatedAtAscIdAsc(@Param("paymentId") Long paymentId);
}
