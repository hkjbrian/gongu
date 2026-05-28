package com.gongu.server.domain.payment.service;

import com.gongu.server.domain.order.entity.Order;
import com.gongu.server.domain.payment.domain.Payment;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

@Component
@RequiredArgsConstructor
public class PaymentResultCommitter {

    // 금액 일치 확정
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void commitConfirm(Payment payment, Order order, Long portOneAmount, LocalDateTime paidAt) {
        payment.confirm(portOneAmount, paidAt);
        order.pay();
    }

    // 금액 불일치 보상 취소
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void commitMismatchCancel(Payment payment, Order order) {
        payment.cancelByMismatch();
        order.cancel("결제 금액 불일치");
    }

    // PG 조회 실패 처리
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void commitFail(Payment payment) {
        payment.fail();
    }
}
