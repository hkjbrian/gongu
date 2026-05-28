# PaymentService 구현 (#119) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** `PaymentService.verifyPayment()` 구현 — 멱등성 보장, 금액 검증, 금액 불일치 시 DB 보상 트랜잭션 + PortOne 취소, CB/타임아웃 시 FAILED 처리

**Spec:** GitHub Issue #119

**Tech Stack:** Spring Boot 3.5, Java 25, JPA, Resilience4j (CircuitBreaker/Retry), PortOneClient (RestClient)

---

## 파일 맵

### 수정 대상
| 파일 | 변경 유형 | 이유 |
|------|----------|------|
| `src/main/java/com/gongu/server/domain/payment/domain/Payment.java` | Modify | 보상 취소 메서드(`cancelByMismatch`) 추가 — `cancel()`은 PAID→CANCELLED 전용이라 PENDING 상태에서 호출 불가 |
| `src/main/java/com/gongu/server/domain/payment/service/PaymentService.java` | Create | 핵심 구현 대상 |
| `src/test/java/com/gongu/server/domain/payment/service/PaymentServiceTest.java` | Create | 단위 테스트 |

### 읽기 전용 참고 파일
| 파일 | 참고 목적 |
|------|----------|
| `src/main/java/com/gongu/server/domain/payment/domain/Payment.java` | 엔티티 메서드 시그니처, 상태 전이 guard 파악 |
| `src/main/java/com/gongu/server/domain/payment/repository/PaymentRepository.java` | `findByIdempotencyKey()` 시그니처 |
| `src/main/java/com/gongu/server/domain/order/entity/Order.java` | `pay()`, `cancel()` guard 조건 파악 |
| `src/main/java/com/gongu/server/domain/order/repository/OrderRepository.java` | `findByIdWithLock()` 시그니처 (비관적 락) |
| `src/main/java/com/gongu/server/global/infrastructure/portone/PortOneClient.java` | `getPayment()`, `cancelPayment()` 시그니처, InfraException 전파 방식 |
| `src/main/java/com/gongu/server/global/infrastructure/portone/dto/PortOnePaymentResponse.java` | `amount().total()` 접근 방식 |
| `src/main/java/com/gongu/server/global/exception/errorcode/PaymentErrorCode.java` | 사용 가능한 에러 코드 목록 |
| `src/main/java/com/gongu/server/global/exception/errorcode/OrderErrorCode.java` | ORDER_NOT_FOUND 등 에러 코드 |
| `src/main/java/com/gongu/server/domain/user/repository/UserRepository.java` | `findByIdAndDeletedAtIsNull()` 시그니처 |
| `src/main/java/com/gongu/server/domain/order/service/OrderService.java` | 서비스 레이어 패턴 참고 |
| `src/test/java/com/gongu/server/domain/order/service/OrderServiceTest.java` | 테스트 패턴 참고 (Mockito, given/when/then) |
| `src/main/resources/application.yml` | resilience4j portone 설정 값 확인 |

### 금지 파일 (건드리지 않음)
- 컨트롤러, DTO 클래스 — 다음 이슈 범위
- `PaymentRepository.java` — 이번 이슈에서 쿼리 추가 불필요
- `OrderRepository.java` — 기존 메서드로 충분

---

## Task 1: Payment 엔티티에 `cancelByMismatch()` 메서드 추가

- [ ] **수행**

**참고 문서/파일:**
- `src/main/java/com/gongu/server/domain/payment/domain/Payment.java` — 현재 `cancel()`이 `PAID` 상태만 허용하는 guard 확인
- `src/main/java/com/gongu/server/global/exception/errorcode/PaymentErrorCode.java` — 사용할 에러 코드 확인

**수정 대상 파일:**
- Modify: `src/main/java/com/gongu/server/domain/payment/domain/Payment.java`

**구현 방향:**
- 기존 `cancel()` 메서드 위에 `cancelByMismatch()` 메서드를 추가한다
- `cancelByMismatch()`의 guard 조건: `this.status != PaymentStatus.PENDING` → `throw new BusinessException(PaymentErrorCode.PAYMENT_ALREADY_PROCESSED)`
- 상태 전이: `this.status = PaymentStatus.CANCELLED; this.cancelledAt = LocalDateTime.now();`
- 기존 `cancel()` 메서드(PAID→CANCELLED, 환불용)는 그대로 유지한다
- `fail()`은 CB 오픈/타임아웃에만 사용하므로 건드리지 않는다

**검증:**
```bash
./gradlew compileJava
```
Expected: BUILD SUCCESSFUL, 컴파일 에러 없음

**커밋:**
```bash
git add src/main/java/com/gongu/server/domain/payment/domain/Payment.java
git commit -m "feat: Payment 엔티티에 cancelByMismatch() 보상 취소 메서드 추가 (#119)"
```

---

## Task 2: PaymentService 구현

- [ ] **수행**

