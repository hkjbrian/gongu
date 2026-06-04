package com.gongu.server.domain.payment.service;

import com.gongu.server.domain.order.entity.Order;
import com.gongu.server.domain.order.entity.OrderStatus;
import com.gongu.server.domain.order.repository.OrderRepository;
import com.gongu.server.domain.payment.domain.Payment;
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
import com.gongu.server.global.infrastructure.portone.dto.PortOnePaymentResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class PaymentService {

    private final UserRepository userRepository;
    private final OrderRepository orderRepository;
    private final PaymentRepository paymentRepository;
    private final PortOneClient portOneClient;

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

    @Transactional(noRollbackFor = {BusinessException.class, InfraException.class})
    public VerifyPaymentResponse completePayment(String paymentId) {
        Payment payment = paymentRepository.findByMerchantUidWithLock(paymentId)
                .orElseThrow(() -> new BusinessException(PaymentErrorCode.PAYMENT_NOT_FOUND));

        if (payment.getStatus() == PaymentStatus.PAID) {
            return VerifyPaymentResponse.of(payment.getOrder(), payment);
        }

        Order order = orderRepository.findByIdWithLock(payment.getOrder().getId())
                .orElseThrow(() -> new BusinessException(OrderErrorCode.ORDER_NOT_FOUND));

        if (order.getStatus() == OrderStatus.CANCELLED) {
            if (payment.getStatus() == PaymentStatus.REFUNDED) {
                // 이미 환불 완료 — 멱등 처리
                throw new BusinessException(PaymentErrorCode.ORDER_EXPIRED_REFUNDED);
            }
            if (payment.getStatus() != PaymentStatus.PENDING
                    && payment.getStatus() != PaymentStatus.CANCELLED) {
                throw new BusinessException(PaymentErrorCode.PAYMENT_INVALID_STATE_TRANSITION);
            }
            boolean pgCancelled = executePGCancel(paymentId, "주문 만료로 인한 자동 환불");
            if (pgCancelled) {
                payment.refund();
            }
            throw new BusinessException(PaymentErrorCode.ORDER_EXPIRED_REFUNDED);
        }

        if (payment.getStatus() != PaymentStatus.PENDING) {
            throw new BusinessException(PaymentErrorCode.PAYMENT_INVALID_STATE_TRANSITION);
        }

        PortOnePaymentResponse portOneResponse;
        try {
            portOneResponse = portOneClient.getPayment(paymentId);
        } catch (InfraException e) {
            payment.fail();   // dirty checking will persist this on transaction commit
            throw e;
        }

        if (portOneResponse == null) {
            payment.fail();
            throw new BusinessException(PaymentErrorCode.PAYMENT_PG_UNAVAILABLE);
        }

        if (!"PAID".equals(portOneResponse.status())) {
            payment.fail();   // dirty checking will persist this
            throw new BusinessException(PaymentErrorCode.PAYMENT_NOT_COMPLETED);
        }

        Long expectedAmount = order.getTotalPrice();
        Long actualAmount = portOneResponse.amount().total();

        if (expectedAmount.equals(actualAmount)) {
            order.pay();
            payment.confirm(actualAmount, portOneResponse.paidAt().toLocalDateTime());
            return VerifyPaymentResponse.of(order, payment);
        } else {
            executePGCancel(paymentId, "결제 금액 불일치");
            payment.refund();
            order.cancel("결제 금액 불일치");
            throw new BusinessException(PaymentErrorCode.PAYMENT_AMOUNT_MISMATCH);
        }
    }

    /**
     * PortOne 결제 취소 실행.
     *
     * @return true: 실제 PG 취소 발생 (또는 PAYMENT_ALREADY_PROCESSED)
     *         false: PAYMENT_NOT_FOUND (PG에 결제 없음 - 환불 불필요)
     */
    private boolean executePGCancel(String paymentId, String reason) {
        try {
            portOneClient.cancelPayment(paymentId, reason);
            return true;
        } catch (BusinessException e) {
            if (e.getErrorCode() == PaymentErrorCode.PAYMENT_ALREADY_PROCESSED) {
                log.info("PortOne cancel idempotent: paymentId={}, reason={}", paymentId, e.getErrorCode().getCode());
                return true;
            } else if (e.getErrorCode() == PaymentErrorCode.PAYMENT_NOT_FOUND) {
                log.info("PortOne cancel skipped - payment not found in PG: paymentId={}", paymentId);
                return false;
            } else {
                throw e;
            }
        }
    }
}
