# Payment/Order 만료 취소 연동 및 보상 처리 구현 계획

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** PENDING Payment가 있는 Order의 만료 처리 연동 및 결제 완료 후 스케줄러 race condition 보상 트랜잭션 구현

**Spec:** `docs/superpowers/specs/2026-06-03-payment-order-expiry-design.md`

**Tech Stack:** Spring Boot 3.5, Java 25, JPA (Pessimistic Lock), PortOne V2

---

## 브랜치

`feat/#139-payment-order-expiry-integration` (main에서 분기)

---

## 파일 맵

### 신규 생성
- `src/main/java/com/gongu/server/domain/payment/service/PaymentExpireService.java`
- `src/main/java/com/gongu/server/domain/order/scheduler/PaymentExpiryScheduler.java`
- `src/test/java/com/gongu/server/domain/payment/service/PaymentExpireServiceTest.java`

### 수정
- `src/main/java/com/gongu/server/domain/payment/domain/PaymentStatus.java`
- `src/main/java/com/gongu/server/domain/payment/domain/Payment.java`
- `src/main/java/com/gongu/server/global/exception/errorcode/PaymentErrorCode.java`
- `src/main/java/com/gongu/server/domain/payment/repository/PaymentRepository.java`
- `src/main/java/com/gongu/server/domain/order/repository/OrderRepository.java`
- `src/main/java/com/gongu/server/domain/order/service/OrderExpireService.java` (NOT EXISTS 조건 대응 확인)
- `src/main/java/com/gongu/server/domain/payment/service/PaymentService.java`
- `src/test/java/com/gongu/server/domain/order/service/OrderExpireServiceTest.java`

### 참고 전용 (수정 금지)
- `src/main/java/com/gongu/server/domain/order/scheduler/OrderExpiryScheduler.java` — Task 4에서만 수정
- `src/main/java/com/gongu/server/domain/order/entity/Order.java` — cancel() 시그니처 확인용
- `docs/adr/예외_처리_전략.md` — BusinessException 컨벤션 확인용

---

## Task 1: Payment 도메인 모델 변경

- [ ] PaymentStatus, Payment 메서드, PaymentErrorCode 변경

**참고 문서/파일:**
- Spec: `docs/superpowers/specs/2026-06-03-payment-order-expiry-design.md` — "Payment 상태 머신 변경" 섹션
- `src/main/java/com/gongu/server/domain/payment/domain/Payment.java` — 현재 메서드 시그니처 확인
- `docs/adr/예외_처리_전략.md` — BusinessException 사용 컨벤션

**수정 대상 파일:**
- Modify: `src/main/java/com/gongu/server/domain/payment/domain/PaymentStatus.java`
- Modify: `src/main/java/com/gongu/server/domain/payment/domain/Payment.java`
- Modify: `src/main/java/com/gongu/server/global/exception/errorcode/PaymentErrorCode.java`

**구현 방향:**

`PaymentStatus.java`:
- `REFUNDED` 값 추가 → `PENDING, PAID, CANCELLED, FAILED, REFUNDED`

`Payment.java`:
- `cancelByMismatch()` 메서드 삭제. 대신 `refund()` 메서드 추가.
  - `refund()`: status가 PENDING 또는 PAID가 아니면 `PAYMENT_INVALID_STATE_TRANSITION` 예외. 조건 통과 시 `this.status = PaymentStatus.REFUNDED`, `this.cancelledAt = LocalDateTime.now()`
- `cancel()` 메서드 삭제. `refund()` 가 PAID 상태도 처리하므로 통합.
- `expire()` 메서드 추가.
  - `expire()`: status가 PENDING이 아니면 `PAYMENT_INVALID_STATE_TRANSITION` 예외. `this.status = PaymentStatus.CANCELLED`, `this.cancelledAt = LocalDateTime.now()`

`PaymentErrorCode.java`:
- `ORDER_EXPIRED_REFUNDED("PAYMENT_008", "주문 만료로 결제가 자동 환불되었습니다", 409)` 추가

