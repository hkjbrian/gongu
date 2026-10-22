# #249 completePayment 가독성 개선 — noRollbackFor 제거 및 결과 반환 방식 전환 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 약 150줄짜리 `PaymentReconciler.completePayment`를 단계별 private 메서드로 나누고, "DB를 바꿨는데도 예외를 던져 커밋시키는" `noRollbackFor` 구조를 "결과를 반환해 커밋"하는 구조로 바꿔 읽기 쉽게 만든다. 외부에서 보이는 동작(예외 코드, 응답, DB 최종 상태)은 바꾸지 않는다.

**Architecture:** 3단계로 간다. (1) 공백 분기에 특성화 테스트를 먼저 추가한다(원본 코드에서 통과해야 함). (2) 분기별 메서드로 분리하고 보상 중복을 통합한다(동작·기존 테스트 불변). (3) `PaymentReconciler.completePayment`가 `Confirmed(응답)` / `Rejected(에러코드)`를 반환하게 하고 `noRollbackFor`를 제거한다. 트랜잭션 밖의 `PaymentService.completePayment`가 `Rejected`를 같은 에러코드의 `BusinessException`으로 변환해 던진다.

**Tech Stack:** Spring Boot 3.5, Java 25, Spring Data JPA, JUnit5 + Mockito + AssertJ, `@SpringBootTest`(통합 테스트).

**Spec:** GitHub 이슈 #249, `docs/adr/결제_확정_아키텍처.md`(ADR-008, 특히 P3·D1)

## Global Constraints

