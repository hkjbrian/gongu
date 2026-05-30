# Payment Domain Tests Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Payment 엔티티 도메인 메서드 단위 테스트 작성 및 PaymentServiceTest 서킷브레이커 케이스 명칭 보완

**Spec:** GitHub Issue #121

**Tech Stack:** Spring Boot 3.5, Java 25, JUnit 5, Mockito, Resilience4j

---

## File Map

| 파일 | 동작 |
|------|------|
| `src/test/java/com/gongu/server/domain/payment/domain/PaymentTest.java` | **Create** — Payment 엔티티 도메인 메서드 단위 테스트 |
| `src/test/java/com/gongu/server/domain/payment/service/PaymentServiceTest.java` | **Modify** — 서킷브레이커 명칭 테스트 추가 |

**참조 전용 (수정 금지):**
- `src/main/java/com/gongu/server/domain/payment/domain/Payment.java` — 도메인 메서드 목록
- `src/main/java/com/gongu/server/domain/payment/domain/PaymentStatus.java` — 상태 열거값
- `src/main/java/com/gongu/server/global/exception/errorcode/PaymentErrorCode.java` — 에러 코드
- `src/main/java/com/gongu/server/global/exception/InfraException.java` — InfraException 타입
- `src/main/java/com/gongu/server/global/infrastructure/portone/PortOneClient.java` — 서킷브레이커 구조 파악

---

### Task 1: Payment 엔티티 도메인 메서드 단위 테스트 작성

**참고 파일:**
- `src/main/java/com/gongu/server/domain/payment/domain/Payment.java` — 테스트 대상 메서드 전체
- `src/main/java/com/gongu/server/domain/payment/domain/PaymentStatus.java` — PENDING, PAID, FAILED, CANCELLED
- `src/main/java/com/gongu/server/global/exception/errorcode/PaymentErrorCode.java` — 기대 예외 코드
- `src/test/java/com/gongu/server/domain/payment/service/PaymentServiceTest.java` — 패키지·import 패턴 참고

**수정 대상 파일:**
- Create: `src/test/java/com/gongu/server/domain/payment/domain/PaymentTest.java`

**금지 사항:**
- `Payment.java` 프로덕션 코드 수정 금지

**구현 방향:**

`Payment.initiate()` 테스트:
- 반환 객체의 `status == PENDING`, `amount`, `merchantUid`, `impUid`, `idempotencyKey` 값이 인수와 일치하는지 검증
- `order` 필드가 전달한 Order 객체와 동일한지 검증

`Payment.confirm()` 테스트:
- PENDING 상태에서 올바른 금액 전달 → `status == PAID`, `paidAt != null`
- PENDING 아닌 상태(PAID, FAILED, CANCELLED)에서 호출 → `BusinessException(PAYMENT_ALREADY_PROCESSED)` 발생
- PENDING 상태에서 금액 불일치 → `BusinessException(PAYMENT_AMOUNT_MISMATCH)` 발생

`Payment.cancelByMismatch()` 테스트:
- PENDING 상태에서 호출 → `status == CANCELLED`, `cancelledAt != null`
- PENDING 아닌 상태(PAID)에서 호출 → `BusinessException(PAYMENT_INVALID_STATE_TRANSITION)` 발생

`Payment.cancel()` 테스트:
- PAID 상태에서 호출 → `status == CANCELLED`, `cancelledAt != null`
- PAID 아닌 상태(PENDING, FAILED)에서 호출 → `BusinessException(PAYMENT_INVALID_STATE_TRANSITION)` 발생

`Payment.fail()` 테스트:
- PENDING 상태에서 호출 → `status == FAILED`
- PENDING 아닌 상태(PAID, CANCELLED)에서 호출 → `BusinessException(PAYMENT_INVALID_STATE_TRANSITION)` 발생

**구현 시 주의:**
- `Payment.builder()`는 `AccessLevel.PRIVATE`이므로 `Payment.initiate()` 팩토리 메서드로만 생성
- 상태를 PAID/CANCELLED로 만들기 위해서는 `initiate()` 후 `confirm()` / `cancel()` 순서로 호출
- Order 인수는 `Mockito.mock(Order.class)`로 처리

**검증:**
```bash
./gradlew test --tests "com.gongu.server.domain.payment.domain.*" -x jacocoTestCoverageVerification
```
Expected: BUILD SUCCESSFUL, PaymentTest 전체 통과

**커밋:**
```bash
git add src/test/java/com/gongu/server/domain/payment/domain/PaymentTest.java
git commit -m "test: Payment 엔티티 도메인 메서드 단위 테스트 추가 (#121)"
```

---

### Task 2: PaymentServiceTest 서킷브레이커 명칭 케이스 추가

**참고 파일:**
- `src/main/java/com/gongu/server/global/infrastructure/portone/PortOneClient.java` — `getPaymentFallback`이 `InfraException(PAYMENT_PG_UNAVAILABLE)` 던지는 구조 확인
- `src/test/java/com/gongu/server/domain/payment/service/PaymentServiceTest.java` — 기존 `completePayment_PortOne_InfraException` 테스트 위치 확인
- `src/main/java/com/gongu/server/global/exception/errorcode/PaymentErrorCode.java` — `PAYMENT_PG_UNAVAILABLE` (503)

**수정 대상 파일:**
- Modify: `src/test/java/com/gongu/server/domain/payment/service/PaymentServiceTest.java`

**금지 사항:**
- 기존 테스트 메서드 수정·삭제 금지 (기존 `completePayment_PortOne_InfraException` 유지)
- PaymentService.java 프로덕션 코드 수정 금지

**구현 방향:**

기존 `completePayment_PortOne_InfraException` 아래에 서킷브레이커 전용 명칭 테스트 추가:

`completePayment_서킷브레이커_오픈_503` 테스트:
- `portOneClient.getPayment(PAYMENT_ID)` → `InfraException(PaymentErrorCode.PAYMENT_PG_UNAVAILABLE)` throw
- 서비스가 `InfraException`을 전파하는지 (`assertThatThrownBy → isInstanceOf(InfraException.class)`)
- `payment.fail()` 호출 여부 검증 (`verify(payment).fail()`)
- DisplayName: `"completePayment_서킷브레이커_오픈_503 — InfraException 전파 + payment.fail() 호출"`

**검증:**
```bash
./gradlew test --tests "com.gongu.server.domain.payment.service.*" -x jacocoTestCoverageVerification
```
Expected: BUILD SUCCESSFUL, 신규 테스트 포함 전체 통과

**커밋:**
```bash
git add src/test/java/com/gongu/server/domain/payment/service/PaymentServiceTest.java
git commit -m "test: PaymentServiceTest 서킷브레이커 명칭 케이스 추가 (#121)"
```

---

## 완료 후

PR 생성 후 CLAUDE.md 9~11단계(Codex 리뷰 위임 → 판정 → 반영) 따름.