**검증:**
```bash
./gradlew compileJava
```
Expected: BUILD SUCCESSFUL

**커밋:**
```bash
git add src/main/java/com/gongu/server/domain/payment/domain/PaymentStatus.java \
        src/main/java/com/gongu/server/domain/payment/domain/Payment.java \
        src/main/java/com/gongu/server/global/exception/errorcode/PaymentErrorCode.java
git commit -m "feat: PaymentStatus REFUNDED 추가 및 Payment 상태 전이 메서드 변경 (#139)"
```

---

## Task 2: Repository 쿼리 추가 및 수정

- [ ] PaymentRepository에 만료 Payment 조회 쿼리 추가, OrderRepository의 만료 Order 조회 쿼리에 NOT EXISTS 조건 추가

**참고 문서/파일:**
- Spec: `docs/superpowers/specs/2026-06-03-payment-order-expiry-design.md` — "스케줄러 분리" 섹션
- `src/main/java/com/gongu/server/domain/order/repository/OrderRepository.java` — `findExpiredReservedOrderIds` 기존 쿼리 참고
- `src/main/java/com/gongu/server/domain/payment/repository/PaymentRepository.java` — `findByMerchantUidWithLock` Lock 패턴 참고

**수정 대상 파일:**
- Modify: `src/main/java/com/gongu/server/domain/payment/repository/PaymentRepository.java`
- Modify: `src/main/java/com/gongu/server/domain/order/repository/OrderRepository.java`

**구현 방향:**

`PaymentRepository.java`에 두 메서드 추가:

1. 만료 대상 PENDING Payment ID 목록 조회 (락 없음):
```java
@Query("SELECT p.id FROM Payment p WHERE p.status = :status AND p.order.status = :orderStatus AND p.order.createdAt < :threshold ORDER BY p.id")
List<Long> findExpiredPendingPaymentIds(@Param("status") PaymentStatus status, @Param("orderStatus") OrderStatus orderStatus, @Param("threshold") LocalDateTime threshold, Pageable pageable);
```

2. ID로 Payment 비관적 락 조회 추가:
```java
@Lock(LockModeType.PESSIMISTIC_WRITE)
@Query("SELECT p FROM Payment p JOIN FETCH p.order WHERE p.id = :id")
Optional<Payment> findByIdWithLock(@Param("id") Long id);
```

`OrderRepository.java`에서 `findExpiredReservedOrderIds` 쿼리를 아래로 교체:
```java
@Query("SELECT o.id FROM Order o WHERE o.status = :status AND o.createdAt < :threshold AND NOT EXISTS (SELECT 1 FROM Payment p WHERE p.order = o AND p.status = 'PENDING') ORDER BY o.id")
List<Long> findExpiredReservedOrderIds(@Param("status") OrderStatus status, @Param("threshold") LocalDateTime threshold, Pageable pageable);
```
- 기존 쿼리 파라미터에서 `:status`는 그대로 유지. `PageRequest.of(0, 100)` 호출부(`OrderExpiryScheduler.java`)는 변경 불필요.

**검증:**
```bash
./gradlew compileJava
```
Expected: BUILD SUCCESSFUL

**커밋:**
```bash
git add src/main/java/com/gongu/server/domain/payment/repository/PaymentRepository.java \
        src/main/java/com/gongu/server/domain/order/repository/OrderRepository.java
git commit -m "feat: Payment 만료 조회 쿼리 추가 및 Order 만료 조회에 NOT EXISTS 조건 적용 (#139)"
```

---

## Task 3: PaymentExpireService 신규 구현

- [ ] PENDING Payment + Order 함께 만료 취소하는 서비스 구현

**참고 문서/파일:**
- Spec: `docs/superpowers/specs/2026-06-03-payment-order-expiry-design.md` — "Payment 취소 스케줄러", "락 순서 정합성" 섹션
- `src/main/java/com/gongu/server/domain/order/service/OrderExpireService.java` — REQUIRES_NEW 트랜잭션 + double-check 패턴 참고
- `src/main/java/com/gongu/server/domain/order/entity/Order.java` — `cancel(String reason)` 시그니처 확인
- `src/main/java/com/gongu/server/domain/product/repository/ProductRepository.java` — `findByIdWithLock` 사용 여부 확인

