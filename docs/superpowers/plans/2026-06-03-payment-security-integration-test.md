# PaymentController SecurityConfig requestMatchers 통합 테스트 구현 계획

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** `SecurityConfig`의 `/payments/**` requestMatchers 설정이 회귀되면 즉시 잡을 수 있는 통합 테스트 클래스를 추가한다

**Spec:** GitHub Issue #131

**Tech Stack:** Spring Boot 3.5, JUnit 5, MockMvc, Spring Security Test

---

## 배경

현재 `PaymentControllerTest`는 `@AutoConfigureMockMvc(addFilters = false)`로 Security 필터 체인이 비활성화되어 있다.
`SecurityConfig`에서 정의된 아래 requestMatchers는 현재 어떤 테스트로도 검증되지 않는다:

- `POST /payments/webhook` → `permitAll()`
- `POST /payments/**` → `authenticated()`

---

## File Map

| 경로 | 작업 |
|------|------|
| `src/test/java/com/gongu/server/domain/payment/controller/PaymentSecurityTest.java` | **Create** |
| `src/test/java/com/gongu/server/domain/payment/controller/PaymentControllerTest.java` | 참조만 (수정 금지) |
| `src/main/java/com/gongu/server/global/config/SecurityConfig.java` | 참조만 (수정 금지) |
| `src/main/java/com/gongu/server/global/security/jwt/JwtAuthenticationFilter.java` | 참조만 |
| `src/main/java/com/gongu/server/global/security/handler/JwtAuthenticationEntryPoint.java` | 참조만 |
| `src/main/java/com/gongu/server/global/security/handler/JwtAccessDeniedHandler.java` | 참조만 |
| `src/test/resources/application.yml` | 참조만 (수정 금지) |

---

## Task 1: PaymentSecurityTest 클래스 추가

- [ ] 아래 단계를 순서대로 완료한다.

**참고 문서/파일 (읽어야 할 것):**
- `src/main/java/com/gongu/server/global/config/SecurityConfig.java` — requestMatchers 정의, 의존 빈 목록 확인
- `src/main/java/com/gongu/server/global/security/jwt/JwtAuthenticationFilter.java` — 필터 동작 방식 파악 (토큰 없을 때 어떻게 처리하는지)
- `src/main/java/com/gongu/server/global/security/handler/JwtAuthenticationEntryPoint.java` — 401 응답 작성 방식 확인
- `src/main/java/com/gongu/server/global/security/handler/JwtAccessDeniedHandler.java` — 실 구현 여부 확인
- `src/test/java/com/gongu/server/domain/payment/controller/PaymentControllerTest.java` — 기존 테스트 구조 및 MockBean 목록 참고
- `src/test/resources/application.yml` — 테스트 환경 설정 확인 (Redis 설정 포함)

**수정 대상 파일:**
- Create: `src/test/java/com/gongu/server/domain/payment/controller/PaymentSecurityTest.java`

**금지 사항:**
- `PaymentControllerTest.java` 수정 금지 — 독립된 별도 클래스로 추가한다
- `SecurityConfig.java` 수정 금지
- `application.yml` 수정 금지

**구현 방향 (WHAT, not HOW):**

테스트 클래스 설계 방향:
- Security 필터 체인이 **활성화된** 상태에서 requestMatchers를 검증해야 한다
- `@WebMvcTest(PaymentController.class)` + `@Import(SecurityConfig.class)` 조합을 권장:
  - 전체 `@SpringBootTest`보다 가볍고 DB/Redis 없이 실행 가능
  - `SecurityConfig`의 실제 빈 의존 목록(`JwtProvider`, `JwtAuthenticationEntryPoint`, `JwtAccessDeniedHandler`)을 `@MockitoBean`으로 제공
  - `JwtAuthenticationEntryPoint`는 mock 시 기본 동작이 응답을 작성하지 않을 수 있으므로, mock 설정 시 실제 401 응답이 나오도록 `doAnswer` 또는 실제 빈 import로 해결
  - `@SpringBootTest` 방식이 더 적합하다고 판단되면 그쪽으로 구현해도 무방

검증해야 할 3가지 케이스:
1. `POST /payments/webhook` — 인증 헤더 없이 요청 시 `HTTP 200`
   - `paymentService.completePayment(anyString())`를 mock하여 정상 응답 반환
2. `POST /payments/prepare` — 인증 헤더 없이 요청 시 `HTTP 401`
3. `POST /payments/verify` — 인증 헤더 없이 요청 시 `HTTP 401`

각 케이스에는 `@DisplayName`을 명시한다. 예:
- `"POST /payments/webhook — 인증 없이 200 (permitAll)"`
- `"POST /payments/prepare — 인증 없이 401 (authenticated)"`
- `"POST /payments/verify — 인증 없이 401 (authenticated)"`

**검증:**
```bash
./gradlew test --tests "com.gongu.server.domain.payment.controller.PaymentSecurityTest"
```
Expected: `BUILD SUCCESSFUL`, 3개 테스트 모두 PASS

이후 전체 테스트 통과 확인:
```bash
./gradlew test
```
Expected: `BUILD SUCCESSFUL`

**커밋 (프로젝트 컨벤션 따름):**
```bash
git add src/test/java/com/gongu/server/domain/payment/controller/PaymentSecurityTest.java
git commit -m "test: SecurityConfig requestMatchers 통합 테스트 추가 (#131)"
```

---

PR 생성 후 CLAUDE.md 9~11단계(Codex 리뷰 위임 → 판정 → 반영) 따름
