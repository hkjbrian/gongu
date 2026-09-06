# 이슈 #214 — 서킷브레이커가 네트워크 오류를 실패로 집계하도록 수정

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** PortOne이 완전 다운되어 응답조차 못 할 때(연결 실패·타임아웃) 서킷브레이커가 실제로 열리도록, `resilience4j.circuitbreaker.instances.portone.record-exceptions`에 네트워크 계열 예외를 추가한다.

**Architecture:** resilience4j 설정은 `record-exceptions`가 지정되면 allowlist로 동작한다 — 목록에 없는 예외는 전부 "성공"으로 집계된다. 현재 목록에는 `HttpServerErrorException`(5xx)만 있어, RestClient가 네트워크 오류를 감싸 던지는 `ResourceAccessException`이 성공으로 세어져 서킷이 영원히 닫힌 상태로 남는다. 목록을 `retry-exceptions`와 동일한 집합으로 맞춘다. 코드 변경 없이 설정만 바뀌며, 검증을 위해 테스트 프로파일에 resilience4j 설정을 동기화하고 통합 테스트를 추가한다.

**Tech Stack:** Spring Boot 3.5, Java 25, resilience4j-spring-boot3, JUnit 5, `MockServerRestClientCustomizer`(spring-boot-test), H2

## Global Constraints

- 커밋 메시지 형식: `type: 작업 내용 (#214)` — `Co-Authored-By` 절 절대 포함 금지 (`.claude/github-rules.md`)
- Surgical Changes — #214 범위 밖(애스펙트 순서 #213, PortOneClient javadoc, CB 임계값 재조정)은 건드리지 않는다
- 브랜치: `fix/#214-cb-record-network-exceptions` (이미 생성됨, `origin/main` f88ea83 기준 워크트리 `.claude/worktrees/fix-214`)
- 빌드/테스트: 워크트리 루트에서 `./gradlew test` (H2 인메모리 + `localhost:6379` Redis 필요 — 둘 다 기동 확인됨)
- `record-exceptions` / `retry-exceptions` FQCN 정확히:
  - `org.springframework.web.client.HttpServerErrorException`
  - `org.springframework.web.client.ResourceAccessException`
  - `java.io.IOException`

---

## 배경 — 확정된 설계 결정 (2026-09-03, 사용자 합의)

| # | 결정 | 근거 |
|---|---|---|
| D1 | `record-exceptions`를 `retry-exceptions`와 **정확히 동일한 3개 집합**으로 맞춘다 | 두 목록의 의도가 어긋나 있던 것이 버그의 본질. 대칭을 맞춰 의도를 명확히. RestClient는 네트워크 오류를 전부 `ResourceAccessException`으로 감싸므로 raw `IOException`은 거의 안 올라오지만, 방어적으로 함께 등록 |
| D2 | CB 임계값(`sliding-window-size: 10` / `failure-rate-threshold: 50`)은 **이번에 손대지 않는다** | 실트래픽 타임아웃 빈도 데이터가 없는 상태의 임의 변경은 근거가 없다. `application.yml`에 "부하 테스트 후 재조정" 주석을 남겨 이슈의 재검토 항목을 충족. slow-call 설정 추가도 같은 이유로 보류 |
| D3 | 테스트 `application.yml`에 `circuitbreaker` + `retry` 블록을 **운영과 동일하게 복사**한다. `aspect-order`는 넣지 않는다 | 서킷 개방 검증에는 현실적 설정이 필요. 현재 테스트 리소스에는 resilience4j 설정이 0줄이라 모든 테스트가 라이브러리 기본값으로 돈다. `aspect-order`는 #213의 수정 대상이므로 제외 — #213이 두 줄만 추가하면 충돌 없음 |
| D4 | 테스트는 `MockServerRestClientCustomizer` + `MockRestServiceServer`로 PortOne 응답을 시뮬레이션 | 죽은 포트로 실네트워크 실패를 내는 방식보다 결정적·고속. `withException(new ConnectException(...))` → RestClient가 `ResourceAccessException`으로 감쌈 → 실제 경로와 동일. 5xx 회귀도 `withServerError()`로 함께 검증 |

---

## File Structure

