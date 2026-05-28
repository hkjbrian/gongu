# PaymentService 2-Phase 아키텍처 전환 (#127) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** `verifyPayment()` 단일 메서드를 `preparePayment()` + `completePayment()` 2-phase로 분리하여 JPA 영속성 컨텍스트 문제(deadlock, Order 상태 미커밋)를 해소하고, PortOne 웹훅과의 공용 진입점 구조를 확보한다.

**Spec:** GitHub Issue #127

**Tech Stack:** Spring Boot 3.5, Java 25, JPA/Hibernate (dirty checking), Resilience4j, PortOne V2

---

## 배경 — 전환 이유

| 문제 | 원인 | 해결 |
|------|------|------|
| Deadlock 가능성 | 외부 트랜잭션 미커밋 Payment를 REQUIRES_NEW에 전달 | preparePayment에서 선(先)커밋 후 completePayment가 재조회 |
| Order 상태 미커밋 | REQUIRES_NEW 컨텍스트에서 Order가 비관리 엔티티 | completePayment 단일 트랜잭션에서 managed 엔티티로 dirty checking |
| 웹훅 미지원 | 검증 시점에 Payment를 처음 생성 | preparePayment에서 미리 생성 → completePayment가 paymentId로 조회 |

---

## 파일 맵

### 수정/삭제/생성 대상

| 파일 | 변경 유형 |
|------|----------|
| `docs/02-domain-rules.md` | Modify — Payment 섹션 2-phase 흐름으로 업데이트 |
| `docs/superpowers/plans/2026-05-28-payment-service.md` | Modify — deprecated 명시 |
| `src/main/java/com/gongu/server/domain/payment/repository/PaymentRepository.java` | Modify — `findByMerchantUid()` 추가 |
| `src/main/java/com/gongu/server/domain/payment/service/PaymentResultCommitter.java` | **Delete** |
| `src/main/java/com/gongu/server/domain/payment/service/PaymentService.java` | **Rewrite** — preparePayment + completePayment |
| `src/main/java/com/gongu/server/domain/payment/dto/PaymentPrepareResult.java` | **Create** — preparePayment 반환 DTO |
| `src/test/java/com/gongu/server/domain/payment/service/PaymentServiceTest.java` | **Rewrite** — 새 메서드 기준 테스트 |

### 읽기 전용 참고 파일

| 파일 | 참고 목적 |
|------|----------|
| `src/main/java/com/gongu/server/domain/payment/domain/Payment.java` | `initiate()`, `confirm()`, `cancelByMismatch()`, `fail()` 시그니처 |
| `src/main/java/com/gongu/server/domain/payment/domain/PaymentStatus.java` | 상태값 확인 |
| `src/main/java/com/gongu/server/domain/order/entity/Order.java` | `pay()`, `cancel()`, `isOwnedBy()` 확인 |
| `src/main/java/com/gongu/server/domain/order/repository/OrderRepository.java` | `findByIdWithLock()` 시그니처 |
| `src/main/java/com/gongu/server/global/infrastructure/portone/PortOneClient.java` | `getPayment()`, `cancelPayment()` + InfraException 전파 방식 |
| `src/main/java/com/gongu/server/global/infrastructure/portone/dto/PortOnePaymentResponse.java` | `status()`, `amount().total()`, `paidAt()` |
| `src/main/java/com/gongu/server/global/exception/errorcode/PaymentErrorCode.java` | 에러 코드 목록 |
| `docs/schema/ddl.sql` | payments 컬럼명·제약조건 기준 |
| `docs/adr/아키텍처_및_코드_컨벤션.md` | ADR-002 Rich Domain Model |
| `docs/adr/예외_처리_전략.md` | ADR-004 예외 계층 |

### 금지 파일

- `src/main/java/com/gongu/server/domain/payment/controller/` — 컨트롤러 이슈 범위 아님
- `src/main/java/com/gongu/server/domain/payment/domain/Payment.java` — 엔티티 변경 없음
- `docs/schema/ddl.sql` — DDL 변경 없음 (스키마 변경 없음)

---

## Task 1: 도메인 규칙 문서 및 구(舊) 플랜 문서 업데이트

- [ ] **수행**

**참고 문서/파일:**
- `docs/02-domain-rules.md` — Payment 섹션 현재 내용
- `docs/superpowers/plans/2026-05-28-payment-service.md` — 구 플랜 내용

**수정 대상 파일:**
- Modify: `docs/02-domain-rules.md`
- Modify: `docs/superpowers/plans/2026-05-28-payment-service.md`