**참고 문서/파일:**
- `src/main/java/com/gongu/server/domain/payment/domain/Payment.java` — `initiate()`, `confirm()`, `cancelByMismatch()`, `fail()` 메서드
- `src/main/java/com/gongu/server/domain/payment/repository/PaymentRepository.java` — `findByIdempotencyKey()`
- `src/main/java/com/gongu/server/domain/order/entity/Order.java` — `pay()`, `cancel()` guard 조건
- `src/main/java/com/gongu/server/domain/order/repository/OrderRepository.java` — `findByIdWithLock()` (비관적 락)
- `src/main/java/com/gongu/server/global/infrastructure/portone/PortOneClient.java` — `getPayment()`, `cancelPayment()` + InfraException 전파 방식
- `src/main/java/com/gongu/server/global/infrastructure/portone/dto/PortOnePaymentResponse.java` — `amount().total()`, `paidAt()`
- `src/main/java/com/gongu/server/global/exception/errorcode/PaymentErrorCode.java` — 에러 코드
- `src/main/java/com/gongu/server/domain/order/service/OrderService.java` — 서비스 레이어 어노테이션/주입 패턴

**수정 대상 파일:**
- Create: `src/main/java/com/gongu/server/domain/payment/service/PaymentService.java`

**금지 사항:**
- `src/main/java/com/gongu/server/domain/payment/domain/Payment.java`에서 Task 1에서 추가한 메서드 외 변경 금지
- 컨트롤러, DTO 클래스 생성 금지 — 다음 이슈 범위

**구현 방향:**

패키지: `com.gongu.server.domain.payment.service`

클래스 어노테이션: `@Service`, `@RequiredArgsConstructor`, `@Transactional(readOnly = true)`

주입 의존성:
- `UserRepository userRepository`
- `OrderRepository orderRepository`
- `PaymentRepository paymentRepository`
- `PortOneClient portOneClient`

메서드 시그니처:
```
@Transactional
public void verifyPayment(Long userId, String idempotencyKey, Long orderId, String paymentId, Long amount)
```

구현 로직 (순서 엄수):

1. **멱등키 중복 검사**
   - `paymentRepository.findByIdempotencyKey(idempotencyKey)` 조회
   - 존재하면 `throw new BusinessException(PaymentErrorCode.PAYMENT_ALREADY_PROCESSED)` — 상태 무관하게 중복 처리
   - (이미 처리된 결제를 재시도하는 경우로 간주)

2. **사용자 조회**
   - `userRepository.findByIdAndDeletedAtIsNull(userId)` 
   - 없으면 `throw new BusinessException(UserErrorCode.USER_NOT_FOUND)`

3. **주문 조회 및 검증 (비관적 락)**
   - `orderRepository.findByIdWithLock(orderId)` — 비관적 락으로 조회
   - 없으면 `throw new BusinessException(OrderErrorCode.ORDER_NOT_FOUND)`
   - `order.getUser().getId()`가 `userId`와 다르면 `throw new BusinessException(PaymentErrorCode.PAYMENT_NOT_ALLOWED)` — 소유권 불일치
   - `order.getStatus() != OrderStatus.RESERVED`이면 `throw new BusinessException(PaymentErrorCode.PAYMENT_NOT_ALLOWED)` — RESERVED가 아닌 주문

4. **결제 레코드 초기화 및 저장**
   - `Payment payment = Payment.initiate(order, idempotencyKey, paymentId, amount)`
   - `paymentRepository.save(payment)` — PENDING 상태로 저장

5. **PortOne 조회 및 금액 검증**
   - `portOneClient.getPayment(paymentId)` 호출 — InfraException(CB) 발생 시 catch로 이동
   - `portOneResponse.amount().total()`과 `payment.getAmount()` 비교

6. **금액 일치 처리 (동일 @Transactional)**
   - `payment.confirm(portOneResponse.amount().total(), portOneResponse.paidAt().toLocalDateTime())`
   - `order.pay()`
   - → 메서드 정상 종료

7. **금액 불일치 처리 (보상 트랜잭션)**
   - 현재 @Transactional 내에서: `payment.cancelByMismatch()` + `order.cancel("결제 금액 불일치")`
   - 위 DB 변경은 동일 트랜잭션에서 커밋됨
   - **별도 트랜잭션**으로 PortOne 취소: 메서드 안에서 `portOneClient.cancelPayment(paymentId, "결제 금액 불일치")` 호출
     - PortOneClient.cancelPaymentFallback은 null 반환(로그만 남기고 전파 안 함)이므로 실패 무시
   - `throw new BusinessException(PaymentErrorCode.PAYMENT_AMOUNT_MISMATCH)` — 최종적으로 예외 전파

8. **CB 오픈/타임아웃 처리 (InfraException 캐치)**
   - `catch (InfraException e)` 블록
   - `payment.fail()` — PENDING→FAILED
   - `throw e` — InfraException 재전파

> **주의:** 5번 PortOne 조회에서 `BusinessException`이 발생하면(404 등) catch하지 않고 그대로 전파한다.