| 파일 | 책임 | 변경 |
|---|---|---|
| `src/main/resources/application.yml` | 운영 resilience4j 설정 | `circuitbreaker...portone.record-exceptions`에 2개 추가 + 임계값 유지 사유 주석 |
| `src/test/resources/application.yml` | 테스트 프로파일 설정 | `resilience4j:` 블록 신규 추가 (운영과 동일, aspect-order 제외) |
| `src/test/java/com/gongu/server/global/infrastructure/portone/PortOneClientResilienceTest.java` | PortOne 서킷브레이커 동작 통합 테스트 | 신규 |

세 파일은 함께 변경·리뷰된다. 테스트 파일은 `PortOneClient`와 같은 패키지 경로(`global/infrastructure/portone`) 아래 둔다.

---

## Task 1: 테스트 프로파일 resilience4j 설정 동기화 + 5xx 서킷 개방 테스트

**목표:** 테스트 환경이 운영 설정을 그대로 재현하게 만들고, "5xx 반복 → 서킷 OPEN"이라는 이미 동작하는 경로를 통합 테스트로 고정한다. 이 시점의 테스트 설정에는 **버그가 그대로 남아 있다**(record-exceptions에 5xx만) — 그게 목적이다.

**Files:**
- Modify: `src/test/resources/application.yml` (현재 29줄, 파일 끝에 추가)
- Create: `src/test/java/com/gongu/server/global/infrastructure/portone/PortOneClientResilienceTest.java`

**Interfaces:**
- Consumes:
  - `com.gongu.server.global.infrastructure.portone.PortOneClient#getPayment(String)` → `PortOnePaymentResponse`
  - `com.gongu.server.global.infrastructure.portone.dto.PortOnePaymentResponse` (record: `String paymentId, String status, Amount amount, OffsetDateTime paidAt` — 정확한 생성자는 구현 시 파일에서 확인)
  - `com.gongu.server.global.exception.InfraException` (fallback이 던지는 예외, `PaymentErrorCode.PAYMENT_PG_UNAVAILABLE`)
  - `io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry` (resilience4j 스타터가 자동 구성)
  - `org.springframework.boot.test.web.client.MockServerRestClientCustomizer` (spring-boot-test, `RestClientCustomizer` 구현 — Spring Boot 3.4+)
- Produces:
  - `PortOneClientResilienceTest` — 다음 테스트에서 네트워크 케이스 메서드가 추가된다
  - 헬퍼 메서드 `private void driveUntilOpen(int maxCalls)` — Task 2가 재사용

- [ ] **Step 1: 테스트 `application.yml`에 resilience4j 블록 추가**

`src/test/resources/application.yml` 파일 맨 끝(29줄 `reservation-ttl-minutes: 10` 다음)에 빈 줄 하나 두고 추가. **운영 `application.yml` 45~66줄과 동일**하게 유지하되 이 시점에는 `record-exceptions`에 5xx만 둔다 (Task 2에서 함께 고침):

```yaml

resilience4j:
  circuitbreaker:
    instances:
      portone:
        sliding-window-size: 10
        failure-rate-threshold: 50
        wait-duration-in-open-state: 30s
        permitted-number-of-calls-in-half-open-state: 3
        register-health-indicator: true
        record-exceptions:
          - org.springframework.web.client.HttpServerErrorException
        ignore-exceptions:
          - com.gongu.server.global.exception.BusinessException
  retry:
    instances:
      portone:
        max-attempts: 3
        wait-duration: 500ms
        retry-exceptions:
          - java.io.IOException
          - org.springframework.web.client.ResourceAccessException
          - org.springframework.web.client.HttpServerErrorException
```

- [ ] **Step 2: 실패하는(=RED가 아닌 통과 예상) 5xx 테스트 작성**

`PortOneClientResilienceTest.java` 생성. `@SpringBootTest`로 실제 `PortOneClient` 빈(+ resilience4j 애스펙트)을 띄우고, `MockServerRestClientCustomizer`를 `@TestConfiguration` 빈으로 등록해 자동 구성된 `RestClient.Builder`에 `MockRestServiceServer`를 바인딩한다.

