package com.gongu.server.domain.payment.service;

import com.gongu.server.domain.order.entity.Order;
import com.gongu.server.domain.order.entity.OrderStatus;
import com.gongu.server.domain.order.repository.OrderRepository;
import com.gongu.server.domain.payment.domain.Payment;
import com.gongu.server.domain.payment.domain.PaymentHistoryTrigger;
import com.gongu.server.domain.payment.domain.PaymentStatus;
import com.gongu.server.domain.payment.dto.PaymentPrepareResult;
import com.gongu.server.domain.payment.dto.response.VerifyPaymentResponse;
import com.gongu.server.domain.payment.repository.PaymentRepository;
import com.gongu.server.domain.user.repository.UserRepository;
import com.gongu.server.global.exception.BusinessException;
import com.gongu.server.global.exception.InfraException;
import com.gongu.server.global.exception.errorcode.OrderErrorCode;
import com.gongu.server.global.exception.errorcode.PaymentErrorCode;
import com.gongu.server.global.exception.errorcode.UserErrorCode;
import com.gongu.server.global.infrastructure.portone.PortOneClient;
import com.gongu.server.global.infrastructure.portone.dto.PortOnePaymentResult;
import io.github.resilience4j.bulkhead.annotation.Bulkhead;
import io.micrometer.core.instrument.Counter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * completePayment(paymentId, trigger)는 얇은 코디네이터다 (#146).
 * 락·트랜잭션 없이 payment/order 상태를 훑어 PG 조회가 필요한 상황인지 판단하고,
 * 필요하면 트랜잭션·락 밖에서 portOneClient.getPayment()를 먼저 호출한 뒤
 * (또는 필요 없으면 결과 없이) PaymentReconciler의 짧은 트랜잭션에 위임한다.
 * 실제 확정/보상 로직은 전부 {@link PaymentReconciler}에 있다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class PaymentService {

    private final UserRepository userRepository;
    private final OrderRepository orderRepository;
    private final PaymentRepository paymentRepository;
    private final PortOneClient portOneClient;
    @Qualifier("paymentFailedPgErrorCounter")
    private final Counter paymentFailedPgErrorCounter;
    private final PaymentReconciler reconciler;

    @Transactional
    public PaymentPrepareResult preparePayment(Long userId, Long orderId) {
        userRepository.findByIdAndDeletedAtIsNull(userId)
                .orElseThrow(() -> new BusinessException(UserErrorCode.USER_NOT_FOUND));

        Order order = orderRepository.findByIdWithLock(orderId)
                .orElseThrow(() -> new BusinessException(OrderErrorCode.ORDER_NOT_FOUND));

        if (!order.isOwnedBy(userId)) {
            throw new BusinessException(PaymentErrorCode.PAYMENT_NOT_ALLOWED);
        }
        if (order.getStatus() != OrderStatus.RESERVED) {
            throw new BusinessException(PaymentErrorCode.PAYMENT_NOT_ALLOWED);
        }

        boolean hasActivePayment = paymentRepository.existsByOrderIdAndStatusIn(
                orderId, List.of(PaymentStatus.PENDING, PaymentStatus.PAID));
        if (hasActivePayment) {
            throw new BusinessException(PaymentErrorCode.PAYMENT_ACTIVE_EXISTS);
        }

        String paymentId = UUID.randomUUID().toString();
        String idempotencyKey = UUID.randomUUID().toString();
        Payment payment = Payment.initiate(order, idempotencyKey, paymentId, order.getTotalPrice());
        paymentRepository.save(payment);

        return new PaymentPrepareResult(paymentId, order.getTotalPrice());
    }

    public void validateOwnership(Long userId, String paymentId) {
        Payment payment = paymentRepository.findByMerchantUid(paymentId)
                .orElseThrow(() -> new BusinessException(PaymentErrorCode.PAYMENT_NOT_FOUND));
        if (!payment.getOrder().isOwnedBy(userId)) {
            throw new BusinessException(PaymentErrorCode.PAYMENT_NOT_ALLOWED);
        }
    }

    /**
     * 클래스 레벨 @Transactional(readOnly = true)이 조용히 상속되지 않도록
     * Propagation.NOT_SUPPORTED를 명시한다 — 읽기 전용이라도 트랜잭션이 열리면
     * HikariCP 커넥션을 점유한 채 PG를 호출하게 되어 이 메서드의 존재 이유가 사라진다.
     */
    @Bulkhead(name = "payment-complete")
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public VerifyPaymentResponse completePayment(String paymentId, PaymentHistoryTrigger trigger) {
        Optional<Payment> maybePayment = paymentRepository.findByMerchantUid(paymentId);
        if (maybePayment.isEmpty()) {
            return reconciler.completePayment(paymentId, trigger, null);
        }
        Payment payment = maybePayment.get();

        if (payment.getStatus() == PaymentStatus.PAID) {
            Order order = orderRepository.findById(payment.getOrder().getId())
                    .orElseThrow(() -> new BusinessException(OrderErrorCode.ORDER_NOT_FOUND));
            return VerifyPaymentResponse.of(order, payment);
        }

        PortOnePaymentResult prefetched = null;
        if (payment.getStatus() == PaymentStatus.PENDING) {
            Order order = orderRepository.findById(payment.getOrder().getId()).orElse(null);
            if (order != null && order.getStatus() != OrderStatus.CANCELLED) {
                try {
                    prefetched = portOneClient.getPayment(paymentId);
                } catch (InfraException e) {
                    log.warn("PortOne 조회 실패(트랜잭션 밖 선조회) — 재확인 대기: paymentId={}", paymentId, e);
                    paymentFailedPgErrorCounter.increment();
                    throw e;
                }
            }
        }

        return reconciler.completePayment(paymentId, trigger, prefetched);
    }
}