**별도 트랜잭션 구현 방법 선택 (둘 중 택일):**
- Option A: `portOneClient.cancelPayment()` 호출은 Spring transaction 외부이므로 @Transactional 메서드 내 호출 위치를 주문/결제 DB 커밋 후로 두기 — 단, @Transactional 메서드는 하나의 트랜잭션이라 실제 DB 커밋은 메서드 종료 시. 이 경우 PortOne 취소는 같은 트랜잭션 안에서 일어나지만, PortOne은 외부 시스템이라 JPA 트랜잭션과 무관
- Option B: Self-injection(`@Lazy PaymentService self`) 또는 별도 컴포넌트에서 `@Transactional(REQUIRES_NEW)`로 PortOne 취소 분리

**권장: Option A 사용** — PortOneClient는 외부 HTTP 호출이라 DB 트랜잭션과 분리된다. DB 변경(cancelByMismatch + order.cancel)을 먼저 하고, 동일 메서드 내에서 PortOne 취소를 호출한다. (PortOne 취소 실패는 fallback이 null 반환하므로 예외 전파 없음)

**검증:**
```bash
./gradlew compileJava
```
Expected: BUILD SUCCESSFUL

**커밋:**
```bash
git add src/main/java/com/gongu/server/domain/payment/service/PaymentService.java
git commit -m "feat: PaymentService.verifyPayment() 구현 — 멱등성, 금액 검증, 보상 트랜잭션 (#119)"
```

---

## Task 3: PaymentServiceTest 작성

- [ ] **수행**

**참고 문서/파일:**
- `src/main/java/com/gongu/server/domain/payment/service/PaymentService.java` — 테스트 대상 메서드
- `src/test/java/com/gongu/server/domain/order/service/OrderServiceTest.java` — Mockito 패턴, `ReflectionTestUtils` 사용법, given/when/then 구조
- `src/main/java/com/gongu/server/global/infrastructure/portone/dto/PortOnePaymentResponse.java` — 테스트용 mock 응답 구성

**수정 대상 파일:**
- Create: `src/test/java/com/gongu/server/domain/payment/service/PaymentServiceTest.java`

**금지 사항:**
- 통합 테스트(`@SpringBootTest`) 작성 금지 — Mockito 단위 테스트만
- 실제 DB/PortOne 호출 금지

**테스트 케이스 목록 (모든 케이스 작성):**

| 테스트명 | 시나리오 | 예상 결과 |
|---------|---------|---------|
| `verifyPayment_성공_금액일치()` | 정상 플로우, 금액 일치 | `payment.confirm()` + `order.pay()` 호출 확인 |
| `verifyPayment_멱등키_중복_예외()` | 동일 멱등키로 재호출 | `BusinessException(PAYMENT_ALREADY_PROCESSED)` |
| `verifyPayment_사용자_없음_예외()` | 존재하지 않는 userId | `BusinessException(USER_NOT_FOUND)` |
| `verifyPayment_주문_없음_예외()` | 존재하지 않는 orderId | `BusinessException(ORDER_NOT_FOUND)` |
| `verifyPayment_주문_소유권_불일치_예외()` | order.user.id != userId | `BusinessException(PAYMENT_NOT_ALLOWED)` |
| `verifyPayment_주문_상태_비정상_예외()` | order.status == PAID (RESERVED 아님) | `BusinessException(PAYMENT_NOT_ALLOWED)` |
| `verifyPayment_금액_불일치_보상처리()` | PortOne 금액 != 요청 금액 | `payment.cancelByMismatch()` + `order.cancel()` 호출, `portOneClient.cancelPayment()` 호출, `BusinessException(PAYMENT_AMOUNT_MISMATCH)` |
| `verifyPayment_CB오픈_fail처리()` | `portOneClient.getPayment()`에서 `InfraException` 발생 | `payment.fail()` 호출, `InfraException` 재전파 |

**테스트 구조 가이드:**
- `@ExtendWith(MockitoExtension.class)`
- `@InjectMocks PaymentService paymentService`
- `@Mock` 으로 각 의존성 주입
- `ReflectionTestUtils.setField(order, "status", OrderStatus.RESERVED)` 패턴으로 엔티티 필드 설정
- `ReflectionTestUtils.setField(order, "user", user)` 패턴으로 연관관계 설정
- PortOnePaymentResponse mock: `new PortOnePaymentResponse("payId", "PAID", new PortOnePaymentResponse.Amount(10000L), OffsetDateTime.now())`

**검증:**
```bash
./gradlew test --tests "com.gongu.server.domain.payment.service.PaymentServiceTest"
```
Expected: 전체 테스트 통과, BUILD SUCCESSFUL

**커밋:**
```bash
git add src/test/java/com/gongu/server/domain/payment/service/PaymentServiceTest.java
git commit -m "test: PaymentServiceTest 단위 테스트 작성 (#119)"
```

---

## 최종 검증

```bash
./gradlew test
```
Expected: 전체 테스트 통과, BUILD SUCCESSFUL

---

## PR 생성 후

PR 생성 후 `CLAUDE.md` 9~11단계(Codex 리뷰 위임 → 판정 → 반영) 따름
