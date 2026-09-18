# #230 재고 부족 시 결제 확정 롤백 안 되는 문제 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** `PaymentService.completePayment`가 `@Transactional(noRollbackFor = {BusinessException.class, InfraException.class})`이라, 재고 부족(`Product.confirmStock`의 `INSUFFICIENT_STOCK`)이 나도 롤백되지 않고 결제·주문이 PAID로 커밋되던 버그(#230)를 고친다. PG는 결제됨·우리 DB는 PAID·재고 미차감·환불 없음 — 초과 판매 방어선의 마지막 구멍이었다.

**Architecture:** 재고 확인·차감을 `order.pay()`/`payment.confirm()`보다 **먼저** 수행한다. 재고가 부족하면 기존 "금액 불일치" 분기와 완전히 같은 모양(PG 취소 → `payment.refund()` → `order.cancel()` → Redis 예약 해제)의 새 보상 분기로 끝낸다. 재고가 충분하면 기존 성공 경로(락 걸린 각 상품에 `confirmStock` 적용 → `order.pay()`/`payment.confirm()`)를 그대로 탄다. 항목이 여러 개여도 "전 항목 재고 확인 → 전 항목 확정"의 두 단계로 나눠, 앞 항목만 차감된 채 뒤 항목에서 실패하는 부분 커밋을 만들지 않는다.

**Tech Stack:** Spring Boot 3.5, Spring Data JPA, JUnit5 + Mockito + AssertJ, `@SpringBootTest` + `@MockitoBean`(통합 테스트).

## Global Constraints