**수정 대상 파일:**
- Create: `src/main/java/com/gongu/server/domain/payment/service/PaymentExpireService.java`

**구현 방향:**

패키지: `com.gongu.server.domain.payment.service`

`cancelExpiredPayment(Long paymentId, LocalDateTime threshold)` 메서드:
- `@Transactional(propagation = Propagation.REQUIRES_NEW)`
- `paymentRepository.findByIdWithLock(paymentId)` — Payment 먼저 락 (completePayment와 동일 순서)
- double-check 1: payment가 없으면 return
- double-check 2: `payment.getStatus() != PaymentStatus.PENDING`이면 return
- `orderRepository.findByIdWithLock(payment.getOrder().getId())` — Order 락
- double-check 3: `order.getStatus() != OrderStatus.RESERVED`이면 return
- double-check 4: `!order.getCreatedAt().isBefore(threshold)`이면 return
- 재고 복구: `OrderItem` 목록 조회 → Product ID 오름차순 정렬 → 각 Product 비관적 락 획득 → `product.restoreStock()` (OrderExpireService의 기존 재고 복구 로직과 동일)
- `payment.expire()` 호출
- `order.cancel("결제 시간 초과")` 호출

**검증:**
```bash
./gradlew compileJava
```
Expected: BUILD SUCCESSFUL

**커밋:**
```bash
git add src/main/java/com/gongu/server/domain/payment/service/PaymentExpireService.java
git commit -m "feat: PaymentExpireService 구현 (만료 PENDING Payment + Order 함께 취소) (#139)"
```

---

## Task 4: 스케줄러 분리 — PaymentExpiryScheduler 추가 및 OrderExpiryScheduler 쿼리 반영

- [ ] PaymentExpiryScheduler 신규 추가, OrderExpiryScheduler 쿼리 변경 반영

**참고 문서/파일:**
- Spec: `docs/superpowers/specs/2026-06-03-payment-order-expiry-design.md` — "스케줄러 분리", "TTL 설정" 섹션
- `src/main/java/com/gongu/server/domain/order/scheduler/OrderExpiryScheduler.java` — 기존 패턴 참고 (fixedDelay, @Value, 예외 로깅)

**수정 대상 파일:**
- Create: `src/main/java/com/gongu/server/domain/order/scheduler/PaymentExpiryScheduler.java`
- Modify: `src/main/java/com/gongu/server/domain/order/scheduler/OrderExpiryScheduler.java`

**구현 방향:**

`PaymentExpiryScheduler.java`:
- 패키지: `com.gongu.server.domain.order.scheduler`
- `@Scheduled(fixedDelay = 60_000)`
- `@Value("${order.reservation-ttl-minutes}")` 로 TTL 주입 (Order 스케줄러와 동일한 프로퍼티 사용)
- `paymentRepository.findExpiredPendingPaymentIds(PaymentStatus.PENDING, OrderStatus.RESERVED, threshold, PageRequest.of(0, 100))`로 대상 조회
- 각 paymentId에 대해 `paymentExpireService.cancelExpiredPayment(id, threshold)` 호출
- 예외 발생 시 `log.warn("만료 Payment 취소 실패: paymentId={}", id, e)`로 개별 실패 처리 (OrderExpiryScheduler와 동일한 패턴)

`OrderExpiryScheduler.java`:
- Task 2에서 수정된 `findExpiredReservedOrderIds` 쿼리가 이미 NOT EXISTS 조건을 포함하므로 코드 변경 없음. 컴파일 확인만.

**검증:**
```bash
./gradlew compileJava
```
Expected: BUILD SUCCESSFUL

