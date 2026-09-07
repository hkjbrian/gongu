# 이슈 #213 — @Retry가 동작하도록 애스펙트 순서 수정

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** `resilience4j` 애스펙트 순서를 명시해 `@CircuitBreaker`가 `@Retry`보다 바깥에서 돌게 하여, PG 5xx·네트워크 오류 시 `getPayment`/`cancelPayment`가 실제로 `max-attempts(3)`만큼 재시도하도록 고친다.

**Architecture:** resilience4j-spring-boot3는 애스펙트 순서를 전역 프로퍼티로 조정한다(값이 작을수록 바깥). 기본값은 `@Retry`(LOWEST_PRECEDENCE − 4)가 `@CircuitBreaker`(LOWEST_PRECEDENCE − 3)보다 바깥이라, CB의 `fallbackMethod`가 재시도 루프 **안쪽**에서 실행돼 원본 예외를 `InfraException`으로 바꿔버린다. `@Retry`의 `retry-exceptions`에 `InfraException`이 없으므로 재시도 없이 종료된다. 두 애스펙트의 order 값을 **맞바꿔** CB를 바깥으로 보내면, 재시도 루프가 원본 예외(`HttpServerErrorException` 등)를 그대로 보고 3회 재시도하며, CB는 그 3회를 **1건**으로 집계한다. 코드 변경 없이 `application.yml` 설정 2줄(운영·테스트 각각)로 해결하고, 재시도가 실제로 발생하는지 `MockRestServiceServer` 호출 횟수로 검증한다.

**Tech Stack:** Spring Boot 3.5, Java 25, `io.github.resilience4j:resilience4j-spring-boot3:2.2.0`, JUnit 5, `MockRestServiceServer`(spring-test), H2

## Global Constraints