```java
package com.gongu.server.global.infrastructure.portone;

import com.gongu.server.global.exception.InfraException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.client.MockServerRestClientCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.test.web.client.MockRestServiceServer;

import java.net.ConnectException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withException;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.hamcrest.Matchers.containsString;

@SpringBootTest(properties = {
        // 재시도가 (#213 수정 후) 살아나도 테스트가 느려지지 않도록 방어적으로 축소.
        // CB 기록 동작은 재시도 타이밍과 무관하므로 검증 유효성에는 영향 없음.
        "resilience4j.retry.instances.portone.wait-duration=1ms"
})
class PortOneClientResilienceTest {

    private static final String PAYMENT_ID = "pg-tx-1";

    @TestConfiguration
    static class MockServerConfig {
        @Bean
        MockServerRestClientCustomizer mockServerRestClientCustomizer() {
            return new MockServerRestClientCustomizer();
        }
    }

    @Autowired
    private PortOneClient portOneClient;

    @Autowired
    private CircuitBreakerRegistry circuitBreakerRegistry;

    @Autowired
    private MockServerRestClientCustomizer mockServerCustomizer;

    private MockRestServiceServer server;
    private CircuitBreaker circuitBreaker;

    @BeforeEach
    void setUp() {
        server = mockServerCustomizer.getServer();
        server.reset();
        circuitBreaker = circuitBreakerRegistry.circuitBreaker("portone");
        circuitBreaker.reset();
    }

    @AfterEach
    void tearDown() {
        circuitBreaker.reset();
    }

    /**
     * 서킷이 OPEN 될 때까지 getPayment를 반복 호출한다.
     * 각 호출은 fallback을 거쳐 InfraException으로 떨어지므로 삼켜 준다.
     * 애스펙트 순서(#213)에 따라 OPEN 도달에 필요한 논리 호출 수가 달라질 수 있어
     * 정확한 횟수 대신 "maxCalls 안에 OPEN에 도달하는가"로 판정한다.
     */
    private void driveUntilOpen(int maxCalls) {
        for (int i = 0; i < maxCalls; i++) {
            if (circuitBreaker.getState() == CircuitBreaker.State.OPEN) {
                return;
            }
            try {
                portOneClient.getPayment(PAYMENT_ID);
            } catch (RuntimeException expected) {
                // InfraException(재시도 소진/서킷 개방) 또는 원본 예외 — 삼킨다
            }
        }
    }

    @Test
    @DisplayName("PG 5xx가 반복되면 서킷이 OPEN 된다 (기존 동작 회귀 가드)")
    void serverError_opens_circuit() {
        server.expect(org.springframework.test.web.client.ExpectedCount.manyTimes(),
                        requestTo(containsString("/payments/")))
                .andRespond(withServerError());

        driveUntilOpen(30);

        assertThat(circuitBreaker.getState()).isEqualTo(CircuitBreaker.State.OPEN);
    }
}
```

> 구현 시 확인: `MockServerRestClientCustomizer#getServer()` 시그니처(무인자 vs `getServer(RestClient.Builder)`), `ExpectedCount` import 경로. 자동 구성 빌더가 하나뿐이면 무인자 `getServer()`로 충분하다.

- [ ] **Step 3: 5xx 테스트 실행 → 통과 확인**

Run: `./gradlew test --tests 'com.gongu.server.global.infrastructure.portone.PortOneClientResilienceTest'`
Expected: `serverError_opens_circuit` PASS. (5xx는 이미 `record-exceptions`에 있으므로 통과해야 정상. 실패하면 MockRestServiceServer 바인딩이 안 된 것 — `PortOneClient`가 mock 서버 대신 실제 `api.portone.io`로 나가는지 확인)

- [ ] **Step 4: 전체 스위트 회귀 확인**

Run: `./gradlew test`
Expected: BUILD SUCCESSFUL. 기존 테스트가 resilience4j 설정 추가로 깨지지 않는지 확인 (다른 테스트는 `PortOneClient`를 `@MockitoBean`으로 대체하므로 애스펙트 미적용 — 영향 없어야 정상).

- [ ] **Step 5: 커밋**

```bash
git add src/test/resources/application.yml src/test/java/com/gongu/server/global/infrastructure/portone/PortOneClientResilienceTest.java
git commit -m "test: 테스트 프로파일 resilience4j 설정 동기화 및 PortOne 서킷 개방 테스트 추가 (#214)"
```

