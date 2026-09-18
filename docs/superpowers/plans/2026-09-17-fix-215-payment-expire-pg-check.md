# #215 만료 스케줄러 PG 확인 후 취소 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** `PaymentExpireService.cancelExpiredPayment`가 PG 확인 없이 만료 결제를 바로 CANCELLED로 확정해버리는 버그(#215)를 고친다. PG가 실제로 PAID를 확인한 건은 정상 확정시키고, PG 조회 자체가 실패하면 취소하지 않고 다음 주기로 미룬다.

**Architecture:** 새 로직을 만들지 않고 기존 `PaymentService.completePayment(paymentId, trigger)`(PG 조회·PAID 확정·금액 검증·InfraException 시 PENDING 유지 로직이 이미 구현돼 있음, verify/webhook과 동일 경로)를 재사용한다. `PaymentExpireService`는 만료 후보를 찾아 `completePayment`를 호출하고, 그 결과(PAID 확정 성공 / PG가 "결제 안 됨"으로 확정 / PG 조회 판정 불가)에 따라 분기만 담당한다. PG 호출은 `completePayment` 내부의 짧은 트랜잭션 안에서만 일어나고, `PaymentExpireService`의 조정 로직 자체는 트랜잭션을 길게 들고 있지 않는다(#146 원칙 준수).

**Tech Stack:** Spring Boot 3.5, Spring Data JPA, Micrometer(Counter), JUnit5 + Mockito + AssertJ.

## Global Constraints

- 커밋 메시지 형식: `type: 작업 내용 (#215)`. `Co-Authored-By` 절대 포함 금지.
- 이 리포는 Flyway/Liquibase가 없다 — `docs/schema/ddl.sql`이 스키마 소스 오브 트루스, 실제 DB는 수동 `ALTER TABLE`로 맞춘다.
- `Payment` 엔티티는 `@NoArgsConstructor(PROTECTED)` + `@AllArgsConstructor(PRIVATE)` + `@Builder(PRIVATE)`이므로 새 필드도 이 패턴을 따른다.
- `PaymentHistoryRecorder.record(...)`는 `@Transactional(propagation = MANDATORY)` — 반드시 이미 트랜잭션 안에서 호출해야 한다.
- 기존 `PaymentExpireServiceTest`는 이번 변경으로 동작이 완전히 바뀌므로 전면 재작성한다.

---

### Task 1: `payments.expiry_check_attempts` 컬럼 추가

**Files:**
- Modify: `src/main/java/com/gongu/server/domain/payment/domain/Payment.java`
- Modify: `docs/schema/ddl.sql`
- Modify: `docs/schema/table-definitions.md`
- Modify: `src/test/java/com/gongu/server/domain/payment/domain/PaymentTest.java` **(이미 237줄짜리 기존 파일이다 — confirm/refund/fail 상태 전이 테스트가 이미 가득 차 있다. 절대 파일을 통째로 교체하지 말 것. 기존 내용은 전부 그대로 두고, 아래 테스트 메서드 하나만 `fail()` 관련 테스트 블록(`// ── fail() ──` 섹션) 바로 다음, 파일 마지막 `}` 앞에 추가한다.)**

**Interfaces:**
- Produces: `Payment.getExpiryCheckAttempts()` → `int`, `Payment.incrementExpiryCheckAttempts()` → `int` (증가 후 값 반환)

- [ ] **Step 1: 실패하는 테스트 추가 (기존 파일 끝에 추가만 할 것)**

`src/test/java/com/gongu/server/domain/payment/domain/PaymentTest.java`의 마지막 `}` 직전에 아래 섹션을 그대로 추가한다. 기존 헬퍼 `pendingPayment()`를 재사용한다 — 새 헬퍼나 새 import(User/Order 등)는 만들지 않는다:

```java
    // ── incrementExpiryCheckAttempts() ──────────────────────────────────

    @Test
    @DisplayName("incrementExpiryCheckAttempts() — 호출할 때마다 1씩 증가하고 현재값을 반환한다")
    void incrementExpiryCheckAttempts_증가() {
        Payment payment = pendingPayment();

        assertThat(payment.getExpiryCheckAttempts()).isZero();

        int first = payment.incrementExpiryCheckAttempts();
        int second = payment.incrementExpiryCheckAttempts();

        assertThat(first).isEqualTo(1);
        assertThat(second).isEqualTo(2);
        assertThat(payment.getExpiryCheckAttempts()).isEqualTo(2);
    }
```

- [ ] **Step 2: 테스트 실패 확인**

Run: `./gradlew test --tests "*PaymentTest*" -q`
Expected: 컴파일 실패 (`getExpiryCheckAttempts`/`incrementExpiryCheckAttempts` 메서드 없음). 기존 테스트 20여 개는 그대로 파일에 남아있어야 한다 — `wc -l src/test/java/com/gongu/server/domain/payment/domain/PaymentTest.java`로 250줄 안팎인지 먼저 확인하고 진행할 것.

- [ ] **Step 3: `Payment` 엔티티에 필드·메서드 추가**

`src/main/java/com/gongu/server/domain/payment/domain/Payment.java`의 `cancelledAt` 필드 선언 바로 아래(37번째 줄 부근)에 추가:

```java
    @Column(name = "expiry_check_attempts", nullable = false)
    @Builder.Default
    private int expiryCheckAttempts = 0;
```

`fail()` 메서드 바로 아래(클래스 마지막)에 추가:

```java
    public int incrementExpiryCheckAttempts() {
        this.expiryCheckAttempts++;
        return this.expiryCheckAttempts;
    }
```

`@Builder`가 `access = AccessLevel.PRIVATE`이므로 `@Builder.Default`를 쓰려면 클래스의 `@Builder` 애노테이션은 그대로 두고 필드에만 `@Builder.Default`를 추가하면 된다(이미 위 코드에 포함).

- [ ] **Step 4: 테스트 통과 확인**

Run: `./gradlew test --tests "*PaymentTest*" -q`
Expected: PASS

- [ ] **Step 5: 스키마 문서 갱신**

`docs/schema/ddl.sql`의 `payments` 테이블 정의(96~108번째 줄)에서 `cancelled_at` 컬럼 다음 줄에 추가:

```sql
    `expiry_check_attempts` int NOT NULL,
```

즉 최종 형태:

```sql
CREATE TABLE `payments` (
    `id`               bigint       NOT NULL,
    `order_id`         bigint       NOT NULL,
    `idempotency_key`  varchar(255) NOT NULL,
    `imp_uid`          varchar(50)  NOT NULL,
    `merchant_uid`     varchar(255) NOT NULL,
    `amount`           bigint       NOT NULL,
    `status`           varchar(20)  NOT NULL,
    `paid_at`          datetime     NULL,
    `cancelled_at`     datetime     NULL,
    `expiry_check_attempts` int     NOT NULL,
    `created_at`       datetime     NOT NULL,
    `updated_at`       datetime     NOT NULL
);
```

`docs/schema/table-definitions.md`의 `## payments` 테이블(279~296번째 줄) 표에서 `cancelled_at` 행 다음에 추가:

```markdown
| expiry_check_attempts | int | NO | 0 | 만료 스케줄러의 PG 조회가 판정 불가로 끝난 횟수 (#215) |
```

- [ ] **Step 6: 커밋**

```bash
git add src/main/java/com/gongu/server/domain/payment/domain/Payment.java src/test/java/com/gongu/server/domain/payment/domain/PaymentTest.java docs/schema/ddl.sql docs/schema/table-definitions.md
git commit -m "feat: Payment에 만료 스케줄러 PG 조회 시도 횟수 컬럼 추가 (#215)"
```

---

### Task 2: `PaymentRepository` 만료 후보 쿼리에 한도 조건 추가

**Files:**
- Modify: `src/main/java/com/gongu/server/domain/payment/repository/PaymentRepository.java`

**Interfaces:**
- Consumes: `Payment.expiryCheckAttempts` (Task 1)
- Produces: `PaymentRepository.findExpiredPendingPaymentIds(PaymentStatus, OrderStatus, LocalDateTime, int maxAttempts, Pageable)` — 시그니처에 `maxAttempts` 파라미터 추가

이 메서드는 JPQL 파생 쿼리라 별도 리포지토리 테스트 없이(기존에도 없었음) Task 4의 `PaymentExpiryScheduler` 변경과 함께 통합적으로 검증한다. 컴파일이 곧 검증이다 — 변경 직후 전체 빌드로 확인한다.

- [ ] **Step 1: 쿼리 메서드 시그니처 변경**

`src/main/java/com/gongu/server/domain/payment/repository/PaymentRepository.java`의 `findExpiredPendingPaymentIds`를 아래로 교체:

```java
    @Query("SELECT p.id FROM Payment p WHERE p.status = :status AND p.order.status = :orderStatus AND p.order.createdAt < :threshold AND p.expiryCheckAttempts < :maxAttempts ORDER BY p.id")
    List<Long> findExpiredPendingPaymentIds(@Param("status") PaymentStatus status, @Param("orderStatus") OrderStatus orderStatus, @Param("threshold") LocalDateTime threshold, @Param("maxAttempts") int maxAttempts, Pageable pageable);
```

- [ ] **Step 2: 컴파일 확인 (호출부는 Task 4에서 갱신)**

Run: `./gradlew compileJava -q`
Expected: `PaymentExpiryScheduler`에서 시그니처 불일치로 컴파일 실패 — 정상. Task 4에서 고친다. 지금은 커밋하지 않는다.

---

### Task 3: 만료 스케줄러 소진 카운터 추가

**Files:**
- Modify: `src/main/java/com/gongu/server/global/config/MetricsConfig.java`

**Interfaces:**
- Produces: Bean `paymentExpiryReconcileExhaustedCounter` (`Counter`) — `gongu.payment.expiry.reconcile_exhausted`

- [ ] **Step 1: 카운터 빈 추가**

`src/main/java/com/gongu/server/global/config/MetricsConfig.java`의 `paymentFailedAmountMismatchCounter()` 메서드 바로 아래에 추가:

```java
    @Bean
    public Counter paymentExpiryReconcileExhaustedCounter() {
        return Counter.builder("gongu.payment.expiry.reconcile_exhausted")
                .description("만료 스케줄러의 PG 조회가 한도 초과로 판정 불가 확정된 결제 수 — 운영자 확인 대상")
                .register(meterRegistry);
    }
```

- [ ] **Step 2: 컴파일 확인**

Run: `./gradlew compileJava -q`
Expected: 성공 (새 빈은 아직 아무 데서도 주입받지 않으므로 별도 실패 없음)

- [ ] **Step 3: 커밋**

이 태스크는 Task 4와 함께 한 커밋으로 묶는다 (카운터 단독으로는 의미 있는 배포 단위가 아님). 지금은 커밋하지 않고 다음 태스크로 진행한다.

---

### Task 4: `PaymentExpireService` 재작성 — PG 확인 위임 구조

**Files:**
- Modify: `src/main/java/com/gongu/server/domain/payment/service/PaymentExpireService.java`
- Modify: `src/main/java/com/gongu/server/domain/payment/scheduler/PaymentExpiryScheduler.java`
- Test: `src/test/java/com/gongu/server/domain/payment/service/PaymentExpireServiceTest.java` (전면 재작성)

**Interfaces:**
- Consumes: `PaymentService.completePayment(String paymentId, PaymentHistoryTrigger trigger)` — 기존 시그니처 그대로. 성공 시 `VerifyPaymentResponse` 반환. PG가 결제 안 됨을 확정하면 `BusinessException(PaymentErrorCode.PAYMENT_NOT_COMPLETED)`. PG 조회 자체가 실패하면 `InfraException` 또는 `BusinessException(PaymentErrorCode.PAYMENT_PG_UNAVAILABLE)` — 두 경우 모두 payment는 PENDING 유지.
- Consumes: `PaymentRepository.findExpiredPendingPaymentIds(..., maxAttempts, ...)` (Task 2)
- Consumes: `Counter paymentExpiryReconcileExhaustedCounter` (Task 3)
- Produces: `PaymentExpireService.reconcileExpiredPayment(Long paymentId, int maxAttempts)` — `cancelExpiredPayment(Long, LocalDateTime)`를 완전히 대체한다.

- [ ] **Step 1: 실패하는 테스트로 전체 교체**

`src/test/java/com/gongu/server/domain/payment/service/PaymentExpireServiceTest.java` 전체를 아래로 교체:

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
import com.gongu.server.domain.product.entity.ProductStatus;
import com.gongu.server.domain.product.service.StockRedisService;
import com.gongu.server.domain.store.entity.Store;
import com.gongu.server.domain.user.entity.User;
import com.gongu.server.global.exception.BusinessException;
import com.gongu.server.global.exception.InfraException;
import com.gongu.server.global.exception.errorcode.PaymentErrorCode;
import io.micrometer.core.instrument.Counter;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

@ExtendWith(MockitoExtension.class)
class PaymentExpireServiceTest {

    @Mock
    private PaymentRepository paymentRepository;

    @Mock
    private OrderRepository orderRepository;

    @Mock
    private OrderItemRepository orderItemRepository;

    @Mock
    private StockRedisService stockRedisService;

    @Mock
    private PaymentHistoryRecorder paymentHistoryRecorder;

    @Mock
    private PaymentService paymentService;

    @Mock
    private Counter paymentExpiryReconcileExhaustedCounter;

    @InjectMocks
    private PaymentExpireService paymentExpireService;

    private static final int MAX_ATTEMPTS = 3;

    @Test
    @DisplayName("PG가_PAID를_확인하면_completePayment로_정상_확정되고_주문은_건드리지_않는다")
    void reconcileExpiredPayment_PG_PAID_확인_시_정상_확정() {
        // given
        User user = user(1L);
        Order order = order(1L, user, 10_000L);
        Payment payment = payment(order);
        given(paymentRepository.findById(1L)).willReturn(Optional.of(payment));
        given(paymentService.completePayment("pay-uuid", PaymentHistoryTrigger.EXPIRY_SCHEDULER))
                .willReturn(new VerifyPaymentResponse(1L, "pay-uuid", 10_000L, PaymentStatus.PAID, null, OrderStatus.PAID));

        // when
        paymentExpireService.reconcileExpiredPayment(1L, MAX_ATTEMPTS);

        // then
        verify(paymentService).completePayment("pay-uuid", PaymentHistoryTrigger.EXPIRY_SCHEDULER);
        verifyNoInteractions(orderRepository);
        verifyNoInteractions(stockRedisService);
    }

    @Test
    @DisplayName("PG가_결제_안됨을_확정하면_주문을_취소하고_Redis_재고를_해제한다")
    void reconcileExpiredPayment_PG_결제_안됨_확정_시_주문_취소() {
        // given
        User user = user(1L);
        Store store = store(1L);
        Product product = product(1L, store, 10);
        Order order = order(1L, user, 10_000L);
        OrderItem item = orderItem(order, product, 2L);
        Payment payment = payment(order);

        given(paymentRepository.findById(1L)).willReturn(Optional.of(payment));
        willThrow(new BusinessException(PaymentErrorCode.PAYMENT_NOT_COMPLETED))
                .given(paymentService).completePayment("pay-uuid", PaymentHistoryTrigger.EXPIRY_SCHEDULER);
        given(orderRepository.findByIdWithLock(1L)).willReturn(Optional.of(order));
        given(orderItemRepository.findAllByOrder(order)).willReturn(List.of(item));

        // when
        paymentExpireService.reconcileExpiredPayment(1L, MAX_ATTEMPTS);

        // then
        assertThat(order.getStatus()).isEqualTo(OrderStatus.CANCELLED);
        verify(stockRedisService).releaseStockAfterCommit(1L, 2);
    }

    @Test
    @DisplayName("PG_조회_자체가_실패하면_취소하지_않고_시도_횟수만_증가시킨_뒤_예외를_전파한다")
    void reconcileExpiredPayment_PG_조회_실패_시_취소하지_않음() {
        // given
        User user = user(1L);
        Order order = order(1L, user, 10_000L);
        Payment payment = payment(order);

        given(paymentRepository.findById(1L)).willReturn(Optional.of(payment));
        willThrow(new InfraException(PaymentErrorCode.PAYMENT_PG_UNAVAILABLE))
                .given(paymentService).completePayment("pay-uuid", PaymentHistoryTrigger.EXPIRY_SCHEDULER);

        // when & then
        assertThatThrownBy(() -> paymentExpireService.reconcileExpiredPayment(1L, MAX_ATTEMPTS))
                .isInstanceOf(InfraException.class);

        assertThat(payment.getStatus()).isEqualTo(PaymentStatus.PENDING);
        assertThat(payment.getExpiryCheckAttempts()).isEqualTo(1);
        verifyNoInteractions(orderRepository);
        verifyNoInteractions(stockRedisService);
    }

    @Test
    @DisplayName("PG_조회_실패가_한도에_도달하면_소진_카운터를_올리고_이력을_남긴다")
    void reconcileExpiredPayment_한도_도달_시_소진_카운터_및_이력() {
        // given
        User user = user(1L);
        Order order = order(1L, user, 10_000L);
        Payment payment = payment(order);
        ReflectionTestUtils.setField(payment, "expiryCheckAttempts", MAX_ATTEMPTS - 1);

        given(paymentRepository.findById(1L)).willReturn(Optional.of(payment));
        willThrow(new InfraException(PaymentErrorCode.PAYMENT_PG_UNAVAILABLE))
                .given(paymentService).completePayment("pay-uuid", PaymentHistoryTrigger.EXPIRY_SCHEDULER);

        // when
        assertThatThrownBy(() -> paymentExpireService.reconcileExpiredPayment(1L, MAX_ATTEMPTS))
                .isInstanceOf(InfraException.class);

        // then
        assertThat(payment.getExpiryCheckAttempts()).isEqualTo(MAX_ATTEMPTS);
        verify(paymentExpiryReconcileExhaustedCounter).increment();
        verify(paymentHistoryRecorder).record(payment, PaymentStatus.PENDING, PaymentStatus.PENDING,
                PaymentHistoryTrigger.EXPIRY_SCHEDULER, "PG 조회 한도 초과(3회) - 운영자 확인 필요", null);
    }

    @Test
    @DisplayName("이미_한도를_초과한_Payment는_completePayment를_다시_호출하지_않는다")
    void reconcileExpiredPayment_이미_한도_초과_skip() {
        // given
        User user = user(1L);
        Order order = order(1L, user, 10_000L);
        Payment payment = payment(order);
        ReflectionTestUtils.setField(payment, "expiryCheckAttempts", MAX_ATTEMPTS);

        given(paymentRepository.findById(1L)).willReturn(Optional.of(payment));

        // when
        assertThatCode(() -> paymentExpireService.reconcileExpiredPayment(1L, MAX_ATTEMPTS))
                .doesNotThrowAnyException();

        // then
        verifyNoInteractions(paymentService);
    }

    @Test
    @DisplayName("이미_PAID된_Payment는_completePayment를_호출하지_않는다")
    void reconcileExpiredPayment_이미_PAID_skip() {
        // given
        User user = user(1L);
        Order order = order(1L, user, 10_000L);
        Payment payment = payment(order);
        ReflectionTestUtils.setField(payment, "status", PaymentStatus.PAID);

        given(paymentRepository.findById(1L)).willReturn(Optional.of(payment));

        // when
        paymentExpireService.reconcileExpiredPayment(1L, MAX_ATTEMPTS);

        // then
        verifyNoInteractions(paymentService);
    }

    @Test
    @DisplayName("존재하지_않는_Payment_예외_없음")
    void reconcileExpiredPayment_존재하지_않는_Payment_예외_없음() {
        // given
        given(paymentRepository.findById(999L)).willReturn(Optional.empty());

        // when & then
        assertThatCode(() -> paymentExpireService.reconcileExpiredPayment(999L, MAX_ATTEMPTS))
                .doesNotThrowAnyException();
        verifyNoInteractions(paymentService);
        verifyNoInteractions(orderRepository);
    }

    // --- fixture helpers ---

    private User user(Long id) {
        User user = User.of("홍길동" + id, "010-1234-567" + id);
        setId(user, id);
        return user;
    }

    private Store store(Long id) {
        Store store = Store.create("매장" + id, "서울시 강남구", "02-1234-5678");
        setId(store, id);
        return store;
    }

    private Product product(Long id, Store store, int totalStock) {
        Product product = Product.create(
                store, "상품" + id, "상품 설명", 10_000L, totalStock,
                ProductStatus.ACTIVE,
                java.time.LocalDateTime.now().minusDays(1), java.time.LocalDateTime.now().plusDays(1)
        );
        setId(product, id);
        return product;
    }

    private Order order(Long id, User user, long totalPrice) {
        Order order = Order.create(user, totalPrice);
        setId(order, id);
        return order;
    }

    private OrderItem orderItem(Order order, Product product, Long quantity) {
        OrderItem item = OrderItem.create(order, product, quantity);
        setId(item, 1L);
        return item;
    }

    private Payment payment(Order order) {
        Payment p = Payment.initiate(order, "idem-key", "pay-uuid", order.getTotalPrice());
        setId(p, 1L);
        return p;
    }

    private void setId(Object target, Long id) {
        ReflectionTestUtils.setField(target, "id", id);
    }
}
```

- [ ] **Step 2: 테스트 실패 확인**

Run: `./gradlew test --tests "*PaymentExpireServiceTest*" -q`
Expected: 컴파일 실패 — `reconcileExpiredPayment` 메서드 없음, `PaymentExpireService` 생성자에 `PaymentService`/`Counter` 의존성 없음.

- [ ] **Step 3: `PaymentExpireService` 재작성**

`src/main/java/com/gongu/server/domain/payment/service/PaymentExpireService.java` 전체를 아래로 교체:

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
import com.gongu.server.domain.product.service.StockRedisService;
import com.gongu.server.global.exception.BusinessException;
import com.gongu.server.global.exception.InfraException;
import com.gongu.server.global.exception.errorcode.PaymentErrorCode;
import io.micrometer.core.instrument.Counter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;

/**
 * 만료 스케줄러가 집어간 PENDING 결제를 PG 재확인 없이 취소하던 것(#215)을 고친다.
 * PG 조회·PAID 확정 로직은 completePayment(verify/webhook과 동일 경로)를 그대로 재사용하고,
 * 이 클래스는 그 결과에 따른 분기(주문 취소 / 재시도 대기 / 한도 초과 표시)만 담당한다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PaymentExpireService {

    private final PaymentRepository paymentRepository;
    private final OrderRepository orderRepository;
    private final OrderItemRepository orderItemRepository;
    private final StockRedisService stockRedisService;
    private final PaymentHistoryRecorder paymentHistoryRecorder;
    private final PaymentService paymentService;
    @Qualifier("paymentExpiryReconcileExhaustedCounter")
    private final Counter paymentExpiryReconcileExhaustedCounter;

    public void reconcileExpiredPayment(Long paymentId, int maxAttempts) {
        Optional<Payment> optionalPayment = paymentRepository.findById(paymentId);
        if (optionalPayment.isEmpty()) {
            return;
        }

        Payment payment = optionalPayment.get();
        if (payment.getStatus() != PaymentStatus.PENDING) {
            return;
        }
        if (payment.getExpiryCheckAttempts() >= maxAttempts) {
            return;
        }

        String merchantUid = payment.getMerchantUid();
        Long orderId = payment.getOrder().getId();

        try {
            // PG 호출은 completePayment 내부의 짧은 트랜잭션 안에서만 일어난다 —
            // 여기서는 어떤 트랜잭션도 들고 있지 않은 채로 호출한다 (#146 원칙).
            paymentService.completePayment(merchantUid, PaymentHistoryTrigger.EXPIRY_SCHEDULER);
        } catch (BusinessException e) {
            if (e.getErrorCode() == PaymentErrorCode.PAYMENT_NOT_COMPLETED) {
                // PG가 결제 안 됨을 확정 — completePayment가 이미 payment를 FAILED로 확정·기록했다.
                // 남은 건 예약된 주문·재고를 정리하는 것뿐이다.
                cancelOrderAfterPgConfirmedUnpaid(orderId);
                return;
            }
            if (e.getErrorCode() == PaymentErrorCode.PAYMENT_PG_UNAVAILABLE) {
                recordInconclusiveAttempt(paymentId, maxAttempts);
            }
            throw e;
        } catch (InfraException e) {
            // PG 조회 판정 불가 — 취소하지 않는다. 다음 스케줄러 주기가 다시 시도한다.
            recordInconclusiveAttempt(paymentId, maxAttempts);
            throw e;
        }
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void cancelOrderAfterPgConfirmedUnpaid(Long orderId) {
        Optional<Order> optionalOrder = orderRepository.findByIdWithLock(orderId);
        if (optionalOrder.isEmpty()) {
            return;
        }
        Order order = optionalOrder.get();
        if (order.getStatus() != OrderStatus.RESERVED) {
            return;
        }

        List<OrderItem> items = orderItemRepository.findAllByOrder(order);
        order.cancel("PG 미결제 확인 - 만료 취소");

        items.forEach(item ->
                stockRedisService.releaseStockAfterCommit(item.getProduct().getId(), Math.toIntExact(item.getQuantity()))
        );
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void recordInconclusiveAttempt(Long paymentId, int maxAttempts) {
        paymentRepository.findById(paymentId).ifPresent(payment -> {
            if (payment.getStatus() != PaymentStatus.PENDING) {
                return;
            }
            int attempts = payment.incrementExpiryCheckAttempts();
            if (attempts >= maxAttempts) {
                paymentExpiryReconcileExhaustedCounter.increment();
                paymentHistoryRecorder.record(payment, payment.getStatus(), payment.getStatus(),
                        PaymentHistoryTrigger.EXPIRY_SCHEDULER,
                        "PG 조회 한도 초과(" + attempts + "회) - 운영자 확인 필요", null);
            }
        });
    }
}
```

- [ ] **Step 4: `PaymentExpiryScheduler` 호출부 갱신**

`src/main/java/com/gongu/server/domain/payment/scheduler/PaymentExpiryScheduler.java` 전체를 아래로 교체:

```java
package com.gongu.server.domain.payment.scheduler;

import com.gongu.server.domain.order.entity.OrderStatus;
import com.gongu.server.domain.payment.domain.PaymentStatus;
import com.gongu.server.domain.payment.repository.PaymentRepository;
import com.gongu.server.domain.payment.service.PaymentExpireService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.List;

@Slf4j
@Component
@RequiredArgsConstructor
public class PaymentExpiryScheduler {

    private final PaymentExpireService paymentExpireService;
    private final PaymentRepository paymentRepository;

    @Value("${order.reservation-ttl-minutes}")
    private long reservationTtlMinutes;

    @Value("${payment.expiry.batch-size:100}")
    private int paymentExpiryBatchSize;

    @Value("${payment.expiry.max-reconcile-attempts:5}")
    private int paymentExpiryMaxReconcileAttempts;

    @Scheduled(fixedDelayString = "${payment.expiry.fixed-delay-ms:60000}")
    public void expireReservedPayments() {
        LocalDateTime threshold = LocalDateTime.now().minusMinutes(reservationTtlMinutes);
        List<Long> expiredIds = paymentRepository.findExpiredPendingPaymentIds(
                PaymentStatus.PENDING, OrderStatus.RESERVED, threshold, paymentExpiryMaxReconcileAttempts,
                PageRequest.of(0, paymentExpiryBatchSize));

        int count = 0;
        for (Long id : expiredIds) {
            try {
                paymentExpireService.reconcileExpiredPayment(id, paymentExpiryMaxReconcileAttempts);
                count++;
            } catch (Exception e) {
                log.warn("만료 Payment 재확인 실패 — 다음 주기로 미룸: paymentId={}", id, e);
            }
        }
        log.info("만료 Payment 처리 완료: {}건", count);
    }
}
```

- [ ] **Step 5: 테스트 통과 확인**

Run: `./gradlew test --tests "*PaymentExpireServiceTest*" -q`
Expected: PASS (7개 테스트)

- [ ] **Step 6: 전체 빌드 확인**

Run: `./gradlew compileJava compileTestJava -q`
Expected: 성공 (Task 2에서 깨졌던 컴파일이 이제 복구됨)

- [ ] **Step 7: 커밋**

```bash
git add src/main/java/com/gongu/server/domain/payment/repository/PaymentRepository.java \
        src/main/java/com/gongu/server/domain/payment/service/PaymentExpireService.java \
        src/main/java/com/gongu/server/domain/payment/scheduler/PaymentExpiryScheduler.java \
        src/main/java/com/gongu/server/global/config/MetricsConfig.java \
        src/test/java/com/gongu/server/domain/payment/service/PaymentExpireServiceTest.java
git commit -m "fix: 만료 스케줄러가 취소 전 PG를 확인하도록 수정, 한도 초과 시 소진 카운터 기록 (#215)"
```

---

### Task 5: 로컬 DB 마이그레이션 적용 + 전체 검증

**Files:** 없음 (운영 작업 + 검증만)

- [ ] **Step 1: 로컬 MySQL에 컬럼 추가**

이 컨테이너(`gongu-mysql`, 포트 3307)는 `ddl-auto: validate`라서 컬럼이 실제로 없으면 앱이 기동 실패한다. Flyway가 없으므로 수동으로 맞춘다.

Run:
```bash
docker exec -e MYSQL_PWD=12345678 gongu-mysql mysql -uroot gongu_db -e \
  "ALTER TABLE payments ADD COLUMN expiry_check_attempts INT NOT NULL DEFAULT 0;"
```

Expected: 에러 없이 완료. 기존 row는 전부 0으로 채워진다.

- [ ] **Step 2: 전체 테스트 스위트 실행**

Run: `./gradlew test -q`
Expected: 전 테스트 통과 (jacoco 커버리지 게이트 포함 — 이번엔 필터링 없이 전체 실행이므로 통과해야 한다)

- [ ] **Step 3: 로컬 서버 기동 확인**

Run:
```bash
./gradlew bootRun --args='--spring.profiles.active=perf --portone.base-url=https://api.portone.io' &
```
잠시 후 `curl http://localhost:8080/actuator/health`로 `{"status":"UP"}` 확인, 그다음 프로세스 종료.

- [ ] **Step 4: push + PR 생성**

```bash
git push -u origin "fix/#215-payment-expire-pg-check"
gh pr create --title "[FIX] 만료 스케줄러가 PG 확인 없이 주문을 취소 (#215)" --body "$(cat <<'EOF'
## 요약
- 만료 스케줄러가 PENDING 결제를 취소하기 전에 PG(PortOne)를 확인하도록 수정 (`PaymentService.completePayment` 재사용)
- PG가 PAID를 확인하면 정상 확정, "결제 안 됨"을 확정하면 주문 취소 + Redis 재고 해제, PG 조회 자체가 실패하면 취소하지 않고 다음 주기로 미룸
- `payments.expiry_check_attempts` 컬럼 추가 — 재시도 한도(기본 5회) 초과 시 `gongu.payment.expiry.reconcile_exhausted` 카운터 증가 + `payment_histories`에 "PG 조회 한도 초과" 이력 기록

## 완료 기준
- [x] 만료 처리 전 PG 조회 추가 — PAID면 정상 확정 경로로 전환
- [x] PG 조회 자체가 실패하면 취소하지 말고 다음 주기로 미룰 것
- [x] 시도 횟수 및 한도 정책 확정, 초과 건 식별 수단 마련
- [x] PG 조회를 스케줄러 트랜잭션 밖에서 수행
- [x] 회귀 테스트: PG가 PAID를 확인한 건은 만료 시각에 취소되지 않음을 검증

## 테스트
- `./gradlew test` 전체 통과

Closes #215
EOF
)"
```