- 커밋 메시지 형식: `type: 작업 내용 (#213)` — `Co-Authored-By` 절 **절대 포함 금지** (`.claude/github-rules.md`)
- 브랜치: `fix/#213-retry-aspect-order` (이미 생성됨, `origin/main` 2795bee 기준 워크트리 `.claude/worktrees/fix-213`)
- Surgical Changes — 범위 밖은 건드리지 않는다:
  - `record-exceptions`/`retry-exceptions` 목록 (#214에서 확정, 동일 3개 집합 유지)
  - CB 임계값(`sliding-window-size`, `failure-rate-threshold` 등) — 데이터 없이 조정 금지
  - `PortOneClient`의 재시도/폴백 **로직** — 애노테이션·메서드 시그니처 불변
  - `#146` 트랜잭션 분리, `completePayment` 흐름
- 빌드/테스트: 워크트리 루트에서 `./gradlew test` (H2 인메모리 + `localhost:6380` Redis 필요)
- `resilience4j` 애스펙트 순서 프로퍼티 (kebab-case, 모듈 최상위 — `instances.portone` 아래 아님):
  - `resilience4j.retry.retry-aspect-order`
  - `resilience4j.circuitbreaker.circuit-breaker-aspect-order`
- 애스펙트 순서 값 (Spring `Ordered.LOWEST_PRECEDENCE` = `Integer.MAX_VALUE` = 2147483647):
  - `circuit-breaker-aspect-order: 2147483643` — `LOWEST_PRECEDENCE − 4` (바깥, 원래 Retry 자리)
  - `retry-aspect-order: 2147483644` — `LOWEST_PRECEDENCE − 3` (안쪽, 원래 CircuitBreaker 자리)

---

## 배경 — 확정된 설계 결정

| # | 결정 | 근거 |
|---|---|---|
| D1 | 애스펙트 순서를 조정하는 방식은 **두 기본값을 맞바꾼다** (`circuit-breaker-aspect-order: LOWEST−4`, `retry-aspect-order: LOWEST−3`). `1`/`2` 같은 작은 절대값은 쓰지 않는다 | resilience4j 다른 애스펙트(Bulkhead 등)의 상대 순서를 흐트러뜨리지 않는 최소 변경. 현재 `@Bulkhead` 등은 미사용이지만 ADR-008이 verify 벌크헤드 도입을 예고 |
| D2 | 애스펙트 순서 프로퍼티를 **운영·테스트 `application.yml` 양쪽**에 넣는다 | `ResilienceConfigParityTest` javadoc대로, 테스트 클래스패스에서 `src/test/resources/application.yml`이 운영 yml을 가린다. 테스트에 없으면 `PortOneClientResilienceTest`가 여전히 기본 순서로 돈다 |
| D3 | 검증은 **`MockRestServiceServer` 호출 횟수**로 한다 (`ExpectedCount.times(3)` + `server.verify()`) | "재시도가 살아났다"의 유일하게 관측 가능한 증거는 PG에 나간 HTTP 요청 수. CB 메트릭(`numberOfFailedCalls`)은 순서와 무관하게 1이라 판별력이 없다 |
| D4 | `record-exceptions`/`retry-exceptions` 목록은 **손대지 않는다** | #214에서 "동일 3개 집합"으로 확정. 이 이슈는 순서만 고친다 |
| D5 | `PortOneClient` javadoc(25~28행)에 애스펙트 순서가 **load-bearing**이라는 주석 한 줄 추가 | 현재 주석("@Retry 재시도 → 소진 시 CB fallback")은 수정 후 실제 동작과 일치하지만, 그 동작이 `application.yml` 순서 설정에 의존한다는 사실이 코드에 안 보인다 |
| D6 | `ResilienceConfigParityTest`에 애스펙트 순서 parity 검증 추가 | 누군가 한쪽 yml만 되돌리면 테스트는 통과하는데 운영은 버그로 회귀하는 상황 방지. 기존 parity 테스트는 `instances.portone` 맵만 비교하므로 모듈 최상위 프로퍼티는 커버 안 됨 |

---

## File Structure

| 파일 | 책임 | 변경 |
|---|---|---|
| `src/main/resources/application.yml` | 운영 resilience4j 설정 | `resilience4j.retry` / `resilience4j.circuitbreaker` 아래 `*-aspect-order` 2줄 + 사유 주석 |
| `src/test/resources/application.yml` | 테스트 프로파일 설정 | 동일 2줄 (운영과 동기화) |
| `src/main/java/com/gongu/server/global/infrastructure/portone/PortOneClient.java` | PortOne HTTP 클라이언트 | javadoc 주석 1줄 추가 (D5). **코드 무변경** |
| `src/test/java/com/gongu/server/global/infrastructure/portone/PortOneClientResilienceTest.java` | 서킷/재시도 동작 통합 테스트 | 재시도 횟수 검증 테스트 2개 추가 (getPayment 5xx, cancelPayment 5xx) |
| `src/test/java/com/gongu/server/global/infrastructure/portone/ResilienceConfigParityTest.java` | main/test yml 설정 동기화 가드 | 애스펙트 순서 parity 테스트 1개 추가 |

Task 1이 운영 동작을 고치고, Task 2가 회귀 가드를 세운다. 두 태스크는 독립 리뷰 가능하다.

---

## Task 1: 애스펙트 순서 수정 + 재시도 실측 회귀 테스트

**목표:** `getPayment`/`cancelPayment`가 5xx에서 3회 재호출하는지 검증하는 테스트를 추가하고(RED — 현재 1회), 애스펙트 순서를 맞바꿔 GREEN으로 만든다.

**Files:**
- Modify: `src/test/java/com/gongu/server/global/infrastructure/portone/PortOneClientResilienceTest.java` (테스트 메서드 2개 추가)
- Modify: `src/test/resources/application.yml` (`resilience4j.retry` / `resilience4j.circuitbreaker` 최상위에 `*-aspect-order`)
- Modify: `src/main/resources/application.yml` (동일)
- Modify: `src/main/java/com/gongu/server/global/infrastructure/portone/PortOneClient.java` (javadoc 1줄)

**Interfaces:**
- Consumes:
  - `PortOneClient#getPayment(String)` → `PortOnePaymentResponse` (5xx → `InfraException`)
  - `PortOneClient#cancelPayment(String, String)` → `PortOnePaymentResponse` (5xx → `InfraException`)
  - Task 클래스 기존 필드: `portOneClient`, `server`(`MockRestServiceServer`), `circuitBreaker`, `PAYMENT_ID`(`"pg-tx-1"`)
  - 기존 `@SpringBootTest(properties=...)`에 `resilience4j.retry.instances.portone.wait-duration=1ms` 이미 존재 → 재시도 3회가 빠르게 끝남
- Produces: 없음 (Task 2는 이 태스크의 yml 변경을 검증만 함)

- [ ] **Step 1: 재시도 횟수 검증 테스트 2개 작성 (RED 예상)**

`PortOneClientResilienceTest`에 아래 두 메서드를 추가한다. import는 이미 대부분 존재 —
`ExpectedCount`는 파일에서 이미 FQCN(`org.springframework.test.web.client.ExpectedCount`)으로
쓰고 있으므로 동일하게 FQCN 사용. `withServerError`, `requestTo`, `containsString`,
`assertThatThrownBy`, `InfraException`은 기존 import에 있음.

```java
    @Test
    @DisplayName("PG 5xx 응답 시 @Retry가 max-attempts(3)만큼 getPayment를 재호출한다 (#213)")
    void getPayment_retriesUpToMaxAttempts_onServerError() {
        server.expect(org.springframework.test.web.client.ExpectedCount.times(3),
                        requestTo(containsString("/payments/" + PAYMENT_ID)))
                .andRespond(withServerError());

        assertThatThrownBy(() -> portOneClient.getPayment(PAYMENT_ID))
                .isInstanceOf(InfraException.class);

        server.verify();
    }

    @Test
    @DisplayName("PG 5xx 응답 시 @Retry가 max-attempts(3)만큼 cancelPayment를 재호출한다 (#213)")
    void cancelPayment_retriesUpToMaxAttempts_onServerError() {
        server.expect(org.springframework.test.web.client.ExpectedCount.times(3),
                        requestTo(containsString("/payments/" + PAYMENT_ID + "/cancel")))
                .andRespond(withServerError());

        assertThatThrownBy(() -> portOneClient.cancelPayment(PAYMENT_ID, "재시도 검증"))
                .isInstanceOf(InfraException.class);

        server.verify();
    }
```

> 판별 원리: `MockRestServiceServer`는 `times(3)`을 요청하지만 버그 상태에서는 1회만
> 호출된다 → `server.verify()`가 `AssertionError: Further request(s) expected` 로 실패한다.
> 순서를 고치면 3회 모두 소비되어 통과한다.

- [ ] **Step 2: 테스트 실행 → RED 확인**

Run: `./gradlew test --tests 'com.gongu.server.global.infrastructure.portone.PortOneClientResilienceTest'`
Expected: `getPayment_retriesUpToMaxAttempts_onServerError`, `cancelPayment_retriesUpToMaxAttempts_onServerError` **FAIL**
(`java.lang.AssertionError: Further request(s) expected ... 2 request(s) executed` 형태 — 실제로는 1회만 실행).
기존 3개 테스트(`serverError_opens_circuit` 등)는 여전히 PASS.

만약 RED가 아니라 GREEN이면 — 이미 순서가 어딘가에서 조정됐거나 라이브러리 버전 동작이
다른 것. 진행 전 `git log`/`application.yml`에서 `aspect-order` 흔적을 확인하고 사용자에게 보고.

- [ ] **Step 3: 테스트 `application.yml`에 애스펙트 순서 추가**

`src/test/resources/application.yml`의 `resilience4j:` 블록을 수정한다. `circuitbreaker:`와
`retry:`는 이미 존재하므로 각 모듈 바로 아래(= `instances:`와 같은 깊이)에 한 줄씩 추가:

```yaml
resilience4j:
  # src/main/resources/application.yml 의 resilience4j.portone 설정과 동기화 유지 — ResilienceConfigParityTest 가 강제 (#214, #213)
  circuitbreaker:
    # @CircuitBreaker를 @Retry보다 바깥으로: 재시도 3회를 CB 실패 1건으로 집계 (#213)
    # 값이 작을수록 바깥. LOWEST_PRECEDENCE(2147483647) − 4 = 원래 @Retry 기본 자리
    circuit-breaker-aspect-order: 2147483643
    instances:
      portone:
        sliding-window-size: 10
        minimum-number-of-calls: 10
        failure-rate-threshold: 50
        wait-duration-in-open-state: 30s
        permitted-number-of-calls-in-half-open-state: 3
        register-health-indicator: true
        record-exceptions:
          - org.springframework.web.client.HttpServerErrorException
          - org.springframework.web.client.ResourceAccessException
          - java.io.IOException
        ignore-exceptions:
          - com.gongu.server.global.exception.BusinessException
  retry:
    # @Retry를 @CircuitBreaker보다 안쪽으로: 원본 예외(HttpServerErrorException 등)를
    # 재시도 루프가 직접 보게 한다. LOWEST_PRECEDENCE − 3 = 원래 @CircuitBreaker 기본 자리 (#213)
    retry-aspect-order: 2147483644
    instances:
      portone:
        max-attempts: 3
        wait-duration: 500ms
        retry-exceptions:
          - java.io.IOException
          - org.springframework.web.client.ResourceAccessException
          - org.springframework.web.client.HttpServerErrorException
```

> 구현 시 확인: 현재 `src/test/resources/application.yml`의 `resilience4j` 블록 실제 들여쓰기·키
> 순서에 맞춰 삽입한다(위는 논리적 구조 예시). `instances:` 하위는 **건드리지 않는다** —
> `circuit-breaker-aspect-order` / `retry-aspect-order`만 각 모듈 키 바로 아래에 추가.

- [ ] **Step 4: 테스트 실행 → GREEN 확인**

Run: `./gradlew test --tests 'com.gongu.server.global.infrastructure.portone.PortOneClientResilienceTest'`
Expected: 5개 테스트 모두 PASS. (`ResilienceConfigParityTest`는 아직 Step 5 전이라 FAIL할 수 있음 — 다음 스텝에서 해소)

- [ ] **Step 5: 운영 `application.yml`에 동일 반영**

`src/main/resources/application.yml`의 `resilience4j:` 블록에 Step 3과 **동일한** 두 줄을
같은 위치(각 모듈 키 바로 아래, `instances:`와 같은 깊이)에 추가하고 주석을 단다:

```yaml
resilience4j:
  circuitbreaker:
    # @CircuitBreaker를 @Retry보다 바깥으로: 재시도 3회를 CB 실패 1건으로 집계 (#213).
    # 기본 순서(@Retry가 바깥)에서는 CB fallback이 재시도 루프 안에서 InfraException으로
    # 변환돼 @Retry가 무력화된다. 값이 작을수록 바깥 — LOWEST_PRECEDENCE(2147483647) − 4.
    circuit-breaker-aspect-order: 2147483643
    instances:
      portone:
        # ... 기존 내용 그대로 ...
  retry:
    # @Retry를 @CircuitBreaker보다 안쪽으로 (#213). LOWEST_PRECEDENCE − 3.
    retry-aspect-order: 2147483644
    instances:
      portone:
        # ... 기존 내용 그대로 ...
```

- [ ] **Step 6: `PortOneClient` javadoc 주석 갱신 (D5)**

`src/main/java/com/gongu/server/global/infrastructure/portone/PortOneClient.java` 24~28행의
클래스/메서드 상단 주석 블록에 한 줄 추가:

```java
    /**
     * 4xx → BusinessException 즉시 전파 (CB/Retry ignore-exceptions 설정으로 장애 집계 제외)
     * 5xx·네트워크 오류 → @Retry 재시도 → 소진 시 CB fallback → InfraException
     *
     * 이 순서는 application.yml 의 resilience4j.*-aspect-order 에 의존한다 (#213):
     * @CircuitBreaker 가 @Retry 보다 바깥이어야 재시도가 원본 예외를 보고 동작한다.
     */
```

> `cancelPayment`(52행) 위에도 같은 성격의 주석이 있으면 동일하게 한 줄 추가. 없으면 생략.

- [ ] **Step 7: 전체 스위트 실행**

Run: `./gradlew test`
Expected: BUILD SUCCESSFUL. `ResilienceConfigParityTest` 2개 테스트는 여전히 PASS
(애스펙트 순서 키는 `instances.portone` 밖이라 기존 parity 비교에 안 잡힘 — Task 2에서 커버).
jacoco 커버리지 게이트 통과.

- [ ] **Step 8: 커밋**

```bash
git add src/main/resources/application.yml src/test/resources/application.yml \
        src/main/java/com/gongu/server/global/infrastructure/portone/PortOneClient.java \
        src/test/java/com/gongu/server/global/infrastructure/portone/PortOneClientResilienceTest.java
git commit -m "fix: resilience4j 애스펙트 순서를 조정해 @Retry 복구 (#213)"
```

---

## Task 2: 애스펙트 순서 main/test 동기화 가드

**목표:** 운영·테스트 yml의 애스펙트 순서가 어긋나면 테스트가 실패하도록 `ResilienceConfigParityTest`를 확장한다.

**Files:**
- Modify: `src/test/java/com/gongu/server/global/infrastructure/portone/ResilienceConfigParityTest.java` (테스트 메서드 1개 + 헬퍼 1개 추가)

**Interfaces:**
- Consumes: 기존 `private static Map<String,Object> load(Path)`, 상수 `MAIN_YML`, `TEST_YML`
- Produces: 없음 (최종 태스크)

- [ ] **Step 1: 애스펙트 순서 parity 테스트 작성 (GREEN 예상 — Task 1이 이미 양쪽을 맞춰둠)**

`ResilienceConfigParityTest`에 추가. 기존 `portoneNode`는 `instances.portone`까지 내려가므로
재사용 불가 — 모듈 최상위에서 `*-aspect-order` 스칼라를 꺼내는 헬퍼를 새로 둔다:

```java
    @Test
    @DisplayName("resilience4j 애스펙트 순서(circuit-breaker/retry)가 main/test yml 사이에서 동일하다 (#213)")
    void aspectOrder_isInSync() throws IOException {
        Map<String, Object> main = load(MAIN_YML);
        Map<String, Object> test = load(TEST_YML);

        assertThat(aspectOrder(test, "circuitbreaker", "circuit-breaker-aspect-order"))
                .as("circuit-breaker-aspect-order")
                .isEqualTo(aspectOrder(main, "circuitbreaker", "circuit-breaker-aspect-order"))
                .isNotNull();

        assertThat(aspectOrder(test, "retry", "retry-aspect-order"))
                .as("retry-aspect-order")
                .isEqualTo(aspectOrder(main, "retry", "retry-aspect-order"))
                .isNotNull();
    }

    @Test
    @DisplayName("circuit-breaker 애스펙트가 retry 애스펙트보다 바깥이다 (값이 더 작다) (#213)")
    void circuitBreaker_isOuterThanRetry() throws IOException {
        Map<String, Object> main = load(MAIN_YML);

        Integer cb = aspectOrder(main, "circuitbreaker", "circuit-breaker-aspect-order");
        Integer retry = aspectOrder(main, "retry", "retry-aspect-order");

        assertThat(cb).as("circuit-breaker-aspect-order").isNotNull();
        assertThat(retry).as("retry-aspect-order").isNotNull();
        assertThat(cb).as("CB가 Retry보다 바깥 (더 작은 order)").isLessThan(retry);
    }

    @SuppressWarnings("unchecked")
    private static Integer aspectOrder(Map<String, Object> root, String module, String key) {
        Map<String, Object> resilience4j = (Map<String, Object>) root.get("resilience4j");
        Map<String, Object> mod = (Map<String, Object>) resilience4j.get(module);
        assertThat(mod).as("resilience4j.%s", module).isNotNull();
        Object v = mod.get(key);
        return v == null ? null : ((Number) v).intValue();
    }
```

- [ ] **Step 2: 테스트 실행 → GREEN 확인**

Run: `./gradlew test --tests 'com.gongu.server.global.infrastructure.portone.ResilienceConfigParityTest'`
Expected: 4개 테스트 모두 PASS.

- [ ] **Step 3: 가드가 실제로 무는지 역검증 (수동, 커밋 안 함)**

`src/test/resources/application.yml`의 `retry-aspect-order` 값을 임시로 `1` 바꿔 실행:
Run: `./gradlew test --tests 'com.gongu.server.global.infrastructure.portone.ResilienceConfigParityTest'`
Expected: `aspectOrder_isInSync` FAIL. → 값 원복 후 재실행하여 GREEN 확인.

- [ ] **Step 4: 전체 스위트 실행**

Run: `./gradlew test`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 5: 커밋**

```bash
git add src/test/java/com/gongu/server/global/infrastructure/portone/ResilienceConfigParityTest.java
git commit -m "test: 애스펙트 순서 main/test yml 동기화 가드 추가 (#213)"
```

---

## Self-Review

**Spec coverage (이슈 #213 완료 기준):**

| 이슈 항목 | 대응 |
|---|---|
| `@Retry`가 실제로 max-attempts만큼 호출되지 않는 버그 수정 | Task 1 Step 3·5 (애스펙트 순서 맞바꿈) |
| 재시도가 살아났는지 검증하는 테스트 | Task 1 Step 1 (`getPayment`/`cancelPayment` 각각 `times(3)` + `server.verify()`) |
| 코드 주석이 실제 동작과 반대로 기술된 문제 | Task 1 Step 6 (수정 후 주석이 동작과 일치 + 순서 의존성 명시) |
| #214 코멘트("#213이 두 줄만 추가하면 충돌 없음")와 정합 | 두 줄만 추가, `instances.portone` 불변, `driveUntilOpen`은 순서 무관 |
| 다중 인스턴스 서킷 로컬 문제 | 이슈 본문에서 별도 검토 대상 — 범위 밖 |
| 타임아웃을 실패로 셀지 임계값 재검토 | #214 담당 — 범위 밖 |

**범위 밖 (건드리지 않음):** `record-exceptions`/`retry-exceptions` 목록, CB 임계값, `PortOneClient` 재시도/폴백 로직, `#146` 트랜잭션 분리, `PaymentService`.

**Placeholder scan:** 코드 블록은 전부 실제 내용. Task 1 Step 3은 "현재 파일 들여쓰기에 맞춰 삽입"만 구현 시 확인(키 2개 위치는 명시). Task 1 Step 6의 `cancelPayment` 주석은 "있으면 추가"로 조건부 명시.

**Type consistency:**
- `PAYMENT_ID` = `"pg-tx-1"` (기존 상수 재사용), `server`(`MockRestServiceServer`), `circuitBreaker` — 기존 필드명 그대로
- `cancelPayment` URI = `/payments/{paymentId}/cancel` (`PortOneClient` 57행 확인), 테스트 매처 `containsString("/payments/" + PAYMENT_ID + "/cancel")` 일치
- `aspectOrder(...)` 헬퍼: Task 2에서 정의·사용, 반환 `Integer` — `isLessThan(Integer)` 호환
- 애스펙트 순서 값 `2147483643`(CB) < `2147483644`(Retry) — `circuitBreaker_isOuterThanRetry`의 `isLessThan` 통과

**전제 확인 사항 (구현자가 첫 스텝에서 검증):**
- `resilience4j-spring-boot3:2.2.0`이 `resilience4j.retry.retry-aspect-order` /
  `resilience4j.circuitbreaker.circuit-breaker-aspect-order` kebab 키를 바인딩하는가
  (Spring relaxed binding — camelCase도 허용). Step 2의 RED→Step 4의 GREEN 전이가 이 사실을 증명한다.

---

## 실행 후 (워크플로 8~12단계)

1. `git push -u origin fix/#213-retry-aspect-order`
2. `gh pr create` — 제목 `[FIX] 애스펙트 순서로 @Retry가 동작하지 않음 (#213)`, 본문에 D1~D6 결정 요약, #214 코멘트와의 정합, 실측 근거(RED: 1회 → GREEN: 3회)
3. 코드 리뷰 — Codex 미가용이므로 Claude 서브에이전트 (`subagent-driven-development`의 2단계 리뷰 또는 별도 리뷰 서브에이전트)
4. 리뷰 판정 → `.claude/review-process.md` 하드 게이트 (Gate 1 인라인 코멘트 → Gate 2 사용자 합의 → Gate 3 thread reply)
5. merge는 사용자가 직접

## ADR-008 이행 순서에서의 위치

이 이슈는 ADR-008 §7 이행 순서의 **1번**이다 — "측정 baseline 신뢰성"의 선행 조건.
완료 후: 즉시 방어(A안: read-timeout 하향/벌크헤드) → #209(이력) → D1(`PaymentReconciler`) → §5 실험 순으로 진행.