**금지 사항:**
- `docs/schema/ddl.sql` — 스키마 변경 없음
- `docs/schema/table-definitions.md` — 테이블 정의 변경 없음 (컬럼 구조 동일)

**구현 방향:**

`docs/02-domain-rules.md` — Payment 섹션을 다음 내용으로 교체:

```
## Payment

- 결제는 반드시 RESERVED 상태의 주문에 대해서만 준비(prepare)할 수 있다.
- 결제 준비(`preparePayment`) 시 서버가 paymentId(UUID)를 생성하여 Payment PENDING 레코드를 선(先) 저장한다.
- 결제 완료(`completePayment`) 시 PortOne에서 반환된 결제 금액이 주문 `totalPrice`와 일치해야 결제가 확정된다.
- 금액 불일치 시 PortOne에 취소 요청을 보내고 Payment는 CANCELLED, 주문은 CANCELLED 처리한다.
- Payment가 이미 PAID 상태이면 `completePayment` 재호출은 멱등 처리(즉시 리턴)한다.
- 결제 확정 시 Order 상태를 PAID로 전이한다 (같은 트랜잭션 안에서).
```

`docs/superpowers/plans/2026-05-28-payment-service.md` — 파일 최상단에 다음 한 줄 추가:

```
> ⚠️ **DEPRECATED** — 이 플랜은 #119 초기 설계 기준이며 #127(2-phase 전환)으로 대체됨.
```

**검증:**
```bash
# 파일 내용 확인
grep -n "preparePayment\|DEPRECATED" docs/02-domain-rules.md docs/superpowers/plans/2026-05-28-payment-service.md
```
Expected: 두 파일 모두 해당 텍스트 포함

**커밋:**
```bash
git add docs/02-domain-rules.md docs/superpowers/plans/2026-05-28-payment-service.md
git commit -m "docs: Payment 도메인 규칙 2-phase 흐름으로 업데이트 (#127)"
```

---

## Task 2: PaymentRepository — findByMerchantUid() 추가

- [ ] **수행**

**참고 문서/파일:**
- `src/main/java/com/gongu/server/domain/payment/repository/PaymentRepository.java` — 현재 `findByIdempotencyKey()` 패턴
- `docs/schema/ddl.sql` — `merchant_uid` 컬럼명, `UQ_PAYMENTS_MERCHANT_UID` 제약

**수정 대상 파일:**
- Modify: `src/main/java/com/gongu/server/domain/payment/repository/PaymentRepository.java`

**금지 사항:**
- `findByIdempotencyKey()` 삭제 금지 — 기존 코드와의 호환성

**구현 방향:**
- `Optional<Payment> findByMerchantUid(String merchantUid)` 메서드 추가
- Spring Data JPA 네이밍 컨벤션으로 작성 (별도 JPQL 불필요 — `merchant_uid` 컬럼이 `merchantUid` 필드에 매핑됨)

**검증:**
```bash
./gradlew compileJava
```
Expected: BUILD SUCCESSFUL

**커밋:**
```bash
git add src/main/java/com/gongu/server/domain/payment/repository/PaymentRepository.java
git commit -m "feat: PaymentRepository findByMerchantUid() 추가 (#127)"
```

---

## Task 3: PaymentService 재구현 + PaymentResultCommitter 삭제 + DTO 생성

- [ ] **수행**

**참고 문서/파일:**
- `src/main/java/com/gongu/server/domain/payment/domain/Payment.java` — `initiate()`, `confirm()`, `cancelByMismatch()`, `fail()` 메서드
- `src/main/java/com/gongu/server/domain/payment/domain/PaymentStatus.java` — PENDING, PAID 값
- `src/main/java/com/gongu/server/domain/order/entity/Order.java` — `pay()`, `cancel()`, `isOwnedBy()`, `getTotalPrice()`
- `src/main/java/com/gongu/server/domain/order/repository/OrderRepository.java` — `findByIdWithLock()`
- `src/main/java/com/gongu/server/global/infrastructure/portone/PortOneClient.java` — InfraException 전파, fallback 동작
- `src/main/java/com/gongu/server/global/exception/errorcode/PaymentErrorCode.java` — 에러 코드
- `docs/schema/ddl.sql` — `imp_uid varchar(50)`, `merchant_uid varchar(255)`, `idempotency_key varchar(255)` 길이 제약
- `docs/adr/아키텍처_및_코드_컨벤션.md` — ADR-002: 도메인 메서드 사용
- `docs/adr/예외_처리_전략.md` — ADR-004: BusinessException / InfraException 분리