**커밋:**
```bash
git add src/main/java/com/gongu/server/domain/order/scheduler/PaymentExpiryScheduler.java \
        src/main/java/com/gongu/server/domain/order/scheduler/OrderExpiryScheduler.java
git commit -m "feat: PaymentExpiryScheduler 추가 (60초 주기 만료 Payment 자동 취소) (#139)"
```

---

## Task 5: completePayment() race condition 보상 처리

- [ ] Order CANCELLED 감지 → PortOne 환불 → Payment REFUNDED 처리

**참고 문서/파일:**
- Spec: `docs/superpowers/specs/2026-06-03-payment-order-expiry-design.md` — "문제 2: completePayment() race condition 보상 처리" 섹션
- `src/main/java/com/gongu/server/domain/payment/service/PaymentService.java` — `completePayment()` 전체 흐름, `noRollbackFor` 설정 확인
- `src/main/java/com/gongu/server/global/infrastructure/portone/PortOneClient.java` — `cancelPayment()` 시그니처 확인

**수정 대상 파일:**
- Modify: `src/main/java/com/gongu/server/domain/payment/service/PaymentService.java`

**구현 방향:**

`completePayment()` 내 Order 락 획득 직후, PortOne 조회 이전에 아래 블록 삽입:

```
Order order = orderRepository.findByIdWithLock(...)  // 기존 코드

// [추가] race condition 보상 처리
if (order.getStatus() == OrderStatus.CANCELLED) {
    portOneClient.cancelPayment(paymentId, "주문 만료로 인한 자동 환불");
    payment.refund();
    throw new BusinessException(PaymentErrorCode.ORDER_EXPIRED_REFUNDED);
    // noRollbackFor 설정으로 REFUNDED 상태가 커밋됨
}

PortOnePaymentResponse portOneResponse = ...  // 기존 코드
```

기존 `payment.cancelByMismatch()` 호출부를 `payment.refund()`로 교체 (Task 1에서 메서드 삭제됨).

**금지 사항:**
- `noRollbackFor` 설정 변경 금지 — 기존 설정이 보상 처리 커밋을 보장함
- PortOne 조회(`getPayment`) 로직 순서 변경 금지

**검증:**
```bash
./gradlew compileJava
```
Expected: BUILD SUCCESSFUL

**커밋:**
```bash
git add src/main/java/com/gongu/server/domain/payment/service/PaymentService.java
git commit -m "feat: completePayment race condition 보상 처리 구현 (Order CANCELLED 감지 → 자동 환불) (#139)"
```

---

## Task 6: 단위 테스트 작성

- [ ] PaymentExpireService, Payment 상태 전이, completePayment 보상 처리 테스트

**참고 문서/파일:**
- `src/test/java/com/gongu/server/domain/order/service/OrderExpireServiceTest.java` — Mockito 패턴, fixture helper 스타일 참고
- `src/main/java/com/gongu/server/domain/payment/service/PaymentExpireService.java` — 테스트 대상
- `src/main/java/com/gongu/server/domain/payment/service/PaymentService.java` — completePayment 테스트 대상

**수정 대상 파일:**
- Create: `src/test/java/com/gongu/server/domain/payment/service/PaymentExpireServiceTest.java`
- Modify: `src/test/java/com/gongu/server/domain/order/service/OrderExpireServiceTest.java`
- Modify: (기존 PaymentServiceTest가 있다면 보상 처리 케이스 추가, 없다면 생략)

**구현 방향:**

`PaymentExpireServiceTest.java` — `@ExtendWith(MockitoExtension.class)`:

| 테스트명 | 시나리오 |
|---------|---------|
| `만료된_PENDING_Payment_취소_및_Order_취소_재고_복구` | 정상 만료 처리 |
| `이미_PAID된_Payment_skip` | double-check: payment.status == PAID → return |
| `Order가_이미_PAID_상태_skip` | double-check: order.status == PAID → return |
| `아직_유효한_Payment_skip` | double-check: threshold 이전 생성 → return |
| `존재하지_않는_Payment_예외_없음` | Optional.empty() → return |