---

## Task 2: `record-exceptions`에 네트워크 예외 추가 (본 수정)

**목표:** 네트워크 오류(`ResourceAccessException`)가 반복될 때 서킷이 열리도록 고친다. 먼저 그 케이스를 검증하는 테스트를 추가해 RED를 확인하고, 설정을 고쳐 GREEN으로 만든다.

**Files:**
- Modify: `src/test/java/com/gongu/server/global/infrastructure/portone/PortOneClientResilienceTest.java` (Task 1에서 생성, 네트워크 테스트 메서드 추가)
- Modify: `src/test/resources/application.yml` (`record-exceptions`에 2개 추가)
- Modify: `src/main/resources/application.yml` (54~55줄 `record-exceptions`에 2개 추가 + 51줄 근처에 임계값 유지 사유 주석)

**Interfaces:**
- Consumes: Task 1이 만든 `driveUntilOpen(int)`, `server`, `circuitBreaker`, `PAYMENT_ID`
- Produces: 없음 (최종 태스크)

- [ ] **Step 1: 네트워크 오류 테스트 메서드 추가 (RED)**

`PortOneClientResilienceTest`에 추가:

```java
    @Test
    @DisplayName("PG 연결 실패(ResourceAccessException)가 반복되면 서킷이 OPEN 된다")
    void networkError_opens_circuit() {
        server.expect(org.springframework.test.web.client.ExpectedCount.manyTimes(),
                        requestTo(containsString("/payments/")))
                .andRespond(withException(new ConnectException("simulated PG down")));

        driveUntilOpen(30);

        assertThat(circuitBreaker.getState()).isEqualTo(CircuitBreaker.State.OPEN);
    }

    @Test
    @DisplayName("서킷이 OPEN 되면 이후 호출은 PG에 닿지 않고 InfraException으로 빠르게 실패한다")
    void open_circuit_shortCircuits_withoutHittingPg() {
        server.expect(org.springframework.test.web.client.ExpectedCount.manyTimes(),
                        requestTo(containsString("/payments/")))
                .andRespond(withException(new ConnectException("simulated PG down")));
        driveUntilOpen(30);
        assertThat(circuitBreaker.getState()).isEqualTo(CircuitBreaker.State.OPEN);

        long notPermittedBefore = circuitBreaker.getMetrics().getNumberOfNotPermittedCalls();

        assertThatThrownBy(() -> portOneClient.getPayment(PAYMENT_ID))
                .isInstanceOf(InfraException.class);

        assertThat(circuitBreaker.getMetrics().getNumberOfNotPermittedCalls())
                .isGreaterThan(notPermittedBefore);
    }
```

- [ ] **Step 2: 네트워크 테스트 실행 → RED 확인**

Run: `./gradlew test --tests 'com.gongu.server.global.infrastructure.portone.PortOneClientResilienceTest'`
Expected: `networkError_opens_circuit` **FAIL** — `circuitBreaker.getState()`가 `CLOSED`. (현재 `record-exceptions`에 5xx만 있어 `ResourceAccessException`이 성공으로 집계되므로 서킷이 안 열림.) `open_circuit_shortCircuits_withoutHittingPg`도 FAIL(선행 조건 미충족). `serverError_opens_circuit`는 여전히 PASS.

- [ ] **Step 3: 테스트 `application.yml` `record-exceptions` 수정**

`src/test/resources/application.yml`의 `circuitbreaker...portone.record-exceptions`를 다음으로 교체 (`retry-exceptions`와 동일 집합):

```yaml
        record-exceptions:
          - org.springframework.web.client.HttpServerErrorException
          - org.springframework.web.client.ResourceAccessException
          - java.io.IOException
```

- [ ] **Step 4: 네트워크 테스트 실행 → GREEN 확인**

Run: `./gradlew test --tests 'com.gongu.server.global.infrastructure.portone.PortOneClientResilienceTest'`
Expected: 3개 테스트 모두 PASS.

- [ ] **Step 5: 운영 `application.yml` 수정 (동일 반영 + 주석)**

