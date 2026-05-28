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
    private final PaymentResultCommitter committer;

    @Transactional
    public void verifyPayment(Long userId, String idempotencyKey, Long orderId, String paymentId) {
        // 1단계: 멱등키 중복 검사
        if (paymentRepository.findByIdempotencyKey(idempotencyKey).isPresent()) {
            throw new BusinessException(PaymentErrorCode.PAYMENT_ALREADY_PROCESSED);
        }

        // 2단계: 사용자 조회
        userRepository.findByIdAndDeletedAtIsNull(userId)
                .orElseThrow(() -> new BusinessException(UserErrorCode.USER_NOT_FOUND));

        // 3단계: 주문 비관적 락 조회 + 소유권/상태 검증
        Order order = orderRepository.findByIdWithLock(orderId)
                .orElseThrow(() -> new BusinessException(OrderErrorCode.ORDER_NOT_FOUND));

        if (!order.isOwnedBy(userId)) {
            throw new BusinessException(PaymentErrorCode.PAYMENT_NOT_ALLOWED);
        }
        if (order.getStatus() != OrderStatus.RESERVED) {
            throw new BusinessException(PaymentErrorCode.PAYMENT_NOT_ALLOWED);
        }

        // 4단계: 결제 레코드 초기화 저장 (서버 저장 주문 금액 기준)
        Long expectedAmount = order.getTotalPrice();
        Payment payment = Payment.initiate(order, idempotencyKey, paymentId, expectedAmount);
        paymentRepository.save(payment);

        // 5단계: PortOne 조회
        PortOnePaymentResponse portOneResponse;
        try {
            portOneResponse = portOneClient.getPayment(paymentId);
        } catch (InfraException e) {
            committer.commitFail(payment);   // REQUIRES_NEW: fail 상태 즉시 커밋
            throw e;
        }

        // 6단계: PortOne 결제 status 검증
        if (!"PAID".equals(portOneResponse.status())) {
            committer.commitFail(payment);   // REQUIRES_NEW: fail 상태 즉시 커밋
            throw new BusinessException(PaymentErrorCode.PAYMENT_NOT_COMPLETED);
        }

        // 7단계: 금액 비교 (서버 저장값 vs PortOne 실제 결제액)
        Long actualAmount = portOneResponse.amount().total();

        if (expectedAmount.equals(actualAmount)) {
            // 금액 일치: REQUIRES_NEW로 confirm + order.pay() 커밋
            committer.commitConfirm(payment, order, actualAmount, portOneResponse.paidAt().toLocalDateTime());
        } else {
            // 금액 불일치: REQUIRES_NEW로 보상 커밋 → PG 취소 → 예외
            committer.commitMismatchCancel(payment, order);
            portOneClient.cancelPayment(paymentId, "결제 금액 불일치");
            throw new BusinessException(PaymentErrorCode.PAYMENT_AMOUNT_MISMATCH);
        }
    }
}