`OrderExpireServiceTest.java`:
- 기존 테스트는 변경 없음. `cancelExpiredOrder`는 서명 변화 없으므로 기존 테스트 그대로 유지.
- PENDING Payment 있는 Order가 Order 스케줄러에서 skip되는 것은 Repository 쿼리 레벨에서 처리되므로 서비스 단위 테스트 추가 불필요.

**검증:**
```bash
./gradlew test --tests "com.gongu.server.domain.payment.service.PaymentExpireServiceTest"
./gradlew test --tests "com.gongu.server.domain.order.service.OrderExpireServiceTest"
```
Expected: 모든 테스트 PASSED

**커밋:**
```bash
git add src/test/java/com/gongu/server/domain/payment/service/PaymentExpireServiceTest.java \
        src/test/java/com/gongu/server/domain/order/service/OrderExpireServiceTest.java
git commit -m "test: PaymentExpireService 단위 테스트 추가 (#139)"
```

---

## Task 7: ADR 작성 및 GitHub Issue #139 결론 업데이트

- [ ] ADR 작성, Issue 결론 섹션 업데이트

**수정 대상 파일:**
- Create: `docs/adr/payment_만료_취소_설계.md`

**구현 방향:**

ADR 작성 (`docs/adr/` 내 기존 ADR 파일 형식 참고):
- 결정 사항: Payment 상태 머신에 REFUNDED 추가, 스케줄러 분리 (Payment + Order 독립), completePayment 보상 트랜잭션
- 선택하지 않은 대안: PENDING Payment skip (A안), 단일 스케줄러, TTL만으로 race condition 처리
- 근거: 돈 흐름 기준 상태 구분, 락 순서 통일로 데드락 방지, webhook 지연 대비 안전망

GitHub Issue #139 결론 섹션 업데이트:
```bash
gh issue edit 139 --repo hkjbrian/gongu --body "$(cat <<'EOF'
[기존 이슈 본문 유지]

## 결론

### 문제 1: PENDING Payment 만료 처리
**B안 변형 채택** — 스케줄러를 두 개로 분리
- Payment 취소 스케줄러: PENDING Payment가 있는 만료 Order → Payment(CANCELLED) + Order(CANCELLED) 함께 처리. 락 순서: Payment → Order (completePayment와 동일, 데드락 방지)
- Order 취소 스케줄러: PENDING Payment 없는 만료 Order → Order(CANCELLED)만 처리
- TTL: 두 스케줄러 모두 PortOne TTL(15분)보다 긴 20분 사용

### 문제 2: Race condition 보상 처리
**A안 채택** — completePayment()에서 Order CANCELLED 감지 → PortOne 환불 → Payment REFUNDED
- TTL 마진만으로는 webhook 지연 케이스를 100% 보장할 수 없어 보상 트랜잭션을 안전망으로 추가

### 추가 결정
- PaymentStatus에 REFUNDED 추가: 돈이 이동했다가 반환된 모든 케이스를 CANCELLED와 구분
- cancelByMismatch() → refund()로 통합: PAID 여부와 무관하게 동일한 메서드로 처리
EOF
)"
```
(실제 커맨드 실행 시 기존 이슈 본문을 gh issue view로 먼저 읽어 유지)

**검증:**
```bash
./gradlew test
```
Expected: BUILD SUCCESSFUL, 전체 테스트 PASSED

**커밋:**
```bash
git add docs/adr/payment_만료_취소_설계.md
git commit -m "docs: #139 Payment 만료 취소 설계 ADR 추가"
```

---

## 전체 검증 후 PR

```bash
./gradlew test
```
Expected: BUILD SUCCESSFUL, 전체 테스트 PASSED

```bash
git push origin feat/#139-payment-order-expiry-integration
gh pr create \
  --title "[FEAT] Payment/Order 만료 취소 연동 및 race condition 보상 처리 (#139)" \
  --body "..." \
  --milestone "결제 도메인"
```

PR 생성 후 CLAUDE.md 9~11단계(Codex 리뷰 위임 → 판정 → 반영) 진행.
