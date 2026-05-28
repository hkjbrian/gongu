package com.gongu.server.domain.payment.service;

import com.gongu.server.domain.order.entity.Order;
import com.gongu.server.domain.order.entity.OrderStatus;
import com.gongu.server.domain.order.repository.OrderRepository;
import com.gongu.server.domain.payment.domain.Payment;
import com.gongu.server.domain.payment.domain.PaymentStatus;
import com.gongu.server.domain.payment.dto.PaymentPrepareResult;
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

    @Transactional(noRollbackFor = {BusinessException.class, InfraException.class})
    public void completePayment(String paymentId) {
        Payment payment = paymentRepository.findByMerchantUidWithLock(paymentId)
                .orElseThrow(() -> new BusinessException(PaymentErrorCode.PAYMENT_NOT_FOUND));

        if (payment.getStatus() == PaymentStatus.PAID) {
            return;
        }
        if (payment.getStatus() != PaymentStatus.PENDING) {
            throw new BusinessException(PaymentErrorCode.PAYMENT_INVALID_STATE_TRANSITION);
        }

        Order order = orderRepository.findByIdWithLock(payment.getOrder().getId())
                .orElseThrow(() -> new BusinessException(OrderErrorCode.ORDER_NOT_FOUND));

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
            // dirty checking auto-commits both — method returns normally
        } else {
            payment.cancelByMismatch();
            order.cancel("결제 금액 불일치");
            portOneClient.cancelPayment(paymentId, "결제 금액 불일치");
            throw new BusinessException(PaymentErrorCode.PAYMENT_AMOUNT_MISMATCH);
            // noRollbackFor → transaction commits with cancelled state persisted
        }
    }
}