- 커밋 메시지 형식: `type: 작업 내용 (#230)`. `Co-Authored-By` 절대 포함 금지.
- 락 순서는 기존 그대로 유지: Payment → Order → Product(항목별). 새 분기도 이 순서를 지킨다.
- `PaymentHistoryRecorder.record(...)`는 `@Transactional(propagation = MANDATORY)` — 반드시 이미 트랜잭션 안에서(즉 `completePayment` 메서드 안에서) 호출한다.
- `PaymentRepository`에 `findByIdWithLock`가 이미 있다(#215에서 복구됨) — 이번 작업에서 새로 만들지 않는다.
- `executePGCancel`은 PG 취소 실패(`InfraException`)를 캐치하지 않고 그대로 전파한다 — 상태 변경(`payment.refund()`/`order.cancel()`) 코드는 `executePGCancel` 호출 **다음**에 있으므로, PG 취소가 실패하면 자동으로 아무 상태도 안 바뀐 채 예외가 전파된다(완료 기준 3번). 이 속성을 기존 "금액 불일치" 분기가 이미 그대로 갖고 있고, 새 "재고 부족" 분기도 같은 순서로 작성하면 별도 처리 없이 저절로 만족된다.

---

### Task 1: `PaymentErrorCode` + 카운터 + 웹훅 터미널 코드 등록

**Files:**
- Modify: `src/main/java/com/gongu/server/global/exception/errorcode/PaymentErrorCode.java`
- Modify: `src/main/java/com/gongu/server/global/config/MetricsConfig.java`
- Modify: `src/main/java/com/gongu/server/domain/payment/controller/PaymentController.java`

**Interfaces:**
- Produces: `PaymentErrorCode.PAYMENT_INSUFFICIENT_STOCK_REFUNDED` (enum 상수), `MetricsConfig.paymentFailedInsufficientStockCounter()` 빈(`Counter`)

이 태스크는 순수 상수/설정 추가라 독립적으로 컴파일만 확인하면 된다. 별도 테스트 파일 없음 — Task 2가 이 상수·카운터를 실제로 사용하는 테스트를 작성한다.

- [ ] **Step 1: `PaymentErrorCode`에 새 코드 추가**

`src/main/java/com/gongu/server/global/exception/errorcode/PaymentErrorCode.java`에서 `WEBHOOK_VERIFICATION_FAILED("PAYMENT_010", ...)` 다음 줄에 추가:

```java
    PAYMENT_INSUFFICIENT_STOCK_REFUNDED("PAYMENT_011", "재고 부족으로 결제가 자동 환불되었습니다", 409),
```

(세미콜론이 있던 이전 줄 끝의 `;`을 이 줄 끝으로 옮기고, `WEBHOOK_VERIFICATION_FAILED` 줄 끝은 `,`로 바꾼다.)

- [ ] **Step 2: `MetricsConfig`에 카운터 빈 추가**

`src/main/java/com/gongu/server/global/config/MetricsConfig.java`의 `paymentFailedAmountMismatchCounter()` 메서드 바로 아래에 추가:

```java
    @Bean
    public Counter paymentFailedInsufficientStockCounter() {
        return paymentFailedCounter("insufficient_stock");
    }
```

- [ ] **Step 3: 웹훅 터미널 코드에 추가**

`src/main/java/com/gongu/server/domain/payment/controller/PaymentController.java`의 `WEBHOOK_TERMINAL_CODES` `Set.of(...)`에 `PaymentErrorCode.PAYMENT_AMOUNT_MISMATCH` 다음 항목으로 추가:

```java
            PaymentErrorCode.PAYMENT_INSUFFICIENT_STOCK_REFUNDED
```

(재처리해도 결과가 같은 확정 상태이므로 웹훅 재시도를 멈춰야 한다 — 금액 불일치와 동일한 이유.)

- [ ] **Step 4: 컴파일 확인**

Run: `./gradlew compileJava -q`
Expected: 성공 (이 상수·빈은 아직 아무 데서도 쓰이지 않으므로 실패 없음)

- [ ] **Step 5: 커밋**

```bash
git add src/main/java/com/gongu/server/global/exception/errorcode/PaymentErrorCode.java src/main/java/com/gongu/server/global/config/MetricsConfig.java src/main/java/com/gongu/server/domain/payment/controller/PaymentController.java
git commit -m "feat: 재고 부족 결제 확정용 에러코드·카운터·웹훅 터미널 코드 추가 (#230)"
```

---

### Task 2: `PaymentService.completePayment` — 재고 확인을 확정보다 먼저 수행

**Files:**
- Modify: `src/main/java/com/gongu/server/domain/payment/service/PaymentService.java`
- Test: `src/test/java/com/gongu/server/domain/payment/service/PaymentServiceTest.java`

**Interfaces:**
- Consumes: `PaymentErrorCode.PAYMENT_INSUFFICIENT_STOCK_REFUNDED`, `Counter paymentFailedInsufficientStockCounter` (Task 1)
- Consumes 기존: `Product.confirmStock(int)`(변경 없음, `ProductErrorCode.INSUFFICIENT_STOCK` 던짐), `executePGCancel(String, String)`(기존 private 메서드, 변경 없음)
- Produces: `PaymentService` 생성자 시그니처에 `Counter paymentFailedInsufficientStockCounter` 파라미터가 마지막에 추가된다(Task 2 이후 이 서비스를 `new PaymentService(...)`로 만드는 다른 테스트가 있다면 함께 깨진다 — 이 코드베이스에선 `PaymentServiceTest`가 유일한 그런 테스트이고 이 태스크가 같이 고친다).

이 태스크가 이슈 #230의 핵심 수정이다.

- [ ] **Step 1: 실패하는 테스트 먼저 작성 — 재고 부족 보상 분기**

`src/test/java/com/gongu/server/domain/payment/service/PaymentServiceTest.java`에서, `setUp()`의 `paymentService = new PaymentService(...)` 생성자 호출에 새 카운터 필드를 추가한다. 먼저 필드 선언부(`private Counter paymentFailedAmountMismatchCounter;` 바로 아래)에 추가:

```java
    private Counter paymentFailedInsufficientStockCounter;
```

`setUp()` 안, `paymentFailedAmountMismatchCounter = paymentFailedCounter(meterRegistry, "amount_mismatch");` 다음 줄에 추가:

```java
        paymentFailedInsufficientStockCounter = paymentFailedCounter(meterRegistry, "insufficient_stock");
```

`new PaymentService(...)` 생성자 호출의 마지막 인자(`paymentFailedAmountMismatchCounter`) 다음에 추가:

```java
                paymentFailedInsufficientStockCounter
```

(즉 생성자 호출은 `..., paymentFailedAmountMismatchCounter, paymentFailedInsufficientStockCounter);` 형태가 된다.)

그 다음, 파일의 `completePayment_금액불일치_보상처리` 테스트 바로 다음(521번째 줄 부근, 다음 `@Test` 앞)에 새 테스트 두 개를 추가한다:

```java
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
                PAYMENT_ID,
                "PAID",
                new PortOnePaymentResponse.Amount(AMOUNT),
                OffsetDateTime.now()
        );
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
        given(lockedProduct.getRemainingStock()).willReturn(1); // 주문 수량(2)보다 적다 — 재고 부족

        // when & then
        assertThatThrownBy(() -> paymentService.completePayment(PAYMENT_ID, PaymentHistoryTrigger.CLIENT_VERIFY))
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
                PAYMENT_ID,
                "PAID",
                new PortOnePaymentResponse.Amount(AMOUNT),
                OffsetDateTime.now()
        );
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
        assertThatThrownBy(() -> paymentService.completePayment(PAYMENT_ID, PaymentHistoryTrigger.CLIENT_VERIFY))
                .isInstanceOf(InfraException.class);

        verify(payment, never()).refund();
        verify(order, never()).cancel(anyString());
        verify(stockRedisService, never()).releaseStockAfterCommit(any(), anyInt());
    }
```

- [ ] **Step 2: 테스트 실패 확인**

Run: `./gradlew test --tests "*PaymentServiceTest*" -q`
Expected: 컴파일 실패 — `Product.getRemainingStock()` 호출 자체는 이미 존재하는 메서드라 문제 없지만, 새로 추가한 두 테스트가 기대하는 동작(재고 부족 시 `order.pay()`/`payment.confirm()`이 호출되지 않고 `PAYMENT_INSUFFICIENT_STOCK_REFUNDED`가 던져짐)이 현재 `completePayment` 구현과 다르므로 **테스트 자체는 컴파일되지만 실행 시 실패**한다(현재 코드는 `confirmStock()`이 `BusinessException(INSUFFICIENT_STOCK)`을 던지고 `noRollbackFor`라 그대로 전파되며 `order.pay()`/`payment.confirm()`은 이미 호출된 뒤라 mock 검증도 실패). 기존 두 테스트(`completePayment_성공_금액일치`, 기존 기타)는 계속 통과해야 한다.

- [ ] **Step 3: `PaymentService` 생성자에 새 카운터 추가**

`src/main/java/com/gongu/server/domain/payment/service/PaymentService.java`에서 `@Qualifier("paymentFailedAmountMismatchCounter") private final Counter paymentFailedAmountMismatchCounter;` 바로 아래에 추가:

```java
    @Qualifier("paymentFailedInsufficientStockCounter")
    private final Counter paymentFailedInsufficientStockCounter;
```

(`@RequiredArgsConstructor`가 생성자를 자동 생성하므로 필드 선언 순서가 곧 생성자 파라미터 순서다 — Step 1에서 테스트의 `new PaymentService(...)` 호출에 카운터를 **마지막 인자**로 추가하도록 지시한 것과 일치해야 한다.)

- [ ] **Step 4: `completePayment`의 금액 일치 분기를 재작성**

`src/main/java/com/gongu/server/domain/payment/service/PaymentService.java`에서 아래 블록(179~213번째 줄 부근, `Long expectedAmount = order.getTotalPrice();`부터 메서드 끝 `}`까지)을 통째로 교체한다:

```java
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
```

(이 교체로 메서드 끝의 `}`가 하나 더 생기지 않도록 주의 — 위 코드 블록의 마지막 `}`가 `completePayment` 메서드 자체를 닫는 `}`다. 그 다음에 원래 있던 `private record PgCancelOutcome...` 이하는 그대로 둔다.)

- [ ] **Step 5: 테스트 통과 확인**

Run: `./gradlew test --tests "*PaymentServiceTest*" -q`
Expected: PASS — 전체 24개 테스트(기존 22 + 신규 2)

- [ ] **Step 6: 전체 빌드 확인**

Run: `./gradlew compileJava compileTestJava -q`
Expected: 성공

- [ ] **Step 7: 커밋**

```bash
git add src/main/java/com/gongu/server/domain/payment/service/PaymentService.java src/test/java/com/gongu/server/domain/payment/service/PaymentServiceTest.java
git commit -m "fix: 재고 확인을 결제 확정보다 먼저 수행해 재고 부족 시 롤백되도록 수정 (#230)"
```

---

### Task 3: 통합 테스트 + 웹훅 회귀 테스트 + ADR-008 정정

**Files:**
- Create: `src/test/java/com/gongu/server/domain/payment/service/PaymentInsufficientStockIntegrationTest.java`
- Modify: `src/test/java/com/gongu/server/domain/payment/controller/PaymentControllerTest.java`
- Modify: `docs/adr/결제_확정_아키텍처.md`

**Interfaces:**
- Consumes: `PaymentService.completePayment`(Task 2로 이미 수정됨), `PaymentErrorCode.PAYMENT_INSUFFICIENT_STOCK_REFUNDED`(Task 1)

완료 기준의 "실제 트랜잭션으로 커밋 결과를 확인하는 통합 테스트"를 충족하는 태스크다. Mockito 단위 테스트(Task 2)는 in-memory mock 상태만 검증하므로, 진짜 Spring 트랜잭션·DB 반영까지 보는 별도 테스트가 필요하다 — `PaymentExpireReconcilerIntegrationTest`(#215에서 만들어짐, 같은 디렉터리에 있음)가 정확히 같은 이유로 존재하는 선례다.

- [ ] **Step 1: 실패하는 통합 테스트 작성**

`src/test/java/com/gongu/server/domain/payment/service/PaymentInsufficientStockIntegrationTest.java` 신규 작성. `PaymentHistoryIntegrationTest.java`(같은 디렉터리)의 `@MockitoBean PortOneClient` + `@SpringBootTest` 패턴을 그대로 따른다:

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
import com.gongu.server.domain.payment.repository.PaymentRepository;
import com.gongu.server.domain.product.entity.Product;
import com.gongu.server.domain.product.entity.ProductStatus;
import com.gongu.server.domain.product.repository.ProductRepository;
import com.gongu.server.domain.store.entity.Store;
import com.gongu.server.domain.store.repository.StoreRepository;
import com.gongu.server.domain.user.entity.User;
import com.gongu.server.domain.user.repository.UserRepository;
import com.gongu.server.global.exception.BusinessException;
import com.gongu.server.global.exception.errorcode.PaymentErrorCode;
import com.gongu.server.global.infrastructure.portone.PortOneClient;
import com.gongu.server.global.infrastructure.portone.dto.PortOnePaymentResponse;
import com.gongu.server.global.infrastructure.portone.dto.PortOnePaymentResult;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.time.LocalDateTime;
import java.time.OffsetDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;

/**
 * #230 — 재고 부족 시 결제 확정이 실제로 롤백되지 않고 PAID로 커밋되던 버그의 회귀 테스트.
 * PaymentServiceTest는 Mockito 단위 테스트라 in-memory mock 상태만 본다 — 재고 부족 예외가
 * noRollbackFor 대상이라 실제로는 커밋되는 버그였더라도, mock만으로는 "무엇이 진짜 DB에
 * 반영됐는지" 알 수 없다. 여기서는 실제 Spring 트랜잭션 + DB로 커밋 결과를 확인한다.
 */
@SpringBootTest
class PaymentInsufficientStockIntegrationTest {

    @Autowired
    private PaymentService paymentService;

    @Autowired
    private PaymentRepository paymentRepository;

    @Autowired
    private OrderRepository orderRepository;

    @Autowired
    private OrderItemRepository orderItemRepository;

    @Autowired
    private ProductRepository productRepository;

    @Autowired
    private StoreRepository storeRepository;

    @Autowired
    private UserRepository userRepository;

    @MockitoBean
    private PortOneClient portOneClient;

    @AfterEach
    void tearDown() {
        paymentRepository.deleteAll();
        orderItemRepository.deleteAll();
        orderRepository.deleteAll();
        productRepository.deleteAll();
        storeRepository.deleteAll();
        userRepository.deleteAll();
    }

    @Test
    @DisplayName("재고_부족하면_결제는_REFUNDED_주문은_CANCELLED로_커밋되고_PG_취소가_호출된다")
    void completePayment_재고부족_실제_DB_REFUNDED_CANCELLED() {
        // given
        User user = userRepository.save(User.of("결제자3", "010-7777-8888"));
        Store store = storeRepository.save(Store.create("테스트매장", "서울시 강남구", "02-1234-5678"));
        Product product = productRepository.save(Product.create(
                store, "품절임박상품", "설명", 10_000L, 5,
                ProductStatus.ACTIVE, LocalDateTime.now().minusDays(1), LocalDateTime.now().plusDays(1)));
        // remainingStock을 주문 수량(2)보다 적게 만든다 — Redis 예약은 성공했는데
        // MySQL 재고가 이미 다른 확정으로 줄어든 상황을 재현한다.
        for (int i = 0; i < 4; i++) {
            product.confirmStock(1);
        }
        productRepository.save(product); // remainingStock = 1

        Order order = orderRepository.save(Order.create(user, 20_000L));
        orderItemRepository.save(OrderItem.create(order, product, 2L));
        Payment payment = paymentRepository.save(
                Payment.initiate(order, "idem-int-stock-1", "pay-int-stock-1", 20_000L));

        String rawJson = "{\"id\":\"pay-int-stock-1\",\"status\":\"PAID\",\"amount\":{\"total\":20000}}";
        given(portOneClient.getPayment("pay-int-stock-1")).willReturn(new PortOnePaymentResult(
                new PortOnePaymentResponse("pay-int-stock-1", "PAID",
                        new PortOnePaymentResponse.Amount(20_000L), OffsetDateTime.now()),
                rawJson));
        given(portOneClient.cancelPayment(eq("pay-int-stock-1"), org.mockito.ArgumentMatchers.anyString()))
                .willReturn(new PortOnePaymentResult(null, "{}"));

        // when & then
        assertThatThrownBy(() -> paymentService.completePayment("pay-int-stock-1", PaymentHistoryTrigger.CLIENT_VERIFY))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode())
                        .isEqualTo(PaymentErrorCode.PAYMENT_INSUFFICIENT_STOCK_REFUNDED));

        // then — 새 조회로 실제 DB 커밋 상태를 확인한다 (같은 영속성 컨텍스트가 아님)
        Payment reloadedPayment = paymentRepository.findByMerchantUid("pay-int-stock-1").orElseThrow();
        Order reloadedOrder = orderRepository.findById(order.getId()).orElseThrow();
        Product reloadedProduct = productRepository.findById(product.getId()).orElseThrow();

        assertThat(reloadedPayment.getStatus()).isEqualTo(PaymentStatus.REFUNDED);
        assertThat(reloadedOrder.getStatus()).isEqualTo(OrderStatus.CANCELLED);
        assertThat(reloadedProduct.getRemainingStock()).isEqualTo(1); // 차감되지 않았어야 한다
    }
}
```

- [ ] **Step 2: 테스트 실패 확인**

Run: `./gradlew test --tests "*PaymentInsufficientStockIntegrationTest*" -q`
Expected: Task 2가 이미 반영돼 있다면 이 테스트는 이미 PASS해야 정상이다(Task 2에서 이미 프로덕션 코드를 고쳤으므로). Task 2 완료 후 이 태스크를 진행하는 것이므로, 이 Step은 "고쳐지기 전 코드였다면 실패했을 것"을 확인하는 대신 — 만약 실행 결과가 FAIL이면 Task 2의 구현이 잘못됐다는 신호이니 Task 2로 돌아가 원인을 찾는다. PASS면 Step 3으로.

- [ ] **Step 3: 테스트 통과 확인 (이미 통과했다면 이 Step은 확인만)**

Run: `./gradlew test --tests "*PaymentInsufficientStockIntegrationTest*" -q`
Expected: PASS

- [ ] **Step 4: 웹훅 터미널 코드 회귀 테스트 추가**

`src/test/java/com/gongu/server/domain/payment/controller/PaymentControllerTest.java`를 읽고, 기존 `PAYMENT_AMOUNT_MISMATCH` 웹훅 200 응답을 검증하는 테스트를 찾아라(`WEBHOOK_TERMINAL_CODES` 관련 테스트 — 파일 안에서 `PAYMENT_AMOUNT_MISMATCH`로 검색). 그 테스트와 완전히 같은 구조로, `paymentService.completePayment(...)`가 `BusinessException(PaymentErrorCode.PAYMENT_INSUFFICIENT_STOCK_REFUNDED)`를 던지도록 mock한 뒤 웹훅 엔드포인트가 200을 반환하는지 검증하는 테스트를 하나 추가한다. 기존 테스트의 정확한 given/when/then 구조·mock 설정 방식을 그대로 따라라 — 이 파일은 이미 `@MockitoBean PaymentService` 패턴을 쓰고 있다(파일 상단 확인).

- [ ] **Step 5: 웹훅 테스트 통과 확인**

Run: `./gradlew test --tests "*PaymentControllerTest*" -q`
Expected: PASS

- [ ] **Step 6: ADR-008 P3 서술 정정**

`docs/adr/결제_확정_아키텍처.md`에서 (137번째 줄 부근):

```
- 재고 차감 실패가 결제 확정 롤백을 유발 — 돈은 이미 PG에서 승인됐는데
```

를 아래로 교체:

```
- (정정, #230) 재고 차감 실패는 롤백을 유발하지 **않았다** — `noRollbackFor`에 걸려 결제·주문이 PAID로 그대로 커밋되고, 재고만 미차감·환불 없음으로 남았다. #230에서 재고 확인을 상태 확정보다 먼저 수행하도록 고쳐, 재고 부족 시엔 상태 확정 자체가 일어나지 않고 금액 불일치와 같은 모양의 보상(PG 취소 → REFUNDED · CANCELLED)으로 끝나도록 정정했다.
```

- [ ] **Step 7: 전체 스위트 확인**

Run: `./gradlew test -q`
Expected: exit 0, 전 테스트 통과

- [ ] **Step 8: 커밋**

```bash
git add src/test/java/com/gongu/server/domain/payment/service/PaymentInsufficientStockIntegrationTest.java src/test/java/com/gongu/server/domain/payment/controller/PaymentControllerTest.java docs/adr/결제_확정_아키텍처.md
git commit -m "test: 재고 부족 결제 확정 통합/웹훅 회귀 테스트 추가, ADR-008 P3 정정 (#230)"
```
