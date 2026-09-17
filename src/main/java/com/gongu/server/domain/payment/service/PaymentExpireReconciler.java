package com.gongu.server.domain.payment.service;

import com.gongu.server.domain.order.entity.Order;
import com.gongu.server.domain.order.entity.OrderItem;
import com.gongu.server.domain.order.entity.OrderStatus;
import com.gongu.server.domain.order.repository.OrderItemRepository;
import com.gongu.server.domain.order.repository.OrderRepository;
import com.gongu.server.domain.payment.domain.PaymentHistoryTrigger;
import com.gongu.server.domain.payment.domain.PaymentStatus;
import com.gongu.server.domain.payment.repository.PaymentRepository;
import com.gongu.server.domain.product.service.StockRedisService;
import io.micrometer.core.instrument.Counter;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;

/**
 * PaymentExpireService.reconcileExpiredPayment이 위임하는 짧은 트랜잭션 조정 액션.
 * 별도 빈으로 분리한 이유: 같은 클래스 안에서 this.메서드()로 호출하면
 * Spring 프록시 기반 @Transactional이 self-invocation에 의해 무시되기 때문이다.
 */
@Component
@RequiredArgsConstructor
public class PaymentExpireReconciler {

    private final PaymentRepository paymentRepository;
    private final OrderRepository orderRepository;
    private final OrderItemRepository orderItemRepository;
    private final StockRedisService stockRedisService;
    private final PaymentHistoryRecorder paymentHistoryRecorder;
    @Qualifier("paymentExpiryReconcileExhaustedCounter")
    private final Counter paymentExpiryReconcileExhaustedCounter;

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void cancelOrderAfterPgConfirmedUnpaid(Long orderId) {
        Optional<Order> optionalOrder = orderRepository.findByIdWithLock(orderId);
        if (optionalOrder.isEmpty()) {
            return;
        }
        Order order = optionalOrder.get();
        if (order.getStatus() != OrderStatus.RESERVED) {
            return;
        }

        List<OrderItem> items = orderItemRepository.findAllByOrder(order);
        order.cancel("PG 미결제 확인 - 만료 취소");

        items.forEach(item ->
                stockRedisService.releaseStockAfterCommit(item.getProduct().getId(), Math.toIntExact(item.getQuantity()))
        );
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void recordInconclusiveAttempt(Long paymentId, int maxAttempts) {
        paymentRepository.findById(paymentId).ifPresent(payment -> {
            if (payment.getStatus() != PaymentStatus.PENDING) {
                return;
            }
            int attempts = payment.incrementExpiryCheckAttempts();
            if (attempts >= maxAttempts) {
                paymentExpiryReconcileExhaustedCounter.increment();
                paymentHistoryRecorder.record(payment, payment.getStatus(), payment.getStatus(),
                        PaymentHistoryTrigger.EXPIRY_SCHEDULER,
                        "PG 조회 한도 초과(" + attempts + "회) - 운영자 확인 필요", null);
            }
        });
    }

    /**
     * PortOneClient.getPayment가 4xx를 PAYMENT_NOT_FOUND로 뭉뚱그리는 것(PG가 결제를 전혀 모르는
     * 진짜 404와 자격증명 오류 401/403을 구분하지 못함, 이번 수정 범위 밖)과 PG의 빈/파싱 불가 응답
     * (PAYMENT_PG_UNAVAILABLE) 모두를 "결제 안 됨으로 확정"으로 처리한다.
     * Payment와 Order를 한 트랜잭션 안에서 함께 정리한다 — Order만 정리하면 CANCELLED Order에
     * PENDING Payment가 매달린 채 남는 고아 상태(ADR-007이 막으려는 것)가 생기기 때문이다.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void settleUnconfirmedPayment(Long paymentId, Long orderId) {
        paymentRepository.findById(paymentId).ifPresent(payment -> {
            if (payment.getStatus() == PaymentStatus.PENDING) {
                PaymentStatus fromStatus = payment.getStatus();
                payment.expire();
                paymentHistoryRecorder.record(payment, fromStatus, payment.getStatus(),
                        PaymentHistoryTrigger.EXPIRY_SCHEDULER, "PG 미확인 - 만료 취소", null);
            }
        });

        Optional<Order> optionalOrder = orderRepository.findByIdWithLock(orderId);
        if (optionalOrder.isEmpty()) {
            return;
        }
        Order order = optionalOrder.get();
        if (order.getStatus() != OrderStatus.RESERVED) {
            return;
        }

        List<OrderItem> items = orderItemRepository.findAllByOrder(order);
        order.cancel("PG 미확인 - 만료 취소");

        items.forEach(item ->
                stockRedisService.releaseStockAfterCommit(item.getProduct().getId(), Math.toIntExact(item.getQuantity()))
        );
    }
}