**수정 대상 파일:**
- Delete: `src/main/java/com/gongu/server/domain/payment/service/PaymentResultCommitter.java`
- Create: `src/main/java/com/gongu/server/domain/payment/dto/PaymentPrepareResult.java`
- Rewrite: `src/main/java/com/gongu/server/domain/payment/service/PaymentService.java`

**금지 사항:**
- `Payment.java` 엔티티 수정 금지
- 컨트롤러, API 엔드포인트 생성 금지

**구현 방향:**

### PaymentPrepareResult.java (신규)
```
패키지: com.gongu.server.domain.payment.dto
Java record: PaymentPrepareResult(String paymentId, Long amount)
```

### PaymentResultCommitter.java
파일 삭제. 더 이상 사용되지 않음.

### PaymentService.java (전면 재작성)

패키지: `com.gongu.server.domain.payment.service`
클래스 어노테이션: `@Service`, `@RequiredArgsConstructor`, `@Transactional(readOnly = true)`

주입 의존성:
- `UserRepository userRepository`
- `OrderRepository orderRepository`
- `PaymentRepository paymentRepository`
- `PortOneClient portOneClient`

#### preparePayment(Long userId, Long orderId) → PaymentPrepareResult

어노테이션: `@Transactional`

구현 로직 (순서 엄수):
1. 사용자 조회: `userRepository.findByIdAndDeletedAtIsNull(userId)` → 없으면 `USER_NOT_FOUND`
2. 주문 비관적 락 조회: `orderRepository.findByIdWithLock(orderId)` → 없으면 `ORDER_NOT_FOUND`
3. 소유권 검증: `!order.isOwnedBy(userId)` → `PAYMENT_NOT_ALLOWED`
4. 주문 상태 검증: `order.getStatus() != OrderStatus.RESERVED` → `PAYMENT_NOT_ALLOWED`
5. paymentId 생성: `String paymentId = UUID.randomUUID().toString()`
6. idempotencyKey 생성: `String idempotencyKey = UUID.randomUUID().toString()`
7. Payment 생성 및 저장:
   - `Payment.initiate(order, idempotencyKey, paymentId, order.getTotalPrice())`
   - `paymentRepository.save(payment)` → 이 트랜잭션에서 완전 커밋됨
8. `return new PaymentPrepareResult(paymentId, order.getTotalPrice())`

#### completePayment(String paymentId) → void

어노테이션: `@Transactional(noRollbackFor = {BusinessException.class, InfraException.class})`

> **핵심 설계:** `noRollbackFor`를 사용하여 BusinessException/InfraException throw 시에도 트랜잭션이 커밋된다.
> payment와 order는 이 트랜잭션에서 직접 조회한 managed 엔티티이므로 dirty checking이 정상 동작한다.
> REQUIRES_NEW 불필요.

구현 로직 (순서 엄수):
1. Payment 조회: `paymentRepository.findByMerchantUid(paymentId)` → 없으면 `PAYMENT_NOT_FOUND`
2. 멱등 처리: `payment.getStatus() == PaymentStatus.PAID` → 즉시 return (중복 완료 요청 무시)
3. 상태 검증: `payment.getStatus() != PaymentStatus.PENDING` → `PAYMENT_INVALID_STATE_TRANSITION`
4. Order 비관적 락 조회: `orderRepository.findByIdWithLock(payment.getOrder().getId())` → 없으면 `ORDER_NOT_FOUND`
5. PortOne 조회 (InfraException 처리):
   ```
   try {
       response = portOneClient.getPayment(paymentId)
   } catch (InfraException e) {
       payment.fail()  // dirty checking → noRollbackFor → 커밋
       throw e
   }
   ```
6. PortOne status 검증: `!"PAID".equals(response.status())` →
   ```
   payment.fail()  // dirty checking → 커밋
   throw BusinessException(PAYMENT_NOT_COMPLETED)
   ```
7. 금액 비교: `order.getTotalPrice()` vs `response.amount().total()`
   - **일치**: `payment.confirm(actualAmount, response.paidAt().toLocalDateTime())` + `order.pay()` → dirty checking → 커밋
   - **불일치**:
     ```
     payment.cancelByMismatch()   // dirty checking → 커밋
     order.cancel("결제 금액 불일치")  // dirty checking → 커밋
     portOneClient.cancelPayment(paymentId, "결제 금액 불일치")  // 실패 시 fallback null 반환
     throw BusinessException(PAYMENT_AMOUNT_MISMATCH)
     ```