- 커밋 메시지 형식: `type: 작업 내용 (#249)`. `Co-Authored-By` 절 절대 포함 금지. 커밋·push는 Claude(오케스트레이터)가 직접 한다 — 서브에이전트는 커밋하지 않는다.
- 락 순서 유지: Payment → Order → Product(항목별). 락을 없애지 않는다(ADR-008 D2는 범위 밖).
- **PG 취소(`executePGCancel`)는 항상 상태 변경(`refund()`, `expire()`, `cancel()` 등)보다 먼저 실행한다.** PG 취소가 예외를 던지면 DB는 아무것도 바뀌지 않은 채여야 한다.
- 재고는 "전 항목 확인 → 전 항목 확정" 두 단계를 유지한다. 확인하면서 바로 차감하는 루프로 합치지 않는다. 재고 확인은 `order.pay()` / `payment.confirm()`보다 먼저다(#230).
- 이력의 변경 전 상태(`before`)는 상태 변경 호출 **이전에** 저장한다.
- `PaymentHistoryRecorder.record(...)`는 `Propagation.MANDATORY` — 반드시 리컨실러 트랜잭션 안에서 호출한다.
- `PaymentService`와 클래스에는 `@Transactional`을 붙이지 않는다(커넥션 점유 이슈, 코드 주석 참조). `@Bulkhead(name = "payment-complete")`는 `PaymentService.completePayment`에만 둔다.
- `PaymentService.completePayment`가 던지는 예외 코드는 현재와 동일해야 한다. `PaymentController`(웹훅 `WEBHOOK_TERMINAL_CODES`)와 `PaymentExpireService`(`PAYMENT_NOT_COMPLETED`, `ORDER_EXPIRED_REFUNDED`, `PAYMENT_AMOUNT_MISMATCH`, `PAYMENT_NOT_FOUND`, `PAYMENT_PG_UNAVAILABLE`)가 이 코드에 의존한다.
- 테스트 실행: `./gradlew test --offline -x jacocoTestReport -x jacocoTestCoverageVerification --tests '<패턴>'` (일부만 돌리면 jacoco 커버리지 검증이 실패하므로 제외). 마지막 Task에서만 전체 `./gradlew test`.
- 작업 경로: 워크트리 `server/.claude/worktrees/refactor-249` (브랜치 `refactor/#249-completepayment-readability`).

## Review Focus

1. 상품 2개 이상 주문에서 뒤 상품이 부족할 때 앞 상품이 차감되지 않는다 (Task 1 유닛 테스트). 현재 `OrderService.createOrder`는 항목 1개짜리 주문만 만들어 API로는 도달하지 못하는 경로지만, 코드가 일부러 방어하고 있어 회귀를 막아 둔다.
2. PG 취소가 실패(`InfraException` 또는 알 수 없는 `BusinessException`)하면 payment·order·재고가 하나도 바뀌지 않는다 (Task 1).
3. `Rejected`로 끝나는 네 분기(만료 환불, PG 상태 불일치, 금액 불일치, 재고 부족)의 DB 변경이 실제로 커밋된다 (Task 1 통합 테스트 — Task 3 이후에도 수정 없이 통과해야 함).
4. 예외로 끝나는 분기(PG 조회·취소 `InfraException` 등)는 DB가 그대로다 (Task 1 통합 테스트).
5. 이력 기록(`before → after`, 사유 문자열, PG 원문 폴백)과 실패 카운터가 분기마다 정확하다 (Task 1).

---

### Task 1: 공백 분기 특성화 테스트 추가 (원본 코드에서 통과)

**Files:**
- Modify: `src/test/java/com/gongu/server/domain/payment/service/PaymentReconcilerTest.java`
- Create: `src/test/java/com/gongu/server/domain/payment/service/PaymentCompletionCommitIntegrationTest.java`

**Interfaces:**
- Consumes: 현재의 `PaymentReconciler.completePayment(String, PaymentHistoryTrigger, PortOnePaymentResult)`가 던지는 예외 계약(그대로). 프로덕션 코드는 건드리지 않는다.
- Produces: Task 2·3의 안전망. 모든 테스트가 **원본 코드에서 통과**해야 한다.

모든 테스트는 기존 `PaymentReconcilerTest`의 `setUp`·헬퍼·상수(`PAYMENT_ID`, `AMOUNT`, `order` mock)를 재사용한다. 카운터 필드(`paymentFailedAmountMismatchCounter` 등)는 같은 클래스 안이라 직접 단언할 수 있다. 이력/카운터 테스트는 상태 전이를 확인해야 하므로 `Payment.initiate(order, "idem-key", PAYMENT_ID, AMOUNT)`로 만든 **실제 Payment**를 쓴다(기존 `completePayment_조회실패_재시도_정상확정` 방식).

- [ ] **Step 1: `PaymentReconcilerTest`에 빈 분기·PG 취소 변형·다중 상품 테스트 추가** (Mock 기반, 기존 테스트 스타일)
  - `completePayment_주문없음_ORDER_NOT_FOUND`: `orderRepository.findByIdWithLock`이 empty → `BusinessException(ORDER_NOT_FOUND)`.
  - `completePayment_상품없음_PRODUCT_NOT_FOUND`: PG 결과 PAID·금액 일치, `productRepository.findByIdWithLock`이 empty → `BusinessException(PRODUCT_NOT_FOUND)`, `order.pay()`·`payment.confirm()`·`payment.refund()` 호출 없음.
  - `completePayment_주문CANCELLED_결제FAILED_INVALID_STATE_TRANSITION`: order CANCELLED + payment FAILED → `PAYMENT_INVALID_STATE_TRANSITION`, `portOneClient.cancelPayment` 호출 없음.
  - `completePayment_주문CANCELLED_CANCELLED결제_PG결제없음_환불도_만료도_하지_않는다`: order CANCELLED + payment CANCELLED + `cancelPayment`가 `BusinessException(PAYMENT_NOT_FOUND)` → `ORDER_EXPIRED_REFUNDED`, `payment.refund()`·`payment.expire()` 모두 호출 없음.
  - `completePayment_금액불일치_PG취소실패_상태불변`: 금액 불일치 + `cancelPayment`가 `InfraException` → `InfraException` 전파, `payment.refund()`·`order.cancel(...)`·`releaseStockAfterCommit` 호출 없음.
  - `completePayment_PG취소_ALREADY_PROCESSED면_취소된_것으로_보고_환불을_진행한다`: 재고 부족 + `cancelPayment`가 `BusinessException(PAYMENT_ALREADY_PROCESSED)` → `PAYMENT_INSUFFICIENT_STOCK_REFUNDED`, `payment.refund()` 호출됨.
  - `completePayment_PG취소_알수없는_BusinessException은_그대로_전파하고_상태불변`: 재고 부족 + `cancelPayment`가 `BusinessException(PAYMENT_INVALID_STATE_TRANSITION)` → 같은 코드로 전파, `payment.refund()` 호출 없음.
  - `completePayment_다중상품_뒤상품_재고부족_어느상품도_차감되지_않는다`: 상품 A(락 후 재고 10, 주문 2개, id 1L), 상품 B(재고 1, 주문 2개, id 2L) → `PAYMENT_INSUFFICIENT_STOCK_REFUNDED`, 두 상품 모두 `confirmStock` 호출 없음, `releaseStockAfterCommit(1L, 2)`와 `(2L, 2)` 둘 다 호출됨.

- [ ] **Step 2: `PaymentReconcilerTest`에 이력·카운터 단언 테스트 추가** (실제 Payment 사용, `verify(paymentHistoryRecorder).record(payment, from, to, trigger, reason, rawBody)` + `counter.count()`)
  - 확정: `record(payment, PENDING, PAID, CLIENT_VERIFY, null, rawBody)`, `paymentCompletedCounter` = 1.
  - PG 상태 불일치(FAILED): `record(payment, PENDING, FAILED, CLIENT_VERIFY, "PG 상태 불일치: FAILED", rawBody)`, `paymentFailedPgStatusMismatchCounter` = 1.
  - 금액 불일치: `record(payment, PENDING, REFUNDED, CLIENT_VERIFY, "결제 금액 불일치", cancelRawBody)`, `paymentFailedAmountMismatchCounter` = 1. PG 취소 결과의 `rawBody`가 null이면 PG 조회 원문(`rawBody`)으로 폴백되는 케이스를 별도 테스트로 한다.
  - 재고 부족: `record(payment, PENDING, REFUNDED, CLIENT_VERIFY, "재고 부족으로 인한 자동 환불", cancelRawBody)`, `paymentFailedInsufficientStockCounter` = 1.
  - 만료 환불: `record(payment, PENDING, REFUNDED, CLIENT_VERIFY, "주문 만료로 인한 자동 환불", cancelRawBody)`, `paymentFailedOrderExpiredCancelCounter` = 1.
  - 만료 + PG에 결제 없음: `record(payment, PENDING, CANCELLED, CLIENT_VERIFY, "주문 만료 - PG에 결제 없음", null)`, 같은 만료 카운터 = 1.
  - 만료 멱등(payment REFUNDED + order CANCELLED): `record` 호출 없음, `paymentFailedOrderExpiredIdempotentCounter` = 1.
  - 직접 조회(prefetched null): `paymentFetchedUnderLockCounter` = 1. PG 조회 `InfraException`: `paymentFailedPgErrorCounter` = 1. PG 빈 응답: `paymentFailedPgNullCounter` = 1.

- [ ] **Step 3: 통합 테스트 `PaymentCompletionCommitIntegrationTest` 작성** — `PaymentInsufficientStockIntegrationTest`와 같은 구성(`@SpringBootTest`, `PortOneClient`는 `@MockitoBean`, `@AfterEach`에서 FK 순서로 삭제)으로, `paymentService.completePayment(...)` 호출 후 **새 조회**로 DB 최종 상태를 단언한다.
  - `금액불일치_REFUNDED_CANCELLED가_커밋된다`: PG 금액 ≠ 주문 금액, `cancelPayment` 성공 → `PAYMENT_AMOUNT_MISMATCH`, payment `REFUNDED`, order `CANCELLED`, 상품 `remainingStock` 불변, 이력 1건(`PENDING → REFUNDED`).
  - `PG상태_미완료_FAILED가_커밋된다`: PG status `FAILED` → `PAYMENT_NOT_COMPLETED`, payment `FAILED`, order `RESERVED` 유지, 이력 1건(`PENDING → FAILED`).
  - `주문만료_환불이_커밋된다`: order `CANCELLED` + payment `PENDING`, `cancelPayment` 성공 → `ORDER_EXPIRED_REFUNDED`, payment `REFUNDED`, 이력 1건.
  - `금액불일치_PG취소실패_DB는_그대로_PENDING`: `cancelPayment`가 `InfraException` → `InfraException` 전파, payment `PENDING`, order `RESERVED`, 이력 0건.

- [ ] **Step 4: 실행 — 전부 통과해야 한다**

Run: `./gradlew test --offline -x jacocoTestReport -x jacocoTestCoverageVerification --tests 'com.gongu.server.domain.payment.service.PaymentReconcilerTest' --tests 'com.gongu.server.domain.payment.service.PaymentCompletionCommitIntegrationTest'`
Expected: BUILD SUCCESSFUL, 실패 0. 실패하는 테스트가 있으면 **테스트가 현재 동작을 잘못 가정한 것**이므로 테스트를 고친다(프로덕션 코드를 고치지 않는다).

- [ ] **Step 5: 커밋 (Claude가 수행)**

```bash
git add src/test/java/com/gongu/server/domain/payment/service/PaymentReconcilerTest.java
git commit -m "test: completePayment 공백 분기·이력·카운터 특성화 테스트 추가 (#249)"
git add src/test/java/com/gongu/server/domain/payment/service/PaymentCompletionCommitIntegrationTest.java
git commit -m "test: completePayment 보상 분기 DB 커밋·롤백 통합 테스트 추가 (#249)"
```

---

### Task 2: 메서드 분리와 보상 중복 통합 (동작 불변)

**Files:**
- Modify: `src/main/java/com/gongu/server/domain/payment/service/PaymentReconciler.java`

**Interfaces:**
- Consumes: Task 1의 테스트(안전망). `@Transactional(noRollbackFor = ...)`와 외부 시그니처·예외 계약은 **그대로** 둔다.
- Produces: Task 3이 반환 타입만 바꿀 수 있는 모양. 아래 `handle...` 메서드는 모두 **상태를 바꾼 뒤 사용할 `ErrorCode`를 반환**하고, 호출부가 `throw new BusinessException(handleX(...))` 한다.

`completePayment` 본문이 위에서 아래로 읽히는 짧은 스크립트가 되도록 아래 private 메서드로 나눈다(이름·시그니처 고정, 본문은 기존 코드를 옮긴다). 가드 클로즈로 중첩을 없앤다.

```java
private Order lockOrder(Payment payment)
private ErrorCode handleCancelledOrder(Payment payment, Order order, String paymentId, PaymentHistoryTrigger trigger) // ORDER_EXPIRED_REFUNDED
private PortOnePaymentResult resolvePgResult(String paymentId, PortOnePaymentResult prefetchedResult)
private ErrorCode handlePgNotPaid(Payment payment, PortOnePaymentResult pgResult, PaymentHistoryTrigger trigger)        // PAYMENT_NOT_COMPLETED
private ErrorCode handleAmountMismatch(Payment payment, Order order, PortOnePaymentResult pgResult, PaymentHistoryTrigger trigger) // PAYMENT_AMOUNT_MISMATCH
private ErrorCode handleInsufficientStock(Payment payment, Order order, List<OrderItem> items, PortOnePaymentResult pgResult, PaymentHistoryTrigger trigger) // PAYMENT_INSUFFICIENT_STOCK_REFUNDED
private List<LockedOrderItem> lockProducts(List<OrderItem> items)
private void confirm(Payment payment, Order order, PortOnePaymentResponse paid, PortOnePaymentResult pgResult, List<LockedOrderItem> locked, PaymentHistoryTrigger trigger)
private void refundAndCancelOrder(Payment payment, Order order, List<OrderItem> items, PortOnePaymentResult pgResult, PaymentHistoryTrigger trigger, String reason, Counter counter)
```

- [ ] **Step 1: 메서드 추출** — `LockedOrderItem` record는 지역 선언에서 클래스 private nested record로 올린다. `refundAndCancelOrder`는 금액 불일치·재고 부족 두 분기의 공통 보상 순서를 담당한다: `executePGCancel` → `before` 저장 → `payment.refund()` → `record(...)`(원문은 취소 결과 `rawBody`, 없으면 PG 조회 `rawBody`) → `counter.increment()` → `order.cancel(reason)` → 항목별 `releaseStockAfterCommit`. 금액 불일치 분기는 항목을 호출부에서 `orderItemRepository.findAllByOrder(order)`로 읽어 넘긴다. 만료 분기(`handleCancelledOrder`)는 환불/만료 갈래가 달라 합치지 않는다. `executePGCancel`과 `PgCancelOutcome`은 그대로 둔다.
- [ ] **Step 2: 클래스 Javadoc 정리** — `prefetchedResult` 설명은 유지하고, 새 메서드 구성을 한두 줄로 추가한다.
- [ ] **Step 3: 실행 — 기존 테스트가 한 줄도 수정되지 않은 채 통과해야 한다**

Run: `./gradlew test --offline -x jacocoTestReport -x jacocoTestCoverageVerification --tests 'com.gongu.server.domain.payment.*'`
Expected: BUILD SUCCESSFUL, 실패 0. 테스트를 수정해야 통과한다면 동작이 바뀐 것이므로 중단하고 원인을 고친다.

- [ ] **Step 4: 커밋 (Claude가 수행)**

```bash
git add src/main/java/com/gongu/server/domain/payment/service/PaymentReconciler.java
git commit -m "refactor: completePayment를 단계별 메서드로 분리하고 보상 중복 통합 (#249)"
```

---

### Task 3: 결과 반환 방식 전환 — `noRollbackFor` 제거

**Files:**
- Create: `src/main/java/com/gongu/server/domain/payment/service/PaymentCompletionResult.java`
- Modify: `src/main/java/com/gongu/server/domain/payment/service/PaymentReconciler.java`
- Modify: `src/main/java/com/gongu/server/domain/payment/service/PaymentService.java`
- Modify: `src/test/java/com/gongu/server/domain/payment/service/PaymentReconcilerTest.java`
- Modify: `src/test/java/com/gongu/server/domain/payment/service/PaymentServiceTest.java`
- Modify: `docs/adr/결제_확정_아키텍처.md` (P3 정정 한 단락)

**Interfaces:**
- Produces:

```java
public sealed interface PaymentCompletionResult {
    record Confirmed(VerifyPaymentResponse response) implements PaymentCompletionResult {}
    record Rejected(ErrorCode errorCode) implements PaymentCompletionResult {}   // com.gongu.server.global.exception.ErrorCode
}
```

- `PaymentReconciler.completePayment(String paymentId, PaymentHistoryTrigger trigger, PortOnePaymentResult prefetchedResult)` → `PaymentCompletionResult`. 애노테이션은 `@Transactional`(속성 없음).
- `PaymentService.completePayment`의 시그니처·반환(`VerifyPaymentResponse`)·예외는 변하지 않는다.

**규칙:** 상태를 바꾼 뒤 끝나는 경우만 `Rejected`를 **반환**한다 — `handleCancelledOrder`(`ORDER_EXPIRED_REFUNDED`, 멱등 분기 포함), `handlePgNotPaid`, `handleAmountMismatch`, `handleInsufficientStock`. 아직 아무것도 바꾸지 않은 실패(`PAYMENT_NOT_FOUND`, `ORDER_NOT_FOUND`, `PAYMENT_INVALID_STATE_TRANSITION`, `PAYMENT_PG_UNAVAILABLE`, `PRODUCT_NOT_FOUND`, PG 조회·취소의 `InfraException`)는 예외로 남는다.

- [ ] **Step 1: `PaymentCompletionResult` 작성** 후 리컨실러에서 `@Transactional(noRollbackFor = ...)`를 `@Transactional`로 바꾼다.
- [ ] **Step 2: 리컨실러 반환 전환** — `completePayment`의 반환 타입을 `PaymentCompletionResult`로 바꾼다. 이미 PAID이면 `Confirmed`, 정상 확정 끝에 `Confirmed(VerifyPaymentResponse.of(order, payment))`, 네 `handle...` 분기는 `return new Rejected(handleX(...))`. #230 주석은 새 이유("`Rejected`는 반환이므로 커밋된다")로 고쳐 쓰고, `noRollbackFor`를 언급하는 주석·Javadoc은 모두 정리한다.
- [ ] **Step 3: `PaymentService` 변환** — 두 위임 지점(`reconciler.completePayment(...)`)의 결과를 `private VerifyPaymentResponse toResponse(PaymentCompletionResult result)`로 처리한다: `Confirmed` → `response()` 반환, `Rejected` → `throw new BusinessException(errorCode)`. 이 변환은 리컨실러 트랜잭션이 끝난 뒤(= 커밋 뒤)에 일어난다.
- [ ] **Step 4: 테스트 수정** — 계약이 바뀌는 곳만 고친다.
  - `PaymentReconcilerTest`: 정상 확정 계열은 `((Confirmed) result).response()`로, 네 `Rejected` 분기 테스트(만료 4종, PG 상태 불일치, 금액 불일치, 재고 부족)는 `assertThatThrownBy`를 `assertThat(result).isEqualTo(new Rejected(<에러코드>))`로 바꾼다. 부수효과(`refund()`, `cancel`, 이력, 카운터) 단언은 그대로 둔다. `InfraException`/`PENDING 유지`/`NOT_FOUND` 등 예외로 남는 테스트는 수정하지 않는다.
  - `PaymentServiceTest`: `reconciler.completePayment` 스텁의 반환을 `new Confirmed(response)`로 바꾼다. 추가: `completePayment_리컨실러가_Rejected를_반환하면_같은_에러코드의_BusinessException을_던진다` — 에러코드 4개(`ORDER_EXPIRED_REFUNDED`, `PAYMENT_NOT_COMPLETED`, `PAYMENT_AMOUNT_MISMATCH`, `PAYMENT_INSUFFICIENT_STOCK_REFUNDED`) 각각 `@ParameterizedTest`로.
  - **`PaymentCompletionCommitIntegrationTest`와 `PaymentInsufficientStockIntegrationTest`, `PaymentHistoryIntegrationTest`, `PaymentCompletePgOutsideTransactionIntegrationTest`는 한 줄도 수정하지 않는다.** 이 테스트들이 통과해야 "커밋 의미가 보존됐다"는 증거가 된다.
- [ ] **Step 5: ADR-008 P3 정정** — "정정, #230" 항목 뒤에 한 단락 추가: #249에서 `noRollbackFor`를 제거하고 상태 변경 후 종료되는 분기를 `Rejected` 반환으로 바꾸어, "예외를 던졌는데 커밋" 구조가 사라졌음을 기록한다.
- [ ] **Step 6: 실행**

Run: `./gradlew test --offline -x jacocoTestReport -x jacocoTestCoverageVerification --tests 'com.gongu.server.domain.payment.*'`
Expected: BUILD SUCCESSFUL, 실패 0. 통합 테스트 4종이 수정 없이 통과해야 한다.

- [ ] **Step 7: 커밋 (Claude가 수행)**

```bash
git add src/main/java/com/gongu/server/domain/payment/service/PaymentCompletionResult.java src/main/java/com/gongu/server/domain/payment/service/PaymentReconciler.java src/main/java/com/gongu/server/domain/payment/service/PaymentService.java src/test/java/com/gongu/server/domain/payment/service/PaymentReconcilerTest.java src/test/java/com/gongu/server/domain/payment/service/PaymentServiceTest.java
git commit -m "refactor: completePayment 결과를 반환값으로 전환하고 noRollbackFor 제거 (#249)"
git add docs/adr/결제_확정_아키텍처.md
git commit -m "docs: ADR-008 P3에 noRollbackFor 제거 내용 반영 (#249)"
```

---

### Task 4: 전체 검증

**Files:** 없음 (검증만)

- [ ] **Step 1: 전체 빌드·테스트**

Run: `./gradlew build --offline`
Expected: BUILD SUCCESSFUL (jacoco 커버리지 검증 포함, 0.80 이상).

- [ ] **Step 2: diff 점검** — `git diff main...HEAD --stat`로 변경 파일이 이 계획의 파일 목록과 일치하는지, 이번 이슈와 무관한 파일이 없는지 확인한다. `git log --oneline main..HEAD`로 커밋 메시지 형식(`type: 내용 (#249)`, `Co-Authored-By` 없음)을 확인한다.

이후 push → PR 생성 → fresh 서브에이전트 리뷰(워크플로 8~12단계)는 이 계획 범위 밖이며, 사용자 확인 후 진행한다.

## 범위 밖

- ADR-008 D2(조건부 UPDATE로 락 제거)·D3(재고 이행 분리), PG 호출 위치 변경.
- `docs/02-domain-rules.md` Stock 절 갱신(주문 시 DB 차감으로 적혀 있어 현재 Redis 예약 구조와 어긋남) — 별도 이슈.
- `Product.decreaseStock` / `ProductService.decreaseStock` 정리(main 호출처 없음) — 별도 이슈.
