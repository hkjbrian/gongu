# #146 completePayment PG 호출을 트랜잭션 밖으로 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** `PaymentService.completePayment`가 `@Transactional` 범위 안에서(즉 payments·orders 행 비관적 락을 쥔 채) PortOne `getPayment` HTTP 호출을 하던 구조를 고친다. PG 조회는 락도 트랜잭션도 없는 상태에서 먼저 수행하고, 그 결과를 짧은 트랜잭션에 반영한다.

**Architecture:** ADR-008 §6의 "B안"(트랜잭션 분리, 비관적 락은 유지)을 채택한다. `PaymentService.completePayment`는 얇은 코디네이터가 된다 — 락 없이 payment/order 상태를 훑어 "PG를 불러야 하는 상황(PENDING + 주문이 CANCELLED 아님)"인지 판단하고, 맞으면 트랜잭션·락 밖에서 `portOneClient.getPayment()`를 호출한 뒤, 그 결과(또는 호출이 필요 없었으면 `null`)를 새로 만드는 `PaymentReconciler`(ADR-008 D1 명명)의 `@Transactional` 메서드에 넘긴다. `PaymentReconciler`는 오늘의 `completePayment` 로직을 거의 그대로 가져간다 — 락 재획득, 모든 상태 재검증, 분기 처리 — 단 PG 조회 지점만 "미리 받은 결과가 있으면 그걸 쓰고, 없으면(레이스로 인해 코디네이터가 건너뛴 경우) 직접 호출"로 바뀐다. 같은 클래스 안에서 self-invocation으로 부르면 `@Transactional`이 무시되는 문제(#215에서 실제로 겪음)를 피하려고 별도 `@Component` 빈으로 분리한다 — `PaymentExpireReconciler`(#215)와 동일한 패턴.

**Tech Stack:** Spring Boot 3.5, Spring Data JPA, Resilience4j, JUnit5 + Mockito + AssertJ, `@SpringBootTest`(통합 테스트).

## Global Constraints

- 커밋 메시지 형식: `type: 작업 내용 (#146)`. `Co-Authored-By` 절대 포함 금지.
- 락 순서는 그대로 유지: Payment → Order → Product(항목별). 이번 작업은 락을 **없애지 않는다** — 언제 획득하는지(트랜잭션 시작 시점)만 바뀐다. 조건부 UPDATE(D2)는 이번 범위 밖이다(ADR-008 §5 실험 필요, 별도).
- 이번 범위는 **정상 확정 경로(`getPayment` 호출)만**이다. 보상 분기(주문 만료 후 환불·금액 불일치·재고 부족)의 `executePGCancel`(→ `cancelPayment` 호출)은 여전히 락을 쥔 채로 실행된다 — ADR-008 P1에 이미 문서화된 기존 gap이고, 의도적으로 이번 PR에 포함하지 않는다.
- `PaymentService`의 클래스 레벨 `@Transactional(readOnly = true)`가 새 코디네이터 메서드에 조용히 상속되지 않도록, 반드시 `@Transactional(propagation = Propagation.NOT_SUPPORTED)`를 명시한다 — 이게 없으면 읽기 전용이라도 트랜잭션(=DB 커넥션 점유)이 열린 채로 PG를 호출하게 되어 이 이슈의 목적 자체가 무효화된다.
- `PaymentHistoryRecorder.record(...)`는 `@Transactional(propagation = MANDATORY)` — 반드시 이미 트랜잭션 안에서(즉 `PaymentReconciler` 안에서만) 호출한다.
- `@Bulkhead(name = "payment-complete")`는 `PaymentService.completePayment`(코디네이터, 외부에서 보이는 진입점)에만 남긴다 — `PaymentReconciler` 쪽에는 붙이지 않는다.

---

### Task 1: `PaymentReconciler` 신설 — 기존 로직을 그대로 이관하고 `prefetchedResult` 파라미터만 추가

**Files:**
- Create: `src/main/java/com/gongu/server/domain/payment/service/PaymentReconciler.java`
- Create: `src/test/java/com/gongu/server/domain/payment/service/PaymentReconcilerTest.java`

**Interfaces:**
- Produces: `PaymentReconciler.completePayment(String paymentId, PaymentHistoryTrigger trigger, PortOnePaymentResult prefetchedResult)` → `VerifyPaymentResponse`. `prefetchedResult`가 `null`이 아니면 그 값을 쓰고, `null`이면 메서드 내부에서 직접 `portOneClient.getPayment(paymentId)`를 호출한다(기존 동작과 100% 동일 — 이 파라미터를 안 쓰는 모든 케이스가 오늘의 `completePayment`와 정확히 같게 동작해야 한다).

이 태스크는 `PaymentService`를 전혀 건드리지 않는다 — `PaymentReconciler`는 아직 아무 데서도 호출되지 않는 순수 추가 파일이다. 컴파일은 되지만 아직 실제 확정 경로에 연결되지 않은 상태로, Task 2에서 연결한다. 이렇게 나누는 이유: 새 클래스의 로직 자체가 옳은지(기존 15개 시나리오 전부 보존)를 `PaymentService` 배선 변경과 분리해서 검증하기 위해서다.

- [ ] **Step 1: `PaymentReconciler.java` 작성**

```java
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
 * 별도 빈으로 분리한 이유: 코디네이터가 같은 클래스 안에서 self-invocation으로 이
 * 메서드를 부르면 Spring 프록시 기반 @Transactional이 무시되기 때문이다
 * (PaymentExpireReconciler, #215와 동일한 이유).
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

    @Transactional(noRollbackFor = {BusinessException.class, InfraException.class})
    public VerifyPaymentResponse completePayment(String paymentId, PaymentHistoryTrigger trigger,
                                                  PortOnePaymentResult prefetchedResult) {
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
        if (prefetchedResult != null) {
            portOneResult = prefetchedResult;
        } else {
            try {
                portOneResult = portOneClient.getPayment(paymentId);
            } catch (InfraException e) {
                // 판정 불가 — PG 조회 실패. 상태를 바꾸지 않고 PENDING을 유지해
                // 웹훅/사용자 재시도가 정상 확정에 도달할 수 있게 한다.
                log.warn("PortOne 조회 실패 — payment PENDING 유지, 재시도 대기: paymentId={}", paymentId, e);
                paymentFailedPgErrorCounter.increment();
                throw e;
            }
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

        if (!expectedAmount.equals(actualAmount)) {
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

        // 재고 확인·차감을 order.pay()/payment.confirm()보다 먼저 수행한다 (#230).
        // 이전 순서(상태 확정 → 재고 차감)는 재고 부족 예외가 noRollbackFor라 롤백되지 않고
        // 결제·주문이 PAID로 커밋되는 버그가 있었다 — PG는 결제됐는데 재고는 안 깎이고 환불도 없는 상태.
        // 항목이 여러 개여도 "전 항목 확인 → 전 항목 확정" 두 단계로 나눠, 앞 항목만 차감된 채
        // 뒤 항목에서 재고 부족이 나는 부분 커밋을 만들지 않는다.
        record LockedOrderItem(Product product, int quantity) {}

        List<OrderItem> items = orderItemRepository.findAllByOrder(order);
        List<LockedOrderItem> lockedItems = items.stream()
                .map(item -> new LockedOrderItem(
                        productRepository.findByIdWithLock(item.getProduct().getId())
                                .orElseThrow(() -> new BusinessException(ProductErrorCode.PRODUCT_NOT_FOUND)),
                        Math.toIntExact(item.getQuantity())))
                .toList();

        boolean stockInsufficient = lockedItems.stream()
                .anyMatch(locked -> locked.product().getRemainingStock() < locked.quantity());

        if (stockInsufficient) {
            PaymentStatus beforeInsufficient = payment.getStatus();
            PgCancelOutcome cancelOutcome = executePGCancel(paymentId, "재고 부족으로 인한 자동 환불");
            payment.refund();
            paymentHistoryRecorder.record(payment, beforeInsufficient, payment.getStatus(), trigger,
                    "재고 부족으로 인한 자동 환불",
                    cancelOutcome.rawBody() != null ? cancelOutcome.rawBody() : portOneResult.rawBody());
            paymentFailedInsufficientStockCounter.increment();
            order.cancel("재고 부족으로 인한 자동 환불");
            items.forEach(item ->
                    stockRedisService.releaseStockAfterCommit(item.getProduct().getId(), Math.toIntExact(item.getQuantity()))
            );
            throw new BusinessException(PaymentErrorCode.PAYMENT_INSUFFICIENT_STOCK_REFUNDED);
        }

        PaymentStatus beforeConfirm = payment.getStatus();
        order.pay();
        payment.confirm(actualAmount, portOneResponse.paidAt().toLocalDateTime());
        paymentHistoryRecorder.record(payment, beforeConfirm, payment.getStatus(), trigger,
                null, portOneResult.rawBody());

        lockedItems.forEach(locked -> locked.product().confirmStock(locked.quantity()));

        paymentCompletedCounter.increment();
        return VerifyPaymentResponse.of(order, payment);
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
```

- [ ] **Step 2: `PaymentReconcilerTest.java` 작성**

기존 `PaymentServiceTest.java`의 `completePayment_*` 테스트 15개를 그대로 옮기되, 기계적으로 딱 두 가지만 바꾼다:
1. 대상 클래스를 `PaymentService` → `PaymentReconciler`로, 생성자를 이 태스크의 필드 목록(위 Step 1과 동일 순서)에 맞춘다. `userRepository`는 `PaymentReconciler`에 없으므로 제외한다.
2. 모든 `paymentService.completePayment(PAYMENT_ID, PaymentHistoryTrigger.CLIENT_VERIFY)` 호출을 `reconciler.completePayment(PAYMENT_ID, PaymentHistoryTrigger.CLIENT_VERIFY, null)`로 바꾼다(세 번째 인자로 `null`을 추가 — "코디네이터가 선조회를 안 했다"는 뜻이고, 이 경우 `PaymentReconciler`가 직접 `portOneClient.getPayment`를 호출하는 것이 Step 1 코드의 동작이다. 기존 테스트는 전부 이 경로를 검증하던 것이므로 어서션은 **하나도 바뀌지 않는다**).

전체 파일:

```java
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
import com.gongu.server.global.exception.InfraException;
import com.gongu.server.global.exception.errorcode.PaymentErrorCode;
import com.gongu.server.global.infrastructure.portone.PortOneClient;
import com.gongu.server.global.infrastructure.portone.dto.PortOnePaymentResponse;
import com.gongu.server.global.infrastructure.portone.dto.PortOnePaymentResult;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class PaymentReconcilerTest {

    @Mock
    private OrderRepository orderRepository;

    @Mock
    private OrderItemRepository orderItemRepository;

    @Mock
    private ProductRepository productRepository;

    @Mock
    private StockRedisService stockRedisService;

    @Mock
    private PaymentRepository paymentRepository;

    @Mock
    private PortOneClient portOneClient;

    @Mock
    private PaymentHistoryRecorder paymentHistoryRecorder;

    private Counter paymentCompletedCounter;
    private Counter paymentFailedOrderExpiredIdempotentCounter;
    private Counter paymentFailedOrderExpiredCancelCounter;
    private Counter paymentFailedPgErrorCounter;
    private Counter paymentFailedPgNullCounter;
    private Counter paymentFailedPgStatusMismatchCounter;
    private Counter paymentFailedAmountMismatchCounter;
    private Counter paymentFailedInsufficientStockCounter;

    private PaymentReconciler reconciler;

    private Order order;

    private static final Long ORDER_ID = 1L;
    private static final Long AMOUNT = 10_000L;
    private static final String PAYMENT_ID = "pay-uuid-001";

    @BeforeEach
    void setUp() {
        SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
        paymentCompletedCounter = Counter.builder("gongu.payment.completed").register(meterRegistry);
        paymentFailedOrderExpiredIdempotentCounter = paymentFailedCounter(meterRegistry, "order_expired_idempotent");
        paymentFailedOrderExpiredCancelCounter = paymentFailedCounter(meterRegistry, "order_expired_cancel");
        paymentFailedPgErrorCounter = paymentFailedCounter(meterRegistry, "pg_error");
        paymentFailedPgNullCounter = paymentFailedCounter(meterRegistry, "pg_null_response");
        paymentFailedPgStatusMismatchCounter = paymentFailedCounter(meterRegistry, "pg_status_mismatch");
        paymentFailedAmountMismatchCounter = paymentFailedCounter(meterRegistry, "amount_mismatch");
        paymentFailedInsufficientStockCounter = paymentFailedCounter(meterRegistry, "insufficient_stock");
        reconciler = new PaymentReconciler(
                orderRepository, orderItemRepository, productRepository, paymentRepository,
                stockRedisService, portOneClient, paymentHistoryRecorder,
                paymentCompletedCounter,
                paymentFailedOrderExpiredIdempotentCounter,
                paymentFailedOrderExpiredCancelCounter,
                paymentFailedPgErrorCounter,
                paymentFailedPgNullCounter,
                paymentFailedPgStatusMismatchCounter,
                paymentFailedAmountMismatchCounter,
                paymentFailedInsufficientStockCounter
        );

        order = Mockito.mock(Order.class);
        org.mockito.Mockito.lenient().when(order.getId()).thenReturn(ORDER_ID);
        org.mockito.Mockito.lenient().when(order.getTotalPrice()).thenReturn(AMOUNT);
    }

    private Counter paymentFailedCounter(SimpleMeterRegistry meterRegistry, String reason) {
        return Counter.builder("gongu.payment.failed")
                .tag("reason", reason)
                .register(meterRegistry);
    }

    @Test
    @DisplayName("completePayment_성공_금액일치")
    void completePayment_성공_금액일치() {
        // given
        Payment payment = Mockito.mock(Payment.class);
        given(paymentRepository.findByMerchantUidWithLock(PAYMENT_ID)).willReturn(Optional.of(payment));
        given(payment.getStatus()).willReturn(PaymentStatus.PENDING);
        given(payment.getOrder()).willReturn(order);
        given(payment.getMerchantUid()).willReturn(PAYMENT_ID);
        given(payment.getAmount()).willReturn(AMOUNT);
        given(payment.getPaidAt()).willReturn(LocalDateTime.now());
        given(order.getStatus()).willReturn(OrderStatus.RESERVED);
        given(orderRepository.findByIdWithLock(ORDER_ID)).willReturn(Optional.of(order));

        PortOnePaymentResponse portOneResponse = new PortOnePaymentResponse(
                PAYMENT_ID, "PAID", new PortOnePaymentResponse.Amount(AMOUNT), OffsetDateTime.now());
        String rawBody = "{\"id\":\"" + PAYMENT_ID + "\",\"status\":\"PAID\"}";
        given(portOneClient.getPayment(PAYMENT_ID)).willReturn(new PortOnePaymentResult(portOneResponse, rawBody));
        OrderItem orderItem = Mockito.mock(OrderItem.class);
        Product orderProduct = Mockito.mock(Product.class);
        Product lockedProduct = Mockito.mock(Product.class);
        given(orderItemRepository.findAllByOrder(order)).willReturn(List.of(orderItem));
        given(orderItem.getProduct()).willReturn(orderProduct);
        given(orderProduct.getId()).willReturn(1L);
        given(orderItem.getQuantity()).willReturn(2L);
        given(productRepository.findByIdWithLock(1L)).willReturn(Optional.of(lockedProduct));
        given(lockedProduct.getRemainingStock()).willReturn(10);

        // when
        VerifyPaymentResponse result = reconciler.completePayment(PAYMENT_ID, PaymentHistoryTrigger.CLIENT_VERIFY, null);

        // then
        assertThat(result).isNotNull();
        assertThat(result.paymentId()).isEqualTo(PAYMENT_ID);
        assertThat(result.amount()).isEqualTo(AMOUNT);
        InOrder inOrder = Mockito.inOrder(order, payment);
        inOrder.verify(order).pay();
        inOrder.verify(payment).confirm(eq(AMOUNT), any(LocalDateTime.class));
        verify(productRepository).findByIdWithLock(1L);
        verify(lockedProduct).confirmStock(2);
    }

    @Test
    @DisplayName("completePayment_prefetched_결과를_그대로_사용하면_PG를_다시_호출하지_않는다")
    void completePayment_prefetched_결과_사용() {
        // given
        Payment payment = Mockito.mock(Payment.class);
        given(paymentRepository.findByMerchantUidWithLock(PAYMENT_ID)).willReturn(Optional.of(payment));
        given(payment.getStatus()).willReturn(PaymentStatus.PENDING);
        given(payment.getOrder()).willReturn(order);
        given(payment.getMerchantUid()).willReturn(PAYMENT_ID);
        given(payment.getAmount()).willReturn(AMOUNT);
        given(payment.getPaidAt()).willReturn(LocalDateTime.now());
        given(order.getStatus()).willReturn(OrderStatus.RESERVED);
        given(orderRepository.findByIdWithLock(ORDER_ID)).willReturn(Optional.of(order));

        PortOnePaymentResponse portOneResponse = new PortOnePaymentResponse(
                PAYMENT_ID, "PAID", new PortOnePaymentResponse.Amount(AMOUNT), OffsetDateTime.now());
        String rawBody = "{\"id\":\"" + PAYMENT_ID + "\",\"status\":\"PAID\"}";
        PortOnePaymentResult prefetched = new PortOnePaymentResult(portOneResponse, rawBody);
        OrderItem orderItem = Mockito.mock(OrderItem.class);
        Product orderProduct = Mockito.mock(Product.class);
        Product lockedProduct = Mockito.mock(Product.class);
        given(orderItemRepository.findAllByOrder(order)).willReturn(List.of(orderItem));
        given(orderItem.getProduct()).willReturn(orderProduct);
        given(orderProduct.getId()).willReturn(1L);
        given(orderItem.getQuantity()).willReturn(2L);
        given(productRepository.findByIdWithLock(1L)).willReturn(Optional.of(lockedProduct));
        given(lockedProduct.getRemainingStock()).willReturn(10);

        // when
        VerifyPaymentResponse result = reconciler.completePayment(PAYMENT_ID, PaymentHistoryTrigger.CLIENT_VERIFY, prefetched);

        // then
        assertThat(result).isNotNull();
        verify(order).pay();
        verify(portOneClient, never()).getPayment(anyString());
    }

    @Test
    @DisplayName("completePayment_멱등_이미PAID")
    void completePayment_멱등_이미PAID() {
        // given
        Payment payment = Mockito.mock(Payment.class);
        given(paymentRepository.findByMerchantUidWithLock(PAYMENT_ID)).willReturn(Optional.of(payment));
        given(payment.getStatus()).willReturn(PaymentStatus.PAID);
        given(payment.getOrder()).willReturn(order);
        given(payment.getMerchantUid()).willReturn(PAYMENT_ID);
        given(payment.getAmount()).willReturn(AMOUNT);
        given(payment.getPaidAt()).willReturn(LocalDateTime.now());
        given(order.getStatus()).willReturn(OrderStatus.PAID);

        // when
        VerifyPaymentResponse result = reconciler.completePayment(PAYMENT_ID, PaymentHistoryTrigger.CLIENT_VERIFY, null);

        // then
        assertThat(result).isNotNull();
        assertThat(result.paymentId()).isEqualTo(PAYMENT_ID);
        verify(portOneClient, never()).getPayment(any());
    }

    @Test
    @DisplayName("completePayment_Payment_없음")
    void completePayment_Payment_없음() {
        // given
        given(paymentRepository.findByMerchantUidWithLock(PAYMENT_ID)).willReturn(Optional.empty());

        // when & then
        assertThatThrownBy(() -> reconciler.completePayment(PAYMENT_ID, PaymentHistoryTrigger.CLIENT_VERIFY, null))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode())
                        .isEqualTo(PaymentErrorCode.PAYMENT_NOT_FOUND));
    }

    @Test
    @DisplayName("completePayment_상태_PENDING_아님")
    void completePayment_상태_PENDING_아님() {
        // given
        Payment payment = Mockito.mock(Payment.class);
        given(paymentRepository.findByMerchantUidWithLock(PAYMENT_ID)).willReturn(Optional.of(payment));
        given(payment.getStatus()).willReturn(PaymentStatus.FAILED);
        given(payment.getOrder()).willReturn(order);
        given(order.getStatus()).willReturn(OrderStatus.RESERVED);
        given(orderRepository.findByIdWithLock(ORDER_ID)).willReturn(Optional.of(order));

        // when & then
        assertThatThrownBy(() -> reconciler.completePayment(PAYMENT_ID, PaymentHistoryTrigger.CLIENT_VERIFY, null))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode())
                        .isEqualTo(PaymentErrorCode.PAYMENT_INVALID_STATE_TRANSITION));

        verify(portOneClient, never()).getPayment(any());
    }

    @Test
    @DisplayName("completePayment_PG조회_InfraException_전파 — payment는 PENDING 유지 (fail 미호출)")
    void completePayment_PortOne_InfraException() {
        // given
        Payment payment = Mockito.mock(Payment.class);
        given(paymentRepository.findByMerchantUidWithLock(PAYMENT_ID)).willReturn(Optional.of(payment));
        given(payment.getStatus()).willReturn(PaymentStatus.PENDING);
        given(payment.getOrder()).willReturn(order);
        given(orderRepository.findByIdWithLock(ORDER_ID)).willReturn(Optional.of(order));
        given(portOneClient.getPayment(PAYMENT_ID))
                .willThrow(new InfraException(PaymentErrorCode.PAYMENT_PG_UNAVAILABLE));

        // when & then
        assertThatThrownBy(() -> reconciler.completePayment(PAYMENT_ID, PaymentHistoryTrigger.CLIENT_VERIFY, null))
                .isInstanceOf(InfraException.class);

        verify(payment, never()).fail();
    }

    @Test
    @DisplayName("completePayment_PG_빈응답 — PAYMENT_PG_UNAVAILABLE + payment는 PENDING 유지 (fail 미호출)")
    void completePayment_PG_빈응답_PENDING_유지() {
        // given
        Payment payment = Mockito.mock(Payment.class);
        given(paymentRepository.findByMerchantUidWithLock(PAYMENT_ID)).willReturn(Optional.of(payment));
        given(payment.getStatus()).willReturn(PaymentStatus.PENDING);
        given(payment.getOrder()).willReturn(order);
        given(orderRepository.findByIdWithLock(ORDER_ID)).willReturn(Optional.of(order));
        given(portOneClient.getPayment(PAYMENT_ID)).willReturn(null);

        // when & then
        assertThatThrownBy(() -> reconciler.completePayment(PAYMENT_ID, PaymentHistoryTrigger.CLIENT_VERIFY, null))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode())
                        .isEqualTo(PaymentErrorCode.PAYMENT_PG_UNAVAILABLE));

        verify(payment, never()).fail();
    }

    @Test
    @DisplayName("completePayment_조회실패후_재시도시_정상확정 — 1회차 InfraException(PENDING 유지), 2회차 PAID로 수렴")
    void completePayment_조회실패_재시도_정상확정() {
        // given — 실제 Payment 엔티티로 상태 전이를 검증한다 (mock 고정 stub이 아님)
        Payment payment = Payment.initiate(order, "idem-key-207", PAYMENT_ID, AMOUNT);
        given(paymentRepository.findByMerchantUidWithLock(PAYMENT_ID)).willReturn(Optional.of(payment));
        given(order.getStatus()).willReturn(OrderStatus.RESERVED);
        given(orderRepository.findByIdWithLock(ORDER_ID)).willReturn(Optional.of(order));

        PortOnePaymentResponse paidResponse = new PortOnePaymentResponse(
                PAYMENT_ID, "PAID", new PortOnePaymentResponse.Amount(AMOUNT), OffsetDateTime.now());
        String rawBody = "{\"id\":\"" + PAYMENT_ID + "\",\"status\":\"PAID\"}";
        given(portOneClient.getPayment(PAYMENT_ID))
                .willThrow(new InfraException(PaymentErrorCode.PAYMENT_PG_UNAVAILABLE))
                .willReturn(new PortOnePaymentResult(paidResponse, rawBody));
        // 참고: 원본 테스트(PaymentServiceTest.completePayment_조회실패_재시도_정상확정)와 동일하게
        // order.getStatus()를 명시적으로 stub한다 (setUp()의 lenient 기본값과 값은 같지만, 원본 기준을 그대로 따름).

        OrderItem orderItem = Mockito.mock(OrderItem.class);
        Product orderProduct = Mockito.mock(Product.class);
        Product lockedProduct = Mockito.mock(Product.class);
        given(orderItemRepository.findAllByOrder(order)).willReturn(List.of(orderItem));
        given(orderItem.getProduct()).willReturn(orderProduct);
        given(orderProduct.getId()).willReturn(1L);
        given(orderItem.getQuantity()).willReturn(2L);
        given(productRepository.findByIdWithLock(1L)).willReturn(Optional.of(lockedProduct));
        given(lockedProduct.getRemainingStock()).willReturn(10);

        // when — 1회차: InfraException 전파, payment는 PENDING 그대로
        assertThatThrownBy(() -> reconciler.completePayment(PAYMENT_ID, PaymentHistoryTrigger.CLIENT_VERIFY, null))
                .isInstanceOf(InfraException.class);
        assertThat(payment.getStatus()).isEqualTo(PaymentStatus.PENDING);

        // when — 2회차: PG 복구 후 정상 확정으로 수렴
        VerifyPaymentResponse result = reconciler.completePayment(PAYMENT_ID, PaymentHistoryTrigger.CLIENT_VERIFY, null);

        // then
        assertThat(result).isNotNull();
        assertThat(payment.getStatus()).isEqualTo(PaymentStatus.PAID);
        verify(order).pay();
        verify(lockedProduct).confirmStock(2);
    }

    @Test
    @DisplayName("completePayment_PortOne_status_미완료")
    void completePayment_PortOne_status_미완료() {
        // given
        Payment payment = Mockito.mock(Payment.class);
        given(paymentRepository.findByMerchantUidWithLock(PAYMENT_ID)).willReturn(Optional.of(payment));
        given(payment.getStatus()).willReturn(PaymentStatus.PENDING);
        given(payment.getOrder()).willReturn(order);
        given(orderRepository.findByIdWithLock(ORDER_ID)).willReturn(Optional.of(order));

        PortOnePaymentResponse portOneResponse = new PortOnePaymentResponse(
                PAYMENT_ID, "FAILED", new PortOnePaymentResponse.Amount(AMOUNT), OffsetDateTime.now());
        String rawBody = "{\"id\":\"" + PAYMENT_ID + "\",\"status\":\"FAILED\"}";
        given(portOneClient.getPayment(PAYMENT_ID)).willReturn(new PortOnePaymentResult(portOneResponse, rawBody));

        // when & then
        assertThatThrownBy(() -> reconciler.completePayment(PAYMENT_ID, PaymentHistoryTrigger.CLIENT_VERIFY, null))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode())
                        .isEqualTo(PaymentErrorCode.PAYMENT_NOT_COMPLETED));

        verify(payment).fail();
    }

    @Test
    @DisplayName("completePayment_금액불일치_보상처리")
    void completePayment_금액불일치_보상처리() {
        // given
        Payment payment = Mockito.mock(Payment.class);
        given(paymentRepository.findByMerchantUidWithLock(PAYMENT_ID)).willReturn(Optional.of(payment));
        given(payment.getStatus()).willReturn(PaymentStatus.PENDING);
        given(payment.getOrder()).willReturn(order);
        given(orderRepository.findByIdWithLock(ORDER_ID)).willReturn(Optional.of(order));

        Long mismatchAmount = 5_000L;
        PortOnePaymentResponse portOneResponse = new PortOnePaymentResponse(
                PAYMENT_ID, "PAID", new PortOnePaymentResponse.Amount(mismatchAmount), OffsetDateTime.now());
        String rawBody = "{\"id\":\"" + PAYMENT_ID + "\",\"status\":\"PAID\"}";
        given(portOneClient.getPayment(PAYMENT_ID)).willReturn(new PortOnePaymentResult(portOneResponse, rawBody));
        OrderItem orderItem = Mockito.mock(OrderItem.class);
        Product orderProduct = Mockito.mock(Product.class);
        given(orderItemRepository.findAllByOrder(order)).willReturn(List.of(orderItem));
        given(orderItem.getProduct()).willReturn(orderProduct);
        given(orderProduct.getId()).willReturn(1L);
        given(orderItem.getQuantity()).willReturn(2L);

        // when & then
        assertThatThrownBy(() -> reconciler.completePayment(PAYMENT_ID, PaymentHistoryTrigger.CLIENT_VERIFY, null))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode())
                        .isEqualTo(PaymentErrorCode.PAYMENT_AMOUNT_MISMATCH));

        verify(payment).refund();
        verify(order).cancel(anyString());
        verify(portOneClient).cancelPayment(eq(PAYMENT_ID), anyString());
        verify(stockRedisService).releaseStockAfterCommit(1L, 2);
    }

    @Test
    @DisplayName("completePayment_재고부족_보상처리")
    void completePayment_재고부족_보상처리() {
        // given
        Payment payment = Mockito.mock(Payment.class);
        given(paymentRepository.findByMerchantUidWithLock(PAYMENT_ID)).willReturn(Optional.of(payment));
        given(payment.getStatus()).willReturn(PaymentStatus.PENDING);
        given(payment.getOrder()).willReturn(order);
        given(orderRepository.findByIdWithLock(ORDER_ID)).willReturn(Optional.of(order));

        PortOnePaymentResponse portOneResponse = new PortOnePaymentResponse(
                PAYMENT_ID, "PAID", new PortOnePaymentResponse.Amount(AMOUNT), OffsetDateTime.now());
        String rawBody = "{\"id\":\"" + PAYMENT_ID + "\",\"status\":\"PAID\"}";
        given(portOneClient.getPayment(PAYMENT_ID)).willReturn(new PortOnePaymentResult(portOneResponse, rawBody));

        OrderItem orderItem = Mockito.mock(OrderItem.class);
        Product orderProduct = Mockito.mock(Product.class);
        Product lockedProduct = Mockito.mock(Product.class);
        given(orderItemRepository.findAllByOrder(order)).willReturn(List.of(orderItem));
        given(orderItem.getProduct()).willReturn(orderProduct);
        given(orderProduct.getId()).willReturn(1L);
        given(orderItem.getQuantity()).willReturn(2L);
        given(productRepository.findByIdWithLock(1L)).willReturn(Optional.of(lockedProduct));
        given(lockedProduct.getRemainingStock()).willReturn(1);

        // when & then
        assertThatThrownBy(() -> reconciler.completePayment(PAYMENT_ID, PaymentHistoryTrigger.CLIENT_VERIFY, null))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode())
                        .isEqualTo(PaymentErrorCode.PAYMENT_INSUFFICIENT_STOCK_REFUNDED));

        verify(order, never()).pay();
        verify(payment, never()).confirm(any(), any());
        verify(lockedProduct, never()).confirmStock(anyInt());
        verify(payment).refund();
        verify(order).cancel(anyString());
        verify(portOneClient).cancelPayment(eq(PAYMENT_ID), anyString());
        verify(stockRedisService).releaseStockAfterCommit(1L, 2);
    }

    @Test
    @DisplayName("completePayment_재고부족_PG취소실패시_상태불변_InfraException_전파")
    void completePayment_재고부족_PG취소실패_상태불변() {
        // given
        Payment payment = Mockito.mock(Payment.class);
        given(paymentRepository.findByMerchantUidWithLock(PAYMENT_ID)).willReturn(Optional.of(payment));
        given(payment.getStatus()).willReturn(PaymentStatus.PENDING);
        given(payment.getOrder()).willReturn(order);
        given(orderRepository.findByIdWithLock(ORDER_ID)).willReturn(Optional.of(order));

        PortOnePaymentResponse portOneResponse = new PortOnePaymentResponse(
                PAYMENT_ID, "PAID", new PortOnePaymentResponse.Amount(AMOUNT), OffsetDateTime.now());
        String rawBody = "{\"id\":\"" + PAYMENT_ID + "\",\"status\":\"PAID\"}";
        given(portOneClient.getPayment(PAYMENT_ID)).willReturn(new PortOnePaymentResult(portOneResponse, rawBody));

        OrderItem orderItem = Mockito.mock(OrderItem.class);
        Product orderProduct = Mockito.mock(Product.class);
        Product lockedProduct = Mockito.mock(Product.class);
        given(orderItemRepository.findAllByOrder(order)).willReturn(List.of(orderItem));
        given(orderItem.getProduct()).willReturn(orderProduct);
        given(orderProduct.getId()).willReturn(1L);
        given(orderItem.getQuantity()).willReturn(2L);
        given(productRepository.findByIdWithLock(1L)).willReturn(Optional.of(lockedProduct));
        given(lockedProduct.getRemainingStock()).willReturn(1);

        given(portOneClient.cancelPayment(eq(PAYMENT_ID), anyString()))
                .willThrow(new InfraException(PaymentErrorCode.PAYMENT_PG_UNAVAILABLE));

        // when & then
        assertThatThrownBy(() -> reconciler.completePayment(PAYMENT_ID, PaymentHistoryTrigger.CLIENT_VERIFY, null))
                .isInstanceOf(InfraException.class);

        verify(payment, never()).refund();
        verify(order, never()).cancel(anyString());
        verify(stockRedisService, never()).releaseStockAfterCommit(any(), anyInt());
    }

    @Test
    @DisplayName("completePayment_ORDER_EXPIRED_환불_성공")
    void completePayment_ORDER_EXPIRED_환불_성공() {
        // given
        Payment payment = Mockito.mock(Payment.class);
        given(paymentRepository.findByMerchantUidWithLock(PAYMENT_ID)).willReturn(Optional.of(payment));
        given(payment.getStatus()).willReturn(PaymentStatus.PENDING);
        given(payment.getOrder()).willReturn(order);
        given(order.getStatus()).willReturn(OrderStatus.CANCELLED);
        given(orderRepository.findByIdWithLock(ORDER_ID)).willReturn(Optional.of(order));
        given(portOneClient.cancelPayment(eq(PAYMENT_ID), anyString()))
                .willReturn(Mockito.mock(PortOnePaymentResult.class));

        // when & then
        assertThatThrownBy(() -> reconciler.completePayment(PAYMENT_ID, PaymentHistoryTrigger.CLIENT_VERIFY, null))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode())
                        .isEqualTo(PaymentErrorCode.ORDER_EXPIRED_REFUNDED));

        verify(portOneClient).cancelPayment(eq(PAYMENT_ID), anyString());
        verify(payment).refund();
    }

    @Test
    @DisplayName("completePayment_ORDER_EXPIRED_PG결제없음_환불미호출")
    void completePayment_ORDER_EXPIRED_PG결제없음_환불미호출() {
        // given
        Payment payment = Mockito.mock(Payment.class);
        given(paymentRepository.findByMerchantUidWithLock(PAYMENT_ID)).willReturn(Optional.of(payment));
        given(payment.getStatus()).willReturn(PaymentStatus.PENDING);
        given(payment.getOrder()).willReturn(order);
        given(order.getStatus()).willReturn(OrderStatus.CANCELLED);
        given(orderRepository.findByIdWithLock(ORDER_ID)).willReturn(Optional.of(order));
        given(portOneClient.cancelPayment(eq(PAYMENT_ID), anyString()))
                .willThrow(new BusinessException(PaymentErrorCode.PAYMENT_NOT_FOUND));

        // when & then
        assertThatThrownBy(() -> reconciler.completePayment(PAYMENT_ID, PaymentHistoryTrigger.CLIENT_VERIFY, null))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode())
                        .isEqualTo(PaymentErrorCode.ORDER_EXPIRED_REFUNDED));

        verify(portOneClient).cancelPayment(eq(PAYMENT_ID), anyString());
        verify(payment, never()).refund();
        verify(payment).expire();
    }

    @Test
    @DisplayName("completePayment_CANCELLED_Payment_ORDER_EXPIRED_환불_성공")
    void completePayment_CANCELLED_Payment_ORDER_EXPIRED_환불_성공() {
        // given
        Payment payment = Mockito.mock(Payment.class);
        given(paymentRepository.findByMerchantUidWithLock(PAYMENT_ID)).willReturn(Optional.of(payment));
        given(payment.getStatus()).willReturn(PaymentStatus.CANCELLED);
        given(payment.getOrder()).willReturn(order);
        given(order.getStatus()).willReturn(OrderStatus.CANCELLED);
        given(orderRepository.findByIdWithLock(ORDER_ID)).willReturn(Optional.of(order));
        given(portOneClient.cancelPayment(eq(PAYMENT_ID), anyString()))
                .willReturn(Mockito.mock(PortOnePaymentResult.class));

        // when & then
        assertThatThrownBy(() -> reconciler.completePayment(PAYMENT_ID, PaymentHistoryTrigger.CLIENT_VERIFY, null))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode())
                        .isEqualTo(PaymentErrorCode.ORDER_EXPIRED_REFUNDED));

        verify(portOneClient).cancelPayment(eq(PAYMENT_ID), anyString());
        verify(payment).refund();
    }

    @Test
    @DisplayName("completePayment_REFUNDED_CANCELLED_Order_멱등_ORDER_EXPIRED_REFUNDED")
    void completePayment_REFUNDED_CANCELLED_Order_멱등_ORDER_EXPIRED_REFUNDED() {
        // given
        Payment payment = Mockito.mock(Payment.class);
        given(paymentRepository.findByMerchantUidWithLock(PAYMENT_ID)).willReturn(Optional.of(payment));
        given(payment.getStatus()).willReturn(PaymentStatus.REFUNDED);
        given(payment.getOrder()).willReturn(order);
        given(order.getStatus()).willReturn(OrderStatus.CANCELLED);
        given(orderRepository.findByIdWithLock(ORDER_ID)).willReturn(Optional.of(order));

        // when & then
        assertThatThrownBy(() -> reconciler.completePayment(PAYMENT_ID, PaymentHistoryTrigger.CLIENT_VERIFY, null))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode())
                        .isEqualTo(PaymentErrorCode.ORDER_EXPIRED_REFUNDED));

        verify(portOneClient, never()).cancelPayment(anyString(), anyString());
        verify(payment, never()).refund();
    }

    @Test
    @DisplayName("completePayment_ORDER_EXPIRED_서킷오픈_InfraException_전파")
    void completePayment_ORDER_EXPIRED_서킷오픈_InfraException_전파() {
        // given
        Payment payment = Mockito.mock(Payment.class);
        given(paymentRepository.findByMerchantUidWithLock(PAYMENT_ID)).willReturn(Optional.of(payment));
        given(payment.getStatus()).willReturn(PaymentStatus.PENDING);
        given(payment.getOrder()).willReturn(order);
        given(order.getStatus()).willReturn(OrderStatus.CANCELLED);
        given(orderRepository.findByIdWithLock(ORDER_ID)).willReturn(Optional.of(order));
        given(portOneClient.cancelPayment(eq(PAYMENT_ID), anyString()))
                .willThrow(new InfraException(PaymentErrorCode.PAYMENT_PG_UNAVAILABLE));

        // when & then
        assertThatThrownBy(() -> reconciler.completePayment(PAYMENT_ID, PaymentHistoryTrigger.CLIENT_VERIFY, null))
                .isInstanceOf(InfraException.class);

        verify(payment, never()).refund();
    }
}
```

- [ ] **Step 3: 컴파일 및 테스트 확인**

Run: `./gradlew test --tests "*PaymentReconcilerTest*" -q`
Expected: PASS — 15개 전부

- [ ] **Step 4: 전체 컴파일 확인 (기존 PaymentService/PaymentServiceTest는 아직 안 건드렸으니 그대로 통과해야 한다)**

Run: `./gradlew compileJava compileTestJava -q`
Expected: 성공

- [ ] **Step 5: 커밋**

```bash
git add src/main/java/com/gongu/server/domain/payment/service/PaymentReconciler.java src/test/java/com/gongu/server/domain/payment/service/PaymentReconcilerTest.java
git commit -m "feat: PaymentReconciler 신설 — completePayment 로직 이관, PG 선조회 결과 수용 가능하게 (#146)"
```

---

### Task 2: `PaymentService.completePayment`를 코디네이터로 재작성 + 배선 전환

**Files:**
- Modify: `src/main/java/com/gongu/server/domain/payment/service/PaymentService.java`
- Modify: `src/test/java/com/gongu/server/domain/payment/service/PaymentServiceTest.java`

**Interfaces:**
- Consumes: `PaymentReconciler.completePayment(String, PaymentHistoryTrigger, PortOnePaymentResult)` (Task 1)
- Produces: `PaymentService.completePayment(String paymentId, PaymentHistoryTrigger trigger)` — **외부 시그니처는 변경 없음**(`PaymentController`, `PaymentExpireService` 등 기존 호출부는 전혀 안 건드린다).

- [ ] **Step 1: 실패하는 테스트로 `PaymentServiceTest.java` 교체**

`src/test/java/com/gongu/server/domain/payment/service/PaymentServiceTest.java`에서 `completePayment_*` 테스트 15개(277번째 줄 `completePayment_성공_금액일치`부터 파일 끝 723번째 줄까지)를 전부 삭제한다 — 이 테스트들은 Task 1에서 이미 `PaymentReconcilerTest`로 옮겨졌다. 대신 그 자리에 코디네이터 전용 테스트로 교체한다.

파일 상단 필드 선언부(52-94번째 줄 부근, `@Mock private OrderItemRepository orderItemRepository;`부터 `private Counter paymentFailedAmountMismatchCounter;`까지의 이제 필요 없는 필드들)와 `setUp()`의 `paymentService = new PaymentService(...)` 생성자 호출을 아래처럼 바꾼다.

전체 파일(276번째 줄까지의 `preparePayment_*`/`validateOwnership_*` 테스트는 원문 그대로 유지, 필드 선언과 `setUp()`과 `completePayment_*` 부분만 교체):

```java
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
import com.gongu.server.domain.user.entity.User;
import com.gongu.server.domain.user.repository.UserRepository;
import com.gongu.server.global.exception.BusinessException;
import com.gongu.server.global.exception.InfraException;
import com.gongu.server.global.exception.errorcode.PaymentErrorCode;
import com.gongu.server.global.exception.errorcode.UserErrorCode;
import com.gongu.server.global.infrastructure.portone.PortOneClient;
import com.gongu.server.global.infrastructure.portone.dto.PortOnePaymentResponse;
import com.gongu.server.global.infrastructure.portone.dto.PortOnePaymentResult;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class PaymentServiceTest {

    @Mock
    private UserRepository userRepository;

    @Mock
    private OrderRepository orderRepository;

    @Mock
    private PaymentRepository paymentRepository;

    @Mock
    private PortOneClient portOneClient;

    @Mock
    private PaymentReconciler reconciler;

    private Counter paymentFailedPgErrorCounter;

    private PaymentService paymentService;

    private User user;
    private Order order;

    private static final Long USER_ID = 1L;
    private static final Long ORDER_ID = 1L;
    private static final Long AMOUNT = 10_000L;
    private static final String PAYMENT_ID = "pay-uuid-001";

    @BeforeEach
    void setUp() {
        SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
        paymentFailedPgErrorCounter = Counter.builder("gongu.payment.failed")
                .tag("reason", "pg_error")
                .register(meterRegistry);
        paymentService = new PaymentService(
                userRepository, orderRepository, paymentRepository,
                portOneClient, paymentFailedPgErrorCounter, reconciler
        );

        user = Mockito.mock(User.class);
        org.mockito.Mockito.lenient().when(user.getId()).thenReturn(USER_ID);

        order = Mockito.mock(Order.class);
        org.mockito.Mockito.lenient().when(order.getId()).thenReturn(ORDER_ID);
        org.mockito.Mockito.lenient().when(order.getStatus()).thenReturn(OrderStatus.RESERVED);
        org.mockito.Mockito.lenient().when(order.getTotalPrice()).thenReturn(AMOUNT);
        org.mockito.Mockito.lenient().when(order.isOwnedBy(USER_ID)).thenReturn(true);
    }

    // ────────────────────────────────────────────────────────────
    // preparePayment
    // ────────────────────────────────────────────────────────────

    @Test
    @DisplayName("preparePayment_성공")
    void preparePayment_성공() {
        // given
        given(userRepository.findByIdAndDeletedAtIsNull(USER_ID)).willReturn(Optional.of(user));
        given(paymentRepository.existsByOrderIdAndStatusIn(eq(ORDER_ID), any(List.class))).willReturn(false);
        given(orderRepository.findByIdWithLock(ORDER_ID)).willReturn(Optional.of(order));
        Payment savedPayment = Mockito.mock(Payment.class);
        given(paymentRepository.save(any(Payment.class))).willReturn(savedPayment);

        // when
        PaymentPrepareResult result = paymentService.preparePayment(USER_ID, ORDER_ID);

        // then
        assertThat(result).isNotNull();
        assertThat(result.paymentId()).isNotNull().isNotEmpty();
        assertThat(result.amount()).isEqualTo(AMOUNT);
        verify(paymentRepository).save(any(Payment.class));
    }

    @Test
    @DisplayName("preparePayment_사용자_없음")
    void preparePayment_사용자_없음() {
        // given
        given(userRepository.findByIdAndDeletedAtIsNull(USER_ID)).willReturn(Optional.empty());

        // when & then
        assertThatThrownBy(() -> paymentService.preparePayment(USER_ID, ORDER_ID))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode())
                        .isEqualTo(UserErrorCode.USER_NOT_FOUND));

        verify(orderRepository, never()).findByIdWithLock(any());
    }

    @Test
    @DisplayName("preparePayment_활성결제_존재_예외")
    void preparePayment_활성결제_존재_예외() {
        // given
        given(userRepository.findByIdAndDeletedAtIsNull(USER_ID)).willReturn(Optional.of(user));
        given(orderRepository.findByIdWithLock(ORDER_ID)).willReturn(Optional.of(order));
        given(order.isOwnedBy(USER_ID)).willReturn(true);
        given(order.getStatus()).willReturn(OrderStatus.RESERVED);
        given(paymentRepository.existsByOrderIdAndStatusIn(eq(ORDER_ID), any(List.class))).willReturn(true);

        // when & then
        assertThatThrownBy(() -> paymentService.preparePayment(USER_ID, ORDER_ID))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode())
                        .isEqualTo(PaymentErrorCode.PAYMENT_ACTIVE_EXISTS));

        verify(paymentRepository, never()).save(any());
    }

    @Test
    @DisplayName("preparePayment_소유권_불일치")
    void preparePayment_소유권_불일치() {
        // given
        given(userRepository.findByIdAndDeletedAtIsNull(USER_ID)).willReturn(Optional.of(user));
        given(orderRepository.findByIdWithLock(ORDER_ID)).willReturn(Optional.of(order));
        given(order.isOwnedBy(USER_ID)).willReturn(false);

        // when & then
        assertThatThrownBy(() -> paymentService.preparePayment(USER_ID, ORDER_ID))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode())
                        .isEqualTo(PaymentErrorCode.PAYMENT_NOT_ALLOWED));

        verify(paymentRepository, never()).existsByOrderIdAndStatusIn(eq(ORDER_ID), any(List.class));
    }

    @Test
    @DisplayName("preparePayment_주문상태_비RESERVED")
    void preparePayment_주문상태_비RESERVED() {
        // given
        given(userRepository.findByIdAndDeletedAtIsNull(USER_ID)).willReturn(Optional.of(user));
        given(orderRepository.findByIdWithLock(ORDER_ID)).willReturn(Optional.of(order));
        given(order.getStatus()).willReturn(OrderStatus.PAID);

        // when & then
        assertThatThrownBy(() -> paymentService.preparePayment(USER_ID, ORDER_ID))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode())
                        .isEqualTo(PaymentErrorCode.PAYMENT_NOT_ALLOWED));

        verify(paymentRepository, never()).existsByOrderIdAndStatusIn(eq(ORDER_ID), any(List.class));
    }

    // ────────────────────────────────────────────────────────────
    // validateOwnership
    // ────────────────────────────────────────────────────────────

    @Test
    @DisplayName("validateOwnership_성공")
    void validateOwnership_성공() {
        // given
        Payment payment = Mockito.mock(Payment.class);
        given(paymentRepository.findByMerchantUid(PAYMENT_ID)).willReturn(Optional.of(payment));
        given(payment.getOrder()).willReturn(order);
        given(order.isOwnedBy(USER_ID)).willReturn(true);

        // when & then
        paymentService.validateOwnership(USER_ID, PAYMENT_ID);
    }

    @Test
    @DisplayName("validateOwnership_결제_없음")
    void validateOwnership_결제_없음() {
        // given
        given(paymentRepository.findByMerchantUid(PAYMENT_ID)).willReturn(Optional.empty());

        // when & then
        assertThatThrownBy(() -> paymentService.validateOwnership(USER_ID, PAYMENT_ID))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode())
                        .isEqualTo(PaymentErrorCode.PAYMENT_NOT_FOUND));
    }

    @Test
    @DisplayName("validateOwnership_소유권_불일치")
    void validateOwnership_소유권_불일치() {
        // given
        Payment payment = Mockito.mock(Payment.class);
        given(paymentRepository.findByMerchantUid(PAYMENT_ID)).willReturn(Optional.of(payment));
        given(payment.getOrder()).willReturn(order);
        given(order.isOwnedBy(USER_ID)).willReturn(false);

        // when & then
        assertThatThrownBy(() -> paymentService.validateOwnership(USER_ID, PAYMENT_ID))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode())
                        .isEqualTo(PaymentErrorCode.PAYMENT_NOT_ALLOWED));
    }

    // ────────────────────────────────────────────────────────────
    // completePayment — 코디네이터: 선조회 판단 + reconciler 위임
    // ────────────────────────────────────────────────────────────

    @Test
    @DisplayName("completePayment_PENDING_주문_RESERVED_PG_선조회_후_reconciler에_결과_전달")
    void completePayment_PENDING_선조회_후_위임() {
        // given
        Payment payment = Mockito.mock(Payment.class);
        given(paymentRepository.findByMerchantUid(PAYMENT_ID)).willReturn(Optional.of(payment));
        given(payment.getStatus()).willReturn(PaymentStatus.PENDING);
        given(payment.getOrder()).willReturn(order);
        given(orderRepository.findById(ORDER_ID)).willReturn(Optional.of(order));

        PortOnePaymentResponse portOneResponse = new PortOnePaymentResponse(
                PAYMENT_ID, "PAID", new PortOnePaymentResponse.Amount(AMOUNT), OffsetDateTime.now());
        String rawBody = "{\"id\":\"" + PAYMENT_ID + "\",\"status\":\"PAID\"}";
        PortOnePaymentResult prefetched = new PortOnePaymentResult(portOneResponse, rawBody);
        given(portOneClient.getPayment(PAYMENT_ID)).willReturn(prefetched);

        VerifyPaymentResponse expected = new VerifyPaymentResponse(
                ORDER_ID, PAYMENT_ID, AMOUNT, PaymentStatus.PAID, null, OrderStatus.PAID);
        given(reconciler.completePayment(PAYMENT_ID, PaymentHistoryTrigger.CLIENT_VERIFY, prefetched))
                .willReturn(expected);

        // when
        VerifyPaymentResponse result = paymentService.completePayment(PAYMENT_ID, PaymentHistoryTrigger.CLIENT_VERIFY);

        // then
        assertThat(result).isEqualTo(expected);
        ArgumentCaptor<PortOnePaymentResult> captor = ArgumentCaptor.forClass(PortOnePaymentResult.class);
        verify(reconciler).completePayment(eq(PAYMENT_ID), eq(PaymentHistoryTrigger.CLIENT_VERIFY), captor.capture());
        assertThat(captor.getValue()).isSameAs(prefetched);
    }

    @Test
    @DisplayName("completePayment_이미_PAID면_락_없이_조기_반환하고_PG도_reconciler도_호출하지_않는다")
    void completePayment_이미_PAID_조기반환() {
        // given
        Payment payment = Mockito.mock(Payment.class);
        given(paymentRepository.findByMerchantUid(PAYMENT_ID)).willReturn(Optional.of(payment));
        given(payment.getStatus()).willReturn(PaymentStatus.PAID);
        given(payment.getOrder()).willReturn(order);
        given(payment.getMerchantUid()).willReturn(PAYMENT_ID);
        given(payment.getAmount()).willReturn(AMOUNT);
        given(orderRepository.findById(ORDER_ID)).willReturn(Optional.of(order));

        // when
        VerifyPaymentResponse result = paymentService.completePayment(PAYMENT_ID, PaymentHistoryTrigger.CLIENT_VERIFY);

        // then
        assertThat(result).isNotNull();
        assertThat(result.paymentId()).isEqualTo(PAYMENT_ID);
        verify(portOneClient, never()).getPayment(anyString());
        verify(reconciler, never()).completePayment(anyString(), any(), any());
    }

    @Test
    @DisplayName("completePayment_주문이_CANCELLED면_선조회를_건너뛰고_reconciler에_null로_위임")
    void completePayment_주문_CANCELLED_선조회_생략() {
        // given
        Payment payment = Mockito.mock(Payment.class);
        given(paymentRepository.findByMerchantUid(PAYMENT_ID)).willReturn(Optional.of(payment));
        given(payment.getStatus()).willReturn(PaymentStatus.PENDING);
        given(payment.getOrder()).willReturn(order);
        given(order.getStatus()).willReturn(OrderStatus.CANCELLED);
        given(orderRepository.findById(ORDER_ID)).willReturn(Optional.of(order));

        VerifyPaymentResponse expected = new VerifyPaymentResponse(
                ORDER_ID, PAYMENT_ID, AMOUNT, PaymentStatus.REFUNDED, null, OrderStatus.CANCELLED);
        given(reconciler.completePayment(eq(PAYMENT_ID), eq(PaymentHistoryTrigger.CLIENT_VERIFY), isNull()))
                .willReturn(expected);

        // when
        VerifyPaymentResponse result = paymentService.completePayment(PAYMENT_ID, PaymentHistoryTrigger.CLIENT_VERIFY);

        // then
        assertThat(result).isEqualTo(expected);
        verify(portOneClient, never()).getPayment(anyString());
        verify(reconciler).completePayment(eq(PAYMENT_ID), eq(PaymentHistoryTrigger.CLIENT_VERIFY), isNull());
    }

    @Test
    @DisplayName("completePayment_Payment_없으면_선조회_없이_reconciler에_null로_위임")
    void completePayment_Payment_없음_선조회_생략() {
        // given
        given(paymentRepository.findByMerchantUid(PAYMENT_ID)).willReturn(Optional.empty());
        given(reconciler.completePayment(eq(PAYMENT_ID), eq(PaymentHistoryTrigger.CLIENT_VERIFY), isNull()))
                .willThrow(new BusinessException(PaymentErrorCode.PAYMENT_NOT_FOUND));

        // when & then
        assertThatThrownBy(() -> paymentService.completePayment(PAYMENT_ID, PaymentHistoryTrigger.CLIENT_VERIFY))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode())
                        .isEqualTo(PaymentErrorCode.PAYMENT_NOT_FOUND));

        verify(portOneClient, never()).getPayment(anyString());
    }

    @Test
    @DisplayName("completePayment_선조회_PG_InfraException이면_reconciler_호출_없이_예외_전파하고_카운터를_올린다")
    void completePayment_선조회_실패_reconciler_미호출() {
        // given
        Payment payment = Mockito.mock(Payment.class);
        given(paymentRepository.findByMerchantUid(PAYMENT_ID)).willReturn(Optional.of(payment));
        given(payment.getStatus()).willReturn(PaymentStatus.PENDING);
        given(payment.getOrder()).willReturn(order);
        given(orderRepository.findById(ORDER_ID)).willReturn(Optional.of(order));
        given(portOneClient.getPayment(PAYMENT_ID))
                .willThrow(new InfraException(PaymentErrorCode.PAYMENT_PG_UNAVAILABLE));

        // when & then
        assertThatThrownBy(() -> paymentService.completePayment(PAYMENT_ID, PaymentHistoryTrigger.CLIENT_VERIFY))
                .isInstanceOf(InfraException.class);

        assertThat(paymentFailedPgErrorCounter.count()).isEqualTo(1.0);
        verify(reconciler, never()).completePayment(anyString(), any(), any());
    }
}
```

- [ ] **Step 2: 테스트 실패 확인**

Run: `./gradlew test --tests "*PaymentServiceTest*" -q`
Expected: 컴파일 실패 — `PaymentService` 생성자가 아직 `orderItemRepository`, `productRepository`, `stockRedisService`, `paymentHistoryRecorder`, 8개 카운터를 요구하는 옛 시그니처이고 `PaymentReconciler` 파라미터가 없다. `reconciler.completePayment(...)`도 아직 `PaymentService`에서 호출되지 않는다.

- [ ] **Step 3: `PaymentService.java` 재작성**

전체 파일을 아래로 교체:

```java
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
```

- [ ] **Step 4: 테스트 통과 확인**

Run: `./gradlew test --tests "*PaymentServiceTest*" -q`
Expected: PASS — 11개 전부(preparePayment 5 + validateOwnership 3 + completePayment 코디네이터 5 = 13개 — 정확한 개수는 위 테스트 파일 기준으로 확인)

- [ ] **Step 5: 전체 빌드 확인**

Run: `./gradlew compileJava compileTestJava -q`
Expected: 성공

- [ ] **Step 6: 커밋**

```bash
git add src/main/java/com/gongu/server/domain/payment/service/PaymentService.java src/test/java/com/gongu/server/domain/payment/service/PaymentServiceTest.java
git commit -m "refactor: completePayment를 PG 선조회 코디네이터로 재작성, PaymentReconciler에 위임 (#146)"
```

---

### Task 3: 통합 테스트 — 트랜잭션 밖에서 PG가 진짜로 호출되는지 실증 + 전체 회귀 확인 + ADR-008 갱신

**Files:**
- Create: `src/test/java/com/gongu/server/domain/payment/service/PaymentCompletePgOutsideTransactionIntegrationTest.java`
- Modify: `docs/adr/결제_확정_아키텍처.md`

**Interfaces:**
- Consumes: `PaymentService.completePayment`, `PaymentReconciler.completePayment` (Task 1, 2로 이미 배선 완료)

Task 1·2의 Mockito 테스트는 "코드가 그 순서로 호출됐는지"만 본다. 실제로 `getPayment` 호출 시점에 진짜 Spring 트랜잭션이 열려 있지 않은지는 별도로 증명해야 한다 — `@Transactional(propagation = NOT_SUPPORTED)`를 빼먹거나 잘못 적용해도 Mockito 테스트는 전부 그대로 통과하기 때문이다.

- [ ] **Step 1: 실패하는(사실은 이미 통과해야 정상인) 통합 테스트 작성**

`PaymentHistoryIntegrationTest.java`(같은 디렉터리)의 `@SpringBootTest` + `@MockitoBean PortOneClient` 패턴을 따른다. `PortOneClient`를 `@MockitoBean`으로 교체하되, `getPayment` 호출 시점에 `TransactionSynchronizationManager.isActualTransactionActive()`를 직접 확인하는 `Answer`를 건다 — 이게 이 태스크의 핵심이다.

```java
package com.gongu.server.domain.payment.service;

import com.gongu.server.domain.order.entity.Order;
import com.gongu.server.domain.order.repository.OrderRepository;
import com.gongu.server.domain.payment.domain.Payment;
import com.gongu.server.domain.payment.domain.PaymentHistoryTrigger;
import com.gongu.server.domain.payment.domain.PaymentStatus;
import com.gongu.server.domain.payment.repository.PaymentRepository;
import com.gongu.server.domain.user.entity.User;
import com.gongu.server.domain.user.repository.UserRepository;
import com.gongu.server.global.infrastructure.portone.PortOneClient;
import com.gongu.server.global.infrastructure.portone.dto.PortOnePaymentResponse;
import com.gongu.server.global.infrastructure.portone.dto.PortOnePaymentResult;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.OffsetDateTime;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;

/**
 * #146 — completePayment의 PG 조회가 실제로 @Transactional 밖에서 실행되는지 증명한다.
 * Task 1/2의 Mockito 단위 테스트는 호출 순서만 검증하므로, PaymentService.completePayment의
 * @Transactional(propagation = NOT_SUPPORTED)가 빠지거나 잘못돼도 초록불일 수 있다 — 여기서는
 * 진짜 Spring 트랜잭션 매니저에게 "지금 트랜잭션이 열려 있냐"고 직접 물어본다.
 */
@SpringBootTest
class PaymentCompletePgOutsideTransactionIntegrationTest {

    @Autowired
    private PaymentService paymentService;

    @Autowired
    private PaymentRepository paymentRepository;

    @Autowired
    private OrderRepository orderRepository;

    @Autowired
    private UserRepository userRepository;

    @MockitoBean
    private PortOneClient portOneClient;

    @AfterEach
    void tearDown() {
        paymentRepository.deleteAll();
        orderRepository.deleteAll();
        userRepository.deleteAll();
    }

    @Test
    @DisplayName("completePayment_PG_getPayment_호출_시점에_활성_트랜잭션이_없다")
    void completePayment_PG_호출_시점_트랜잭션_비활성() {
        // given
        User user = userRepository.save(User.of("결제자4", "010-1111-2222"));
        Order order = orderRepository.save(Order.create(user, 10_000L));
        paymentRepository.save(Payment.initiate(order, "idem-int-tx-1", "pay-int-tx-1", 10_000L));

        AtomicBoolean transactionActiveDuringPgCall = new AtomicBoolean(true); // 기본값 true — 호출이 아예 안 되면 실패로 드러나게
        given(portOneClient.getPayment("pay-int-tx-1")).willAnswer(invocation -> {
            transactionActiveDuringPgCall.set(TransactionSynchronizationManager.isActualTransactionActive());
            return new PortOnePaymentResult(
                    new PortOnePaymentResponse("pay-int-tx-1", "PAID",
                            new PortOnePaymentResponse.Amount(10_000L), OffsetDateTime.now()),
                    "{\"id\":\"pay-int-tx-1\",\"status\":\"PAID\"}");
        });

        // when
        paymentService.completePayment("pay-int-tx-1", PaymentHistoryTrigger.CLIENT_VERIFY);

        // then
        assertThat(transactionActiveDuringPgCall.get())
                .as("portOneClient.getPayment 호출 시점에 활성 Spring 트랜잭션이 없어야 한다 (#146)")
                .isFalse();

        Payment saved = paymentRepository.findByMerchantUid("pay-int-tx-1").orElseThrow();
        assertThat(saved.getStatus()).isEqualTo(PaymentStatus.PAID);
    }
}
```

- [ ] **Step 2: 테스트 통과 확인**

Run: `./gradlew test --tests "*PaymentCompletePgOutsideTransactionIntegrationTest*" -q`
Expected: PASS. 만약 FAIL(트랜잭션이 활성 상태로 나옴)이면 Task 2의 `@Transactional(propagation = Propagation.NOT_SUPPORTED)`가 빠졌거나, 클래스 레벨 `@Transactional(readOnly = true)`가 여전히 적용되고 있다는 뜻이다 — Task 2로 돌아가 `PaymentService.java`의 `completePayment` 메서드 애노테이션을 확인한다.

- [ ] **Step 3: 전체 스위트 확인**

Run: `./gradlew test -q`
Expected: exit 0, 전 테스트 통과 (기존 `PaymentControllerTest`, `PaymentSecurityTest`, `PaymentCompleteBulkheadTest`, `PaymentHistoryIntegrationTest`, `ResilienceConfigParityTest` 등은 `PaymentService`를 `@Autowired` 또는 `@MockitoBean`으로만 쓰므로 생성자 변경의 영향을 받지 않는다 — 그대로 통과해야 정상이다)

- [ ] **Step 4: ADR-008 갱신 — D1 구현 완료 표시**

`docs/adr/결제_확정_아키텍처.md`의 `## 7. 이행 순서` 섹션, 4번 항목:

```
4. **D1** `PaymentReconciler` — PG 조회를 트랜잭션 밖으로 (verify·webhook 공용)
```

을 아래로 교체:

```
4. **D1** `PaymentReconciler` — PG 조회를 트랜잭션 밖으로 (verify·webhook 공용) — **완료 (#146)**. `PaymentService.completePayment`는 락 없이 PG 조회 필요 여부를 판단해 트랜잭션 밖에서 `getPayment`를 선조회하고, `PaymentReconciler`(`@Transactional`)에 결과를 넘긴다. 비관적 락(D2 미채택 상태)과 보상 분기의 PG 취소 호출(P1에 문서화된 gap)은 이번 범위 밖으로 그대로 유지.
```

같은 문서 `## 4. 결정 (제안)`의 `### D1.` 섹션 바로 아래(코드 블록 다음, `- P1(...)...` 불릿 리스트 위)에 한 줄 추가:

```
**구현 완료 (#146, 2026-09-19).** 아래 설계 그대로 채택했다 — 단 3단계("짧은 쓰기 트랜잭션: 조건부 UPDATE로 상태 반영")는 D2 미채택 상태라 비관적 락(`findByMerchantUidWithLock`/`findByIdWithLock`)을 그대로 쓴다. 보상 분기(주문 만료 환불·금액 불일치·재고 부족)의 PG 취소 호출은 이번 범위에 포함하지 않았다 — 여전히 락 보유 중 실행된다.
```

- [ ] **Step 5: 커밋**

```bash
git add src/test/java/com/gongu/server/domain/payment/service/PaymentCompletePgOutsideTransactionIntegrationTest.java docs/adr/결제_확정_아키텍처.md
git commit -m "test: PG 조회가 트랜잭션 밖에서 실행됨을 증명하는 통합 테스트 추가, ADR-008 D1 완료 표시 (#146)"
```
