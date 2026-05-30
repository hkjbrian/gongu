# PaymentController · Verify Endpoint 구현 계획

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** `POST /payments/verify` 엔드포인트와 요청/응답 DTO를 구현하고, `completePayment`가 응답 데이터를 반환하도록 서비스 반환 타입을 변경한다.

**Spec:** GitHub Issue #120 — [FEAT] PaymentController · DTO 구현 (POST /payments/verify)

**Tech Stack:** Spring Boot 3.5, Java 25, Spring Security, Jakarta Validation

---

## File Map

| 상태 | 파일 |
|------|------|
| Create | `src/main/java/com/gongu/server/domain/payment/dto/request/VerifyPaymentRequest.java` |
| Create | `src/main/java/com/gongu/server/domain/payment/dto/response/VerifyPaymentResponse.java` |
| Create | `src/main/java/com/gongu/server/domain/payment/controller/PaymentController.java` |
| Modify | `src/main/java/com/gongu/server/domain/payment/service/PaymentService.java` |
| Modify | `src/main/java/com/gongu/server/global/config/SecurityConfig.java` |
| Modify | `src/test/java/com/gongu/server/domain/payment/service/PaymentServiceTest.java` |
| Create | `src/test/java/com/gongu/server/domain/payment/controller/PaymentControllerTest.java` |
| Reference-only | `src/main/java/com/gongu/server/domain/order/controller/OrderController.java` — 컨트롤러 패턴 참고 |
| Reference-only | `src/main/java/com/gongu/server/domain/payment/domain/Payment.java` — 필드/메서드 확인 |
| Reference-only | `src/main/java/com/gongu/server/domain/payment/domain/PaymentStatus.java` — status enum |
| Reference-only | `src/main/java/com/gongu/server/global/common/ApiResponse.java` — 응답 래퍼 패턴 |
| Reference-only | `src/main/java/com/gongu/server/global/security/UserPrincipal.java` — principal 구조 |

---

### Task 1: DTO 생성

**참고 문서/파일 (읽어야 할 것):**
- `src/main/java/com/gongu/server/domain/order/dto/request/CreateOrderRequest.java` — request record 패턴
- `src/main/java/com/gongu/server/domain/order/dto/response/OrderDetailResponse.java` — response record 패턴
- `src/main/java/com/gongu/server/domain/payment/domain/PaymentStatus.java` — status 타입
- `src/main/java/com/gongu/server/domain/order/entity/OrderStatus.java` — orderStatus 타입

**수정 대상 파일:**
- Create: `src/main/java/com/gongu/server/domain/payment/dto/request/VerifyPaymentRequest.java`
- Create: `src/main/java/com/gongu/server/domain/payment/dto/response/VerifyPaymentResponse.java`

**금지 사항:**
- 기존 `dto/PaymentPrepareResult.java` 수정 금지 — 다른 플로우에서 사용 중

**구현 방향:**
- `VerifyPaymentRequest`: `@NotNull Long orderId`, `@NotNull String paymentId` 두 필드를 가진 record
  - JSON 필드명은 스네이크케이스(`order_id`, `payment_id`)로 `@JsonProperty` 또는 `@JsonNaming` 적용 (프로젝트 기존 방식 따름)
- `VerifyPaymentResponse`: `Long orderId`, `String paymentId`, `Long amount`, `PaymentStatus status`, `LocalDateTime paidAt`, `OrderStatus orderStatus` 필드를 가진 record

**검증:**
```bash
./gradlew compileJava
```
Expected: BUILD SUCCESSFUL (컴파일 오류 없음)

**커밋:**
```bash
git add src/main/java/com/gongu/server/domain/payment/dto/
git commit -m "feat: VerifyPaymentRequest · VerifyPaymentResponse DTO 추가 (#120)"
```

---

### Task 2: PaymentService.completePayment 반환 타입 변경

**참고 문서/파일 (읽어야 할 것):**
- `src/main/java/com/gongu/server/domain/payment/service/PaymentService.java` — 현재 `completePayment` 구현 전체
- `src/main/java/com/gongu/server/domain/payment/domain/Payment.java` — `getMerchantUid()`, `getAmount()`, `getStatus()`, `getPaidAt()` 사용 가능 확인
- `src/main/java/com/gongu/server/domain/payment/dto/response/VerifyPaymentResponse.java` — Task 1에서 생성한 응답 DTO

**수정 대상 파일:**
- Modify: `src/main/java/com/gongu/server/domain/payment/service/PaymentService.java`

**금지 사항:**
- `completePayment` 내부 비즈니스 로직 변경 금지 (PortOne 호출, 금액 검증, 상태 전이 로직)
- `preparePayment` 변경 금지

**구현 방향:**
- `completePayment(String paymentId)` 반환 타입을 `void` → `VerifyPaymentResponse`로 변경
- 성공 경로(금액 일치, `payment.confirm()` + `order.pay()` 호출 후) 마지막에 아래를 추가하고 반환:
  ```
  return new VerifyPaymentResponse(
      order.getId(),
      payment.getMerchantUid(),
      payment.getAmount(),
      payment.getStatus(),
      payment.getPaidAt(),
      order.getStatus()
  );
  ```
- 이미 PAID 상태인 경우(`payment.getStatus() == PaymentStatus.PAID`) 반환: 기존의 `return;`을 동일 구조의 `VerifyPaymentResponse` 반환으로 교체
- 예외 throw 경로는 반환값 불필요 — 변경 없음

**검증:**
```bash
./gradlew compileJava
```
Expected: BUILD SUCCESSFUL

