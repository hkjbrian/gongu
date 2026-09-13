package com.gongu.server.domain.payment.service;

import com.gongu.server.domain.order.entity.Order;
import com.gongu.server.domain.order.entity.OrderItem;
import com.gongu.server.domain.order.entity.OrderStatus;
import com.gongu.server.domain.order.repository.OrderItemRepository;
import com.gongu.server.domain.order.repository.OrderRepository;
import com.gongu.server.domain.payment.domain.Payment;
import com.gongu.server.domain.payment.domain.PaymentHistoryTrigger;
import com.gongu.server.domain.payment.domain.PaymentStatus;
import com.gongu.server.domain.payment.dto.PaymentPrepareResult;
import com.gongu.server.domain.payment.dto.response.VerifyPaymentResponse;
import com.gongu.server.domain.payment.repository.PaymentRepository;
import com.gongu.server.domain.product.entity.Product;
import com.gongu.server.domain.product.repository.ProductRepository;
import com.gongu.server.domain.product.service.StockRedisService;
import com.gongu.server.domain.user.repository.UserRepository;
import com.gongu.server.global.exception.BusinessException;
import com.gongu.server.global.exception.InfraException;
import com.gongu.server.global.exception.errorcode.OrderErrorCode;
import com.gongu.server.global.exception.errorcode.PaymentErrorCode;
import com.gongu.server.global.exception.errorcode.ProductErrorCode;
import com.gongu.server.global.exception.errorcode.UserErrorCode;
import com.gongu.server.global.infrastructure.portone.PortOneClient;
import com.gongu.server.global.infrastructure.portone.dto.PortOnePaymentResponse;
import com.gongu.server.global.infrastructure.portone.dto.PortOnePaymentResult;
import io.github.resilience4j.bulkhead.annotation.Bulkhead;
import io.micrometer.core.instrument.Counter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
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
    private final OrderItemRepository orderItemRepository;
    private final ProductRepository productRepository;
    private final PaymentRepository paymentRepository;
    private final StockRedisService stockRedisService;
    private final PortOneClient portOneClient;
    private final PaymentHistoryRecorder paymentHistoryRecorder;
    @Qualifier("paymentCompletedCounter")
    private final Counter paymentCompletedCounter;
    @Qualifier("paymentFailedOrderExpiredIdempotentCounter")
    private final Counter paymentFailedOrderExpiredIdempotentCounter;
    @Qualifier("paymentFailedOrderExpiredCancelCounter")
    private final Counter paymentFailedOrderExpiredCancelCounter;
    @Qualifier("paymentFailedPgErrorCounter")
    private final Counter paymentFailedPgErrorCounter;
    @Qualifier("paymentFailedPgNullCounter")
    private final Counter paymentFailedPgNullCounter;
    @Qualifier("paymentFailedPgStatusMismatchCounter")
    private final Counter paymentFailedPgStatusMismatchCounter;
    @Qualifier("paymentFailedAmountMismatchCounter")
    private final Counter paymentFailedAmountMismatchCounter;

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

    @Bulkhead(name = "payment-complete")
    @Transactional(noRollbackFor = {BusinessException.class, InfraException.class})
    public VerifyPaymentResponse completePayment(String paymentId, PaymentHistoryTrigger trigger) {
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
                paymentFailedOrderExpiredIdempotentCounter.increment();
                throw new BusinessException(PaymentErrorCode.ORDER_EXPIRED_REFUNDED);
            }
            if (payment.getStatus() != PaymentStatus.PENDING
                    && payment.getStatus() != PaymentStatus.CANCELLED) {
                throw new BusinessException(PaymentErrorCode.PAYMENT_INVALID_STATE_TRANSITION);
            }
            PaymentStatus beforeExpiredBranch = payment.getStatus();
            PgCancelOutcome cancelOutcome = executePGCancel(paymentId, "주문 만료로 인한 자동 환불");
            if (cancelOutcome.cancelled()) {
                payment.refund();
                paymentHistoryRecorder.record(payment, beforeExpiredBranch, payment.getStatus(), trigger,
                        "주문 만료로 인한 자동 환불", cancelOutcome.rawBody());
            } else if (beforeExpiredBranch == PaymentStatus.PENDING) {
                payment.expire();
                paymentHistoryRecorder.record(payment, beforeExpiredBranch, payment.getStatus(), trigger,
                        "주문 만료 - PG에 결제 없음", null);
            }
            paymentFailedOrderExpiredCancelCounter.increment();
            throw new BusinessException(PaymentErrorCode.ORDER_EXPIRED_REFUNDED);
        }

        if (payment.getStatus() != PaymentStatus.PENDING) {
            throw new BusinessException(PaymentErrorCode.PAYMENT_INVALID_STATE_TRANSITION);
        }

        PortOnePaymentResult portOneResult;
        try {
            portOneResult = portOneClient.getPayment(paymentId);
        } catch (InfraException e) {
            // 판정 불가 — PG 조회 실패. 상태를 바꾸지 않고 PENDING을 유지해
            // 웹훅/사용자 재시도가 정상 확정에 도달할 수 있게 한다.
            log.warn("PortOne 조회 실패 — payment PENDING 유지, 재시도 대기: paymentId={}", paymentId, e);
            paymentFailedPgErrorCounter.increment();
            throw e;
        }

        // portOneResult.response()가 null이면(빈/공백 바디 파싱 결과, PortOneClient.parseResponse 참고)
        // "PG 응답 없음"으로 취급한다. portOneResult 자체의 null 체크는 실제 PortOneClient가
        // 만들어낼 수 없는 상태에 대한 방어이지만, 유지 비용이 없어 남겨둔다.
        PortOnePaymentResponse portOneResponse = portOneResult == null ? null : portOneResult.response();

        if (portOneResponse == null) {
            // 판정 불가 — 빈 응답. 상태를 바꾸지 않고 PENDING 유지.
            log.warn("PortOne 빈 응답 — payment PENDING 유지, 재시도 대기: paymentId={}", paymentId);
            paymentFailedPgNullCounter.increment();
            throw new BusinessException(PaymentErrorCode.PAYMENT_PG_UNAVAILABLE);
        }

        if (!"PAID".equals(portOneResponse.status())) {
            // 판정 완료 — PG가 미결제/실패로 확정 응답. FAILED로 확정한다.
            PaymentStatus beforeFail = payment.getStatus();
            payment.fail();
            paymentHistoryRecorder.record(payment, beforeFail, payment.getStatus(), trigger,
                    "PG 상태 불일치: " + portOneResponse.status(), portOneResult.rawBody());
            paymentFailedPgStatusMismatchCounter.increment();
            throw new BusinessException(PaymentErrorCode.PAYMENT_NOT_COMPLETED);
        }

        Long expectedAmount = order.getTotalPrice();
        Long actualAmount = portOneResponse.amount().total();

        if (expectedAmount.equals(actualAmount)) {
            PaymentStatus beforeConfirm = payment.getStatus();
            order.pay();
            payment.confirm(actualAmount, portOneResponse.paidAt().toLocalDateTime());
            paymentHistoryRecorder.record(payment, beforeConfirm, payment.getStatus(), trigger,
                    null, portOneResult.rawBody());

            // MySQL remaining_stock 차감 (결제 확정 시점)
            List<OrderItem> items = orderItemRepository.findAllByOrder(order);
            items.forEach(item -> {
                Product product = productRepository.findByIdWithLock(item.getProduct().getId())
                        .orElseThrow(() -> new BusinessException(ProductErrorCode.PRODUCT_NOT_FOUND));
                product.confirmStock(Math.toIntExact(item.getQuantity()));
            });

            paymentCompletedCounter.increment();
            return VerifyPaymentResponse.of(order, payment);
        } else {
            PaymentStatus beforeMismatch = payment.getStatus();
            PgCancelOutcome cancelOutcome = executePGCancel(paymentId, "결제 금액 불일치");
            payment.refund();
            paymentHistoryRecorder.record(payment, beforeMismatch, payment.getStatus(), trigger,
                    "결제 금액 불일치",
                    cancelOutcome.rawBody() != null ? cancelOutcome.rawBody() : portOneResult.rawBody());
            paymentFailedAmountMismatchCounter.increment();
            order.cancel("결제 금액 불일치");
            List<OrderItem> cancelledItems = orderItemRepository.findAllByOrder(order);
            cancelledItems.forEach(item ->
                    stockRedisService.releaseStockAfterCommit(item.getProduct().getId(), Math.toIntExact(item.getQuantity()))
            );
            throw new BusinessException(PaymentErrorCode.PAYMENT_AMOUNT_MISMATCH);
        }
    }

    private record PgCancelOutcome(boolean cancelled, String rawBody) {}

    /**
     * PortOne 결제 취소 실행.
     *
     * @return cancelled=true: 실제 PG 취소 발생 (또는 PAYMENT_ALREADY_PROCESSED)
     *         cancelled=false: PAYMENT_NOT_FOUND (PG에 결제 없음 - 환불 불필요)
     */
    private PgCancelOutcome executePGCancel(String paymentId, String reason) {
        try {
            PortOnePaymentResult result = portOneClient.cancelPayment(paymentId, reason);
            return new PgCancelOutcome(true, result == null ? null : result.rawBody());
        } catch (BusinessException e) {
            if (e.getErrorCode() == PaymentErrorCode.PAYMENT_ALREADY_PROCESSED) {
                log.info("PortOne cancel idempotent: paymentId={}, reason={}", paymentId, e.getErrorCode().getCode());
                return new PgCancelOutcome(true, null);
            } else if (e.getErrorCode() == PaymentErrorCode.PAYMENT_NOT_FOUND) {
                log.info("PortOne cancel skipped - payment not found in PG: paymentId={}", paymentId);
                return new PgCancelOutcome(false, null);
            } else {
                throw e;
            }
        }
    }
}
