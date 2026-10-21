package com.gongu.server.domain.payment.service;

import com.gongu.server.domain.order.entity.Order;
import com.gongu.server.domain.order.entity.OrderItem;
import com.gongu.server.domain.order.entity.OrderStatus;
import com.gongu.server.domain.order.repository.OrderItemRepository;
import com.gongu.server.domain.order.repository.OrderRepository;
import com.gongu.server.domain.payment.domain.Payment;
import com.gongu.server.domain.payment.domain.PaymentHistoryTrigger;
import com.gongu.server.domain.payment.domain.PaymentStatus;
import com.gongu.server.domain.payment.dto.response.VerifyPaymentResponse;
import com.gongu.server.domain.payment.repository.PaymentRepository;
import com.gongu.server.domain.product.entity.Product;
import com.gongu.server.domain.product.repository.ProductRepository;
import com.gongu.server.domain.product.service.StockRedisService;
import com.gongu.server.global.exception.BusinessException;
import com.gongu.server.global.exception.ErrorCode;
import com.gongu.server.global.exception.InfraException;
import com.gongu.server.global.exception.errorcode.OrderErrorCode;
import com.gongu.server.global.exception.errorcode.PaymentErrorCode;
import com.gongu.server.global.exception.errorcode.ProductErrorCode;
import com.gongu.server.global.infrastructure.portone.PortOneClient;
import com.gongu.server.global.infrastructure.portone.dto.PortOnePaymentResponse;
import com.gongu.server.global.infrastructure.portone.dto.PortOnePaymentResult;
import io.micrometer.core.instrument.Counter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * completePayment의 락 재획득·상태 재검증·확정/보상 분기를 담당한다 (#146).
 * PG 조회(portOneClient.getPayment)는 PaymentService.completePayment(코디네이터)가
 * 트랜잭션·락 밖에서 먼저 시도하고 그 결과를 prefetchedResult로 넘긴다 — null이면
 * (코디네이터가 판단을 건너뛴 레이스 상황 등) 이 클래스가 직접 호출한다.
 * <p>
 * <b>{@code prefetchedResult}로 {@code null}을 넘기면 이 메서드가 payments/orders
 * 비관적 쓰기 락을 쥔 채로 PG 호출을 직접 수행한다 — #146이 없애려던 안티패턴으로
 * 되돌아가는 degraded path다.</b> 코디네이터/리컨실러 레이스 등 일부 상황을 위한
 * 안전판으로만 존재하며, 평상시 진입점으로는 {@link PaymentService#completePayment}를
 * 사용해 트랜잭션·락 밖에서 선조회한 결과를 넘기는 쪽을 우선한다.
 * 별도 빈으로 분리한 이유: 코디네이터가 같은 클래스 안에서 self-invocation으로 이
 * 메서드를 부르면 Spring 프록시 기반 @Transactional이 무시되기 때문이다
 * (PaymentExpireReconciler, #215와 동일한 이유).
 * <p>
 * completePayment 본문은 가드 클로즈로 이어지는 짧은 스크립트이고, 단계별 처리는 private 메서드가
 * 맡는다 — {@code handleX}는 상태를 바꾼 뒤 던질 {@link ErrorCode}를 반환하고(호출부가 throw),
 * 금액 불일치·재고 부족의 공통 보상 순서는 {@code refundAndCancelOrder}로 통합했다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PaymentReconciler {

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
    @Qualifier("paymentFailedInsufficientStockCounter")
    private final Counter paymentFailedInsufficientStockCounter;
    @Qualifier("paymentFetchedUnderLockCounter")
    private final Counter paymentFetchedUnderLockCounter;

    @Transactional(noRollbackFor = {BusinessException.class, InfraException.class})
    public VerifyPaymentResponse completePayment(String paymentId, PaymentHistoryTrigger trigger,
                                                  PortOnePaymentResult prefetchedResult) {
        Payment payment = paymentRepository.findByMerchantUidWithLock(paymentId)
                .orElseThrow(() -> new BusinessException(PaymentErrorCode.PAYMENT_NOT_FOUND));

        if (payment.getStatus() == PaymentStatus.PAID) {
            return VerifyPaymentResponse.of(payment.getOrder(), payment);
        }

        Order order = lockOrder(payment);

        if (order.getStatus() == OrderStatus.CANCELLED) {
            throw new BusinessException(handleCancelledOrder(payment, order, paymentId, trigger));
        }

        if (payment.getStatus() != PaymentStatus.PENDING) {
            throw new BusinessException(PaymentErrorCode.PAYMENT_INVALID_STATE_TRANSITION);
        }

        PortOnePaymentResult pgResult = resolvePgResult(paymentId, prefetchedResult);

        // pgResult.response()가 null이면(빈/공백 바디 파싱 결과, PortOneClient.parseResponse 참고)
        // "PG 응답 없음"으로 취급한다. pgResult 자체의 null 체크는 실제 PortOneClient가
        // 만들어낼 수 없는 상태에 대한 방어이지만, 유지 비용이 없어 남겨둔다.
        PortOnePaymentResponse paid = pgResult == null ? null : pgResult.response();

        if (paid == null) {
            // 판정 불가 — 빈 응답. 상태를 바꾸지 않고 PENDING 유지.
            log.warn("PortOne 빈 응답 — payment PENDING 유지, 재시도 대기: paymentId={}", paymentId);
            paymentFailedPgNullCounter.increment();
            throw new BusinessException(PaymentErrorCode.PAYMENT_PG_UNAVAILABLE);
        }

        if (!"PAID".equals(paid.status())) {
            throw new BusinessException(handlePgNotPaid(payment, pgResult, trigger));
        }

        if (!order.getTotalPrice().equals(paid.amount().total())) {
            throw new BusinessException(handleAmountMismatch(payment, order, paymentId, pgResult, trigger));
        }

        // 재고 확인·차감을 order.pay()/payment.confirm()보다 먼저 수행한다 (#230).
        // 이전 순서(상태 확정 → 재고 차감)는 재고 부족 예외가 noRollbackFor라 롤백되지 않고
        // 결제·주문이 PAID로 커밋되는 버그가 있었다 — PG는 결제됐는데 재고는 안 깎이고 환불도 없는 상태.
        // 항목이 여러 개여도 "전 항목 확인 → 전 항목 확정" 두 단계로 나눠, 앞 항목만 차감된 채
        // 뒤 항목에서 재고 부족이 나는 부분 커밋을 만들지 않는다.
        List<OrderItem> items = orderItemRepository.findAllByOrder(order);
        List<LockedOrderItem> lockedItems = lockProducts(items);

        boolean stockInsufficient = lockedItems.stream()
                .anyMatch(locked -> locked.product().getRemainingStock() < locked.quantity());
        if (stockInsufficient) {
            throw new BusinessException(
                    handleInsufficientStock(payment, order, paymentId, items, pgResult, trigger));
        }

        confirm(payment, order, paid, pgResult, lockedItems, trigger);
        return VerifyPaymentResponse.of(order, payment);
    }

    private Order lockOrder(Payment payment) {
        return orderRepository.findByIdWithLock(payment.getOrder().getId())
                .orElseThrow(() -> new BusinessException(OrderErrorCode.ORDER_NOT_FOUND));
    }

    /**
     * 만료(CANCELLED)된 주문에 대한 결제 완료 시도 처리. 환불/만료 갈래가 보상 통합 대상과 달라
     * {@link #refundAndCancelOrder}를 쓰지 않는다.
     */
    private ErrorCode handleCancelledOrder(Payment payment, Order order, String paymentId,
                                           PaymentHistoryTrigger trigger) {
        if (payment.getStatus() == PaymentStatus.REFUNDED) {
            // 이미 환불 완료 — 멱등 처리
            paymentFailedOrderExpiredIdempotentCounter.increment();
            return PaymentErrorCode.ORDER_EXPIRED_REFUNDED;
        }
        if (payment.getStatus() != PaymentStatus.PENDING
                && payment.getStatus() != PaymentStatus.CANCELLED) {
            return PaymentErrorCode.PAYMENT_INVALID_STATE_TRANSITION;
        }
        PaymentStatus before = payment.getStatus();
        PgCancelOutcome cancelOutcome = executePGCancel(paymentId, "주문 만료로 인한 자동 환불");
        if (cancelOutcome.cancelled()) {
            payment.refund();
            paymentHistoryRecorder.record(payment, before, payment.getStatus(), trigger,
                    "주문 만료로 인한 자동 환불", cancelOutcome.rawBody());
        } else if (before == PaymentStatus.PENDING) {
            payment.expire();
            paymentHistoryRecorder.record(payment, before, payment.getStatus(), trigger,
                    "주문 만료 - PG에 결제 없음", null);
        }
        paymentFailedOrderExpiredCancelCounter.increment();
        return PaymentErrorCode.ORDER_EXPIRED_REFUNDED;
    }

    private PortOnePaymentResult resolvePgResult(String paymentId, PortOnePaymentResult prefetchedResult) {
        if (prefetchedResult != null) {
            return prefetchedResult;
        }
        // degraded path — payments/orders 비관적 쓰기 락을 쥔 채로 PG를 직접 조회한다
        // (#146이 없애려던 안티패턴). 코디네이터가 선조회를 건너뛴 레이스 상황 등에
        // 대한 안전판일 뿐, 정상 경로에서는 도달하지 않아야 한다.
        log.warn("PG 락 하 재조회(degraded path) — 코디네이터 선조회 없이 락 보유 중 직접 호출: paymentId={}", paymentId);
        paymentFetchedUnderLockCounter.increment();
        try {
            return portOneClient.getPayment(paymentId);
        } catch (InfraException e) {
            // 판정 불가 — PG 조회 실패. 상태를 바꾸지 않고 PENDING을 유지해
            // 웹훅/사용자 재시도가 정상 확정에 도달할 수 있게 한다.
            log.warn("PortOne 조회 실패 — payment PENDING 유지, 재시도 대기: paymentId={}", paymentId, e);
            paymentFailedPgErrorCounter.increment();
            throw e;
        }
    }

    private ErrorCode handlePgNotPaid(Payment payment, PortOnePaymentResult pgResult,
                                      PaymentHistoryTrigger trigger) {
        // 판정 완료 — PG가 미결제/실패로 확정 응답. FAILED로 확정한다.
        PaymentStatus before = payment.getStatus();
        payment.fail();
        paymentHistoryRecorder.record(payment, before, payment.getStatus(), trigger,
                "PG 상태 불일치: " + pgResult.response().status(), pgResult.rawBody());
        paymentFailedPgStatusMismatchCounter.increment();
        return PaymentErrorCode.PAYMENT_NOT_COMPLETED;
    }

    private ErrorCode handleAmountMismatch(Payment payment, Order order, String paymentId,
                                           PortOnePaymentResult pgResult, PaymentHistoryTrigger trigger) {
        List<OrderItem> items = orderItemRepository.findAllByOrder(order);
        refundAndCancelOrder(payment, order, paymentId, items, pgResult, trigger,
                "결제 금액 불일치", paymentFailedAmountMismatchCounter);
        return PaymentErrorCode.PAYMENT_AMOUNT_MISMATCH;
    }

    private ErrorCode handleInsufficientStock(Payment payment, Order order, String paymentId,
                                              List<OrderItem> items, PortOnePaymentResult pgResult,
                                              PaymentHistoryTrigger trigger) {
        refundAndCancelOrder(payment, order, paymentId, items, pgResult, trigger,
                "재고 부족으로 인한 자동 환불", paymentFailedInsufficientStockCounter);
        return PaymentErrorCode.PAYMENT_INSUFFICIENT_STOCK_REFUNDED;
    }

    private List<LockedOrderItem> lockProducts(List<OrderItem> items) {
        return items.stream()
                .map(item -> new LockedOrderItem(
                        productRepository.findByIdWithLock(item.getProduct().getId())
                                .orElseThrow(() -> new BusinessException(ProductErrorCode.PRODUCT_NOT_FOUND)),
                        Math.toIntExact(item.getQuantity())))
                .toList();
    }

    private void confirm(Payment payment, Order order, PortOnePaymentResponse paid, PortOnePaymentResult pgResult,
                         List<LockedOrderItem> locked, PaymentHistoryTrigger trigger) {
        PaymentStatus before = payment.getStatus();
        order.pay();
        payment.confirm(paid.amount().total(), paid.paidAt().toLocalDateTime());
        paymentHistoryRecorder.record(payment, before, payment.getStatus(), trigger,
                null, pgResult.rawBody());

        locked.forEach(item -> item.product().confirmStock(item.quantity()));

        paymentCompletedCounter.increment();
    }

    /**
     * 금액 불일치·재고 부족 공통 보상: PG 취소 → 결제 환불 → 이력·카운터 → 주문 취소 → 재고 복원.
     * PG 취소가 가장 먼저 실행되므로 실패(예외) 시 어떤 상태도 바뀌지 않는다.
     */
    private void refundAndCancelOrder(Payment payment, Order order, String paymentId, List<OrderItem> items,
                                      PortOnePaymentResult pgResult, PaymentHistoryTrigger trigger,
                                      String reason, Counter counter) {
        PaymentStatus before = payment.getStatus();
        PgCancelOutcome cancelOutcome = executePGCancel(paymentId, reason);
        payment.refund();
        paymentHistoryRecorder.record(payment, before, payment.getStatus(), trigger, reason,
                cancelOutcome.rawBody() != null ? cancelOutcome.rawBody() : pgResult.rawBody());
        counter.increment();
        order.cancel(reason);
        items.forEach(item ->
                stockRedisService.releaseStockAfterCommit(item.getProduct().getId(), Math.toIntExact(item.getQuantity()))
        );
    }

    private record LockedOrderItem(Product product, int quantity) {}

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
