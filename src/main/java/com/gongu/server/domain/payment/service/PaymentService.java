package com.gongu.server.domain.payment.service;

import com.gongu.server.domain.order.entity.Order;
import com.gongu.server.domain.order.entity.OrderStatus;
import com.gongu.server.domain.order.repository.OrderRepository;
import com.gongu.server.domain.payment.domain.Payment;
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

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class PaymentService {

    private final UserRepository userRepository;
    private final OrderRepository orderRepository;
    private final PaymentRepository paymentRepository;
    private final PortOneClient portOneClient;

    @Transactional
    public void verifyPayment(Long userId, String idempotencyKey, Long orderId, String paymentId, Long amount) {
        // 1단계: 멱등키 중복 검사
        if (paymentRepository.findByIdempotencyKey(idempotencyKey).isPresent()) {
            throw new BusinessException(PaymentErrorCode.PAYMENT_ALREADY_PROCESSED);
        }

        // 2단계: 사용자 조회
        userRepository.findByIdAndDeletedAtIsNull(userId)
                .orElseThrow(() -> new BusinessException(UserErrorCode.USER_NOT_FOUND));

        // 3단계: 주문 비관적 락 조회 + 검증
        Order order = orderRepository.findByIdWithLock(orderId)
                .orElseThrow(() -> new BusinessException(OrderErrorCode.ORDER_NOT_FOUND));

        if (!order.getUser().getId().equals(userId)) {
            throw new BusinessException(PaymentErrorCode.PAYMENT_NOT_ALLOWED);
        }
        if (order.getStatus() != OrderStatus.RESERVED) {
            throw new BusinessException(PaymentErrorCode.PAYMENT_NOT_ALLOWED);
        }

        // 4단계: 결제 레코드 초기화 저장
        Payment payment = Payment.initiate(order, idempotencyKey, paymentId, amount);
        paymentRepository.save(payment);

        // 5단계: PortOne 조회 및 예외 처리
        try {
            PortOnePaymentResponse portOneResponse = portOneClient.getPayment(paymentId);

            Long portOneAmount = portOneResponse.amount().total();

            if (amount.equals(portOneAmount)) {
                // 6단계: 금액 일치
                payment.confirm(portOneAmount, portOneResponse.paidAt().toLocalDateTime());
                order.pay();
            } else {
                // 7단계: 금액 불일치 보상
                payment.cancelByMismatch();
                order.cancel("결제 금액 불일치");
                portOneClient.cancelPayment(paymentId, "결제 금액 불일치");
                throw new BusinessException(PaymentErrorCode.PAYMENT_AMOUNT_MISMATCH);
            }
        } catch (InfraException e) {
            payment.fail();
            throw e;
        }
    }
}