`src/main/resources/application.yml` 54~55줄의 `record-exceptions`를 Step 3과 동일하게 교체하고, 블록에 사유 주석을 단다:

```yaml
      portone:
        # sliding-window / failure-rate 임계값은 현행 유지.
        # 정상 상황의 타임아웃 발생 빈도 실측 데이터가 없어 임의 조정하지 않는다.
        # 부하 테스트(#152 계열)로 baseline 확보 후 재조정 — 이슈 #214 참고.
        sliding-window-size: 10
        failure-rate-threshold: 50
        wait-duration-in-open-state: 30s
        permitted-number-of-calls-in-half-open-state: 3
        register-health-indicator: true
        # record-exceptions는 retry-exceptions와 동일 집합으로 유지한다.
        # 어긋나면 "재시도는 하지만 서킷 집계는 안 되는" 예외가 생겨
        # PG 완전 다운 시 서킷이 열리지 않는다 (이슈 #214).
        record-exceptions:
          - org.springframework.web.client.HttpServerErrorException
          - org.springframework.web.client.ResourceAccessException
          - java.io.IOException
        ignore-exceptions:
            - com.gongu.server.global.exception.BusinessException
```

> `ignore-exceptions`의 들여쓰기(기존 12칸)는 기존 스타일 그대로 둔다 — 범위 밖 포매팅 변경 금지.

- [ ] **Step 6: 전체 스위트 실행**

Run: `./gradlew test`
Expected: BUILD SUCCESSFUL. jacoco 커버리지 게이트(`jacocoTestCoverageVerification`) 통과 확인.

- [ ] **Step 7: 커밋**

```bash
git add src/main/resources/application.yml src/test/resources/application.yml src/test/java/com/gongu/server/global/infrastructure/portone/PortOneClientResilienceTest.java
git commit -m "fix: 서킷브레이커 record-exceptions에 네트워크 예외 추가 (#214)"
```

---

## Self-Review

**Spec coverage (이슈 #214 완료 기준):**

| 완료 기준 | 대응 |
|---|---|
| `record-exceptions`에 네트워크 계열 추가 | Task 2 Step 5 (운영), Step 3 (테스트) |
| 연결 실패 반복 시 서킷이 실제로 열리는지 검증하는 테스트 추가 | Task 2 Step 1 `networkError_opens_circuit` + `open_circuit_shortCircuits_withoutHittingPg` |
| 타임아웃 발생 빈도 확인 후 임계값 적정성 재검토 | Task 2 Step 5 주석 — 데이터 부재로 현행 유지, 부하 테스트 후 재조정 명시 (D2 결정) |
| `ignore-exceptions`의 `BusinessException` 그대로 유지 | 변경 안 함 (명시) |
| 다중 인스턴스 서킷 상태 로컬 문제 | 이슈 본문에서 별도 이슈로 분리 — 범위 밖 |

**범위 밖 (건드리지 않음):** 애스펙트 순서(#213), `PortOneClient` javadoc(#213), CB 임계값 수치 변경, slow-call 설정, `#146` 트랜잭션 분리.

**Placeholder scan:** 코드 블록은 전부 실제 내용. `getServer()` 시그니처와 `ExpectedCount` import 경로만 구현 시 파일에서 확인 필요 — Step 2 주석에 명시.

**Type consistency:** `driveUntilOpen(int)`, `server`, `circuitBreaker`, `PAYMENT_ID`는 Task 1에서 정의, Task 2에서 재사용 — 이름 일치 확인. `CircuitBreaker.State.OPEN` enum 경로 일관.

---

## 실행 후 (워크플로 8~12단계)

1. `git push -u origin fix/#214-cb-record-network-exceptions` (워크트리가 origin/main을 추적 중이므로 `-u`로 자기 브랜치 추적으로 갱신)
2. `gh pr create` — 제목 `[FIX] 서킷브레이커가 네트워크 오류를 실패로 집계하도록 수정 (#214)`, 본문에 D1~D4 결정 요약 + 임계값 재검토를 후속으로 남긴 사유
3. `/codex:review` (Codex 미가용 시 Claude 서브에이전트 리뷰 — `subagent-driven-development`)
4. 리뷰 판정 → `.claude/review-process.md` 하드 게이트 준수