**검증:**
```bash
./gradlew compileJava
```
Expected: BUILD SUCCESSFUL, 컴파일 에러 없음

**커밋:**
```bash
git add src/main/java/com/gongu/server/domain/payment/
git commit -m "feat: PaymentService 2-phase 재구현 — preparePayment + completePayment (#127)"
```

---

## Task 4: PaymentServiceTest 재작성

- [ ] **수행**

**참고 문서/파일:**
- `src/main/java/com/gongu/server/domain/payment/service/PaymentService.java` — 위 Task 3 결과물
- `src/test/java/com/gongu/server/domain/order/service/OrderServiceTest.java` — Mockito 패턴 참고

**수정 대상 파일:**
- Rewrite: `src/test/java/com/gongu/server/domain/payment/service/PaymentServiceTest.java`

**금지 사항:**
- `@SpringBootTest` 사용 금지 — 단위 테스트만 (`@ExtendWith(MockitoExtension.class)`)
- `PaymentResultCommitter` Mock 주입 금지 — 삭제된 클래스

**구현 방향:**

Mock 대상: `UserRepository`, `OrderRepository`, `PaymentRepository`, `PortOneClient`
`@InjectMocks`: `PaymentService`

**preparePayment 테스트 케이스 (4개):**

| 번호 | 시나리오 | 검증 포인트 |
|------|---------|------------|
| 1 | 정상 — paymentId·amount 반환 | `paymentRepository.save()` 호출 확인, 반환값 non-null |
| 2 | 사용자 없음 | `USER_NOT_FOUND` BusinessException |
| 3 | 소유권 불일치 | `PAYMENT_NOT_ALLOWED` BusinessException |
| 4 | 주문 상태 비RESERVED | `PAYMENT_NOT_ALLOWED` BusinessException |

**completePayment 테스트 케이스 (7개):**

| 번호 | 시나리오 | 검증 포인트 |
|------|---------|------------|
| 1 | 정상 — 금액 일치 | `payment.confirm()` + `order.pay()` 호출 확인 |
| 2 | 멱등 — Payment 이미 PAID | 즉시 리턴, PortOne 호출 없음 |
| 3 | Payment 없음 | `PAYMENT_NOT_FOUND` BusinessException |
| 4 | Payment PENDING 아님(FAILED 등) | `PAYMENT_INVALID_STATE_TRANSITION` BusinessException |
| 5 | PortOne 조회 실패 (InfraException) | `payment.fail()` 호출 확인, InfraException 재전파 |
| 6 | PortOne status != PAID | `payment.fail()` 호출 확인, `PAYMENT_NOT_COMPLETED` BusinessException |
| 7 | 금액 불일치 | `payment.cancelByMismatch()` + `order.cancel()` + `portOneClient.cancelPayment()` 호출 확인, `PAYMENT_AMOUNT_MISMATCH` BusinessException |

> **테스트 작성 주의:**
> - `payment`, `order`는 Mockito mock으로 생성하여 도메인 메서드 호출 여부를 `verify()`로 검증
> - `paymentRepository.findByMerchantUid(paymentId)` stub 필요
> - `@Transactional(noRollbackFor = ...)` 은 단위 테스트에서는 동작하지 않으므로 예외 전파만 검증

**검증:**
```bash
./gradlew test
```
Expected: BUILD SUCCESSFUL, 11개 이상 테스트 통과

**커밋:**
```bash
git add src/test/java/com/gongu/server/domain/payment/service/PaymentServiceTest.java
git commit -m "test: PaymentServiceTest 2-phase 기준으로 재작성 (#127)"
```

---

## Self-Review 체크리스트

- [x] **스펙 커버리지**: `preparePayment` (Task 3), `completePayment` (Task 3), `findByMerchantUid` (Task 2), `PaymentResultCommitter` 삭제 (Task 3), 테스트 재작성 (Task 4), 문서 업데이트 (Task 1) — 모두 커버
- [x] **구체성**: 각 태스크에 정확한 파일 경로, 메서드 시그니처, 로직 순서 명시
- [x] **파일 경로 확인**: 모든 경로 실제 존재 확인 완료
- [x] **검증 명령**: 모든 태스크에 `./gradlew compileJava` 또는 `./gradlew test` 포함

---

## 완료 후

PR 생성 후 `CLAUDE.md` 9~11단계 (Codex 리뷰 위임 → 판정 → 반영) 따름