**커밋:**
```bash
git add src/main/java/com/gongu/server/domain/payment/service/PaymentService.java
git commit -m "feat: completePayment 반환 타입 void → VerifyPaymentResponse (#120)"
```

---

### Task 3: PaymentController 구현

**참고 문서/파일 (읽어야 할 것):**
- `src/main/java/com/gongu/server/domain/order/controller/OrderController.java` — `@PreAuthorize`, `@AuthenticationPrincipal`, `ResponseEntity` 패턴
- `src/main/java/com/gongu/server/domain/payment/service/PaymentService.java` — `completePayment` 시그니처
- `src/main/java/com/gongu/server/global/common/ApiResponse.java` — 응답 래퍼

**수정 대상 파일:**
- Create: `src/main/java/com/gongu/server/domain/payment/controller/PaymentController.java`
- Delete: `src/main/java/com/gongu/server/domain/payment/controller/.gitkeep`

**금지 사항:**
- `@RequestMapping` 경로 외 다른 엔드포인트 추가 금지 (이 이슈 범위는 `/payments/verify` 하나)

**구현 방향:**
- `@RestController`, `@RequestMapping("/payments")`, `@RequiredArgsConstructor`
- `@PostMapping("/verify")` 메서드:
  - `@PreAuthorize("hasRole('USER')")`
  - `@Valid @RequestBody VerifyPaymentRequest request`
  - `@RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey` — 추출만, 서비스에 전달하지 않음 (서비스 시그니처 미변경)
  - `@AuthenticationPrincipal UserPrincipal userPrincipal` — 현재 서비스에서 userId 불필요하지만 필드 추출 패턴 일관성 유지
  - `VerifyPaymentResponse result = paymentService.completePayment(request.paymentId())` 호출
  - `ResponseEntity.ok(ApiResponse.success(result))` 반환

**검증:**
```bash
./gradlew compileJava
```
Expected: BUILD SUCCESSFUL

**커밋:**
```bash
git add src/main/java/com/gongu/server/domain/payment/controller/
git commit -m "feat: PaymentController POST /payments/verify 구현 (#120)"
```

---

### Task 4: SecurityConfig 업데이트

**참고 문서/파일 (읽어야 할 것):**
- `src/main/java/com/gongu/server/global/config/SecurityConfig.java` — 현재 authorizeHttpRequests 블록

**수정 대상 파일:**
- Modify: `src/main/java/com/gongu/server/global/config/SecurityConfig.java`

**금지 사항:**
- 기존 permit 규칙(`/auth/**`, `/stores/**`, `/products/**`) 변경 금지

**구현 방향:**
- `authorizeHttpRequests` 블록 내 `anyRequest().authenticated()` 바로 위에 명시적 규칙 추가:
  ```
  .requestMatchers("/payments/**").authenticated()
  ```
  (역할 검증은 `@PreAuthorize`에서 처리하므로 `hasRole`은 SecurityConfig에 포함하지 않음)

**검증:**
```bash
./gradlew compileJava
```
Expected: BUILD SUCCESSFUL

**커밋:**
```bash
git add src/main/java/com/gongu/server/global/config/SecurityConfig.java
git commit -m "feat: SecurityConfig /payments/** 인증 설정 추가 (#120)"
```

---

### Task 5: 테스트 작성 및 기존 테스트 수정

**참고 문서/파일 (읽어야 할 것):**
- `src/test/java/com/gongu/server/domain/payment/service/PaymentServiceTest.java` — 기존 테스트 구조 (반환 타입 변경으로 컴파일 오류 발생 여부 확인)
- `src/main/java/com/gongu/server/domain/payment/controller/PaymentController.java` — 테스트 대상
- `src/main/java/com/gongu/server/domain/order/controller/OrderController.java` — 컨트롤러 테스트 패턴이 있다면 참고

**수정 대상 파일:**
- Modify: `src/test/java/com/gongu/server/domain/payment/service/PaymentServiceTest.java`
- Create: `src/test/java/com/gongu/server/domain/payment/controller/PaymentControllerTest.java`

**구현 방향 — PaymentServiceTest 수정:**
- `completePayment` 반환 타입 변경으로 인해 `void` 반환을 기대하는 단언 패턴이 있다면 `VerifyPaymentResponse result = paymentService.completePayment(...)` 호출 후 응답 필드 검증으로 교체
- 예외 케이스 테스트는 반환값 없이 예외만 검증 — 변경 없음

**구현 방향 — PaymentControllerTest:**
- `@WebMvcTest(PaymentController.class)` + MockMvc 기반 슬라이스 테스트
- `@MockBean PaymentService paymentService` 사용
- 테스트 케이스:
  1. **성공 케이스**: `POST /payments/verify` 요청 시 200 OK + `VerifyPaymentResponse` 반환 검증
  2. **인증 없음**: 토큰 미포함 요청 시 401 반환 검증
  3. **요청 검증 실패**: `paymentId` 누락 시 400 반환 검증

**검증:**
```bash
./gradlew test --tests "com.gongu.server.domain.payment.*"
```
Expected: 모든 테스트 PASS

**커밋:**
```bash
git add src/test/java/com/gongu/server/domain/payment/
git commit -m "test: PaymentController 슬라이스 테스트 및 PaymentServiceTest 반환 타입 반영 (#120)"
```

---

## PR 이후 워크플로우

PR 생성 후 CLAUDE.md 9~11단계(Codex 리뷰 위임 → 판정 → 반영) 따름.
