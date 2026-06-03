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
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

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
        if (payment.getStatus() != PaymentStatus.PENDING) {
            throw new BusinessException(PaymentErrorCode.PAYMENT_INVALID_STATE_TRANSITION);
        }

        Order order = orderRepository.findByIdWithLock(payment.getOrder().getId())
                .orElseThrow(() -> new BusinessException(OrderErrorCode.ORDER_NOT_FOUND));

        if (order.getStatus() == OrderStatus.CANCELLED) {
            try {
                portOneClient.cancelPayment(paymentId, "주문 만료로 인한 자동 환불");
            } catch (InfraException e) {
                // PortOne 취소 실패 — 환불 미완료이므로 REFUNDED로 커밋하지 않고 재시도 가능 상태 유지
                payment.fail();
                throw e;
            }
            payment.refund();
            throw new BusinessException(PaymentErrorCode.ORDER_EXPIRED_REFUNDED);
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
            portOneClient.cancelPayment(paymentId, "결제 금액 불일치");
            payment.refund();
            order.cancel("결제 금액 불일치");
            throw new BusinessException(PaymentErrorCode.PAYMENT_AMOUNT_MISMATCH);
        }
    }
}
