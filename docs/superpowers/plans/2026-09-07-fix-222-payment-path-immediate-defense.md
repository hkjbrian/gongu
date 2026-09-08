# 이슈 #222 — 결제 확정 경로 즉시 방어

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** `completePayment()`가 락+커넥션을 쥔 채 PortOne을 호출할 때의 파국적 꼬리를 두 겹으로 바운드한다 — (1) PortOne 전용 HTTP 타임아웃, (2) `completePayment` 동시 실행 상한(Bulkhead).

> **범위 축소 (2026-09-08, 사용자 결정):** 최초 계획의 Part 3(MySQL `innodb_lock_wait_timeout`)은 이 PR에서 **제외**한다. Part 1+2로 락 보유가 ~7s로 줄고 동시 확정이 20으로 묶이면 한계 효용이 낮고, H2 제약으로 자동 테스트도 예외 매핑에 한정되며, 인프라 변경이 섞인다. 락 대기 상한은 ADR-008 D1(`PaymentReconciler` — 락 구조 자체 재설계) 때 함께 재검토한다.

**Architecture:** 근본 재설계(ADR-008 D1 `PaymentReconciler`, 조건부 UPDATE)는 후속이다. 이 이슈는 **설정·경계 방어**만 한다. #213으로 `@Retry`가 실제 동작하기 시작하며 `getPayment` 최악 소요가 ~16s로 늘었고(3×5s+2×0.5s), HikariCP 25 풀에서 verify 도착률 λ≈2/s면 리틀의 법칙상 고갈된다. Part 1이 각 PG 시도를 2s로 바운드해 최악을 ~7s로 낮추고, Part 2가 그 위에서 동시 완료 스레드를 20개로 제한한다(< 풀 25) — 초과분은 커넥션·락을 점유하기 전에 503으로 거절된다.

**Tech Stack:** Spring Boot 3.5.14, Java 25, `io.github.resilience4j:resilience4j-spring-boot3:2.2.0` (bulkhead 포함), MySQL 8.0 (운영), H2 (테스트), JUnit 5, JDK `com.sun.net.httpserver.HttpServer` (Part 1 테스트용 — 신규 의존성 없음)

## Global Constraints

- 커밋 메시지 형식: `type: 작업 내용 (#222)` — `Co-Authored-By` 절 **절대 포함 금지** (`.claude/github-rules.md`)
- 브랜치: `fix/#222-payment-path-immediate-defense` (워크트리 `.claude/worktrees/fix-222`, `origin/main` 7f8237a 기준)
- Surgical Changes — 범위 밖 금지:
  - `completePayment` **비즈니스 로직·상태 전이** 불변 (락 순서, 보상 분기, 멱등 처리)
  - CB 임계값(`sliding-window-size` 등), `record-exceptions`/`retry-exceptions` (#214 확정)
  - `@Retry`/`@CircuitBreaker` 애스펙트 순서 (#213 확정) — `application.yml`의 `*-aspect-order` 두 줄 불변
  - `PaymentReconciler`·조건부 UPDATE·verify 202 응답 (ADR-008 D1/D2/#216 — 후속)
- 빌드/테스트: 워크트리 루트에서 `./gradlew test` (H2 + `localhost:6380` Redis 필요). 부분 실행 시 `-x jacocoTestCoverageVerification` (필터 실행은 커버리지 게이트가 무조건 실패 — 실제 테스트 실패 아님)
- 파라미터 확정값:
  - PortOne: `portone.connect-timeout: 2s`, `portone.read-timeout: 2s` (Kakao는 전역 `spring.http.client` 5s 유지)
  - Bulkhead `payment-complete`: `max-concurrent-calls: 20`, `max-wait-duration: 500ms`
- 신규 예외 → HTTP 매핑: `BulkheadFullException` → 기존 `PaymentErrorCode.PAYMENT_PG_UNAVAILABLE`(503, 재시도 가능) 재사용. 신규 에러코드 만들지 않는다 (webhook `WEBHOOK_TERMINAL_CODES`에 없으므로 자동으로 재시도 유발됨)

---

## 배경 — 확정된 설계 결정

| # | 결정 | 근거 |
|---|---|---|
| D1 | PortOne 타임아웃은 **전용**으로 낮춘다 (`PortOneProperties` + `RestClientConfig`), 전역 `spring.http.client`는 불변 | `KakaoApiClient`도 자동 구성 `RestClient.Builder`를 공유한다. 전역 하향은 OAuth 호출에 영향 |
| D2 | `read-timeout: 2s` | 정상 PortOne 응답은 수백 ms. `getPayment` 최악 = 3회 × 2s + 2 × 0.5s ≈ 7s (락 보유 상한). 실측 데이터 확보(#209/부하테스트) 후 재조정. 단, 금액 불일치·주문 만료 보상 분기는 `getPayment` 뒤에 `cancelPayment`를 **락 보유 상태로** 한 번 더 호출하므로 그 경로 최악은 ≈14s (#222 이전 ≈32s). Bulkhead가 동시성은 여전히 20으로 제한. |
| D3 | Bulkhead는 `PaymentService.completePayment`에 건다 (PortOne 클라이언트 아님) | resilience4j Bulkhead 애스펙트는 항상 최내곽(하드코딩 `LOWEST_PRECEDENCE−1`). `getPayment`에 걸면 각 재시도가 permit을 재획득. `completePayment`에 걸면 "동시 확정 연산 N개"를 정확히 제한 |
| D4 | Bulkhead 애스펙트(LOWEST−1)가 `@Transactional`(LOWEST)보다 바깥 → permit이 트랜잭션 전체 구간 동안 유지 | 원하는 동작. permit 보유 = DB 커넥션+락+PG 호출 전체를 커버 |
| D5 | `max-concurrent-calls: 20`, `max-wait-duration: 500ms` | 20 < 풀 25 → 다른 엔드포인트·만료 스케줄러에 5커넥션 여유. 500ms 대기는 정상 버스트를 흡수(대기 스레드는 커넥션 미보유), 지속 포화만 거절. 부하테스트 `02-pg-latency.js`는 VUS 15라 정상 시 거절 없음 |
| D6 | 신규 예외는 `PAYMENT_PG_UNAVAILABLE`(503) 재사용, 신규 코드 없음 | "PG 불가"라는 메시지가 로컬 경합에는 부정확하나 API 표면 변경 0. 전용 코드는 ADR-008 D6(payment_events) 때 관측성과 함께 재검토 |
| D7 | MySQL 락 대기 상한(`innodb_lock_wait_timeout`)은 **이 이슈에서 제외** | Part 1+2로 한계 효용 낮음 + H2 제약 + 인프라 변경. ADR-008 D1(`PaymentReconciler`)에서 락 구조와 함께 재검토 |

---

## File Structure

| 파일 | 책임 | 변경 |
|---|---|---|
| `src/main/java/.../global/infrastructure/portone/PortOneProperties.java` | PortOne 설정 record | `connectTimeout` / `readTimeout` (`Duration`) 필드 추가 |
| `src/main/java/.../global/config/RestClientConfig.java` | `portOneRestClient` 빈 | `ClientHttpRequestFactorySettings`로 전용 타임아웃 적용 |
| `src/main/resources/application.yml` | 운영 설정 | `portone.connect-timeout` / `read-timeout`; `resilience4j.bulkhead.instances.payment-complete` |
| `src/test/resources/application.yml` | 테스트 설정 | 동일 2블록 (parity) |
| `src/main/java/.../domain/payment/service/PaymentService.java` | 결제 확정 | `completePayment`에 `@Bulkhead(name="payment-complete")` — **로직 무변경** |
| `src/main/java/.../global/exception/GlobalExceptionHandler.java` | 전역 예외 → HTTP | `BulkheadFullException` 핸들러 1개 |
| `src/test/java/.../global/infrastructure/portone/PortOneTimeoutTest.java` | Part 1 행위 테스트 | 신규 — JDK HttpServer 스텁으로 실제 타임아웃 검증 |
| `src/test/java/.../domain/payment/service/PaymentCompleteBulkheadTest.java` | Part 2 행위 테스트 | 신규 — permit 소진 시 `BulkheadFullException` |
| `src/test/java/.../global/exception/GlobalExceptionHandlerTest.java` | 예외 매핑 테스트 | 신규 또는 기존에 케이스 추가 (구현 시 존재 확인) |

Task 1 = Part 1(타임아웃). Task 2 = Part 2(Bulkhead) + 예외 매핑. 각 태스크는 독립 리뷰 가능.

---

## Task 1: PortOne 전용 HTTP 타임아웃

**목표:** `getPayment`/`cancelPayment`의 각 HTTP 시도를 2s로 바운드한다. Kakao는 영향 없음. JDK `HttpServer` 스텁으로 "느린 PG → 타임아웃까지 걸리는 실제 시간"을 검증한다.

**Files:**
- Modify: `src/main/java/com/gongu/server/global/infrastructure/portone/PortOneProperties.java`
- Modify: `src/main/java/com/gongu/server/global/config/RestClientConfig.java`
- Modify: `src/main/resources/application.yml`, `src/test/resources/application.yml`
- Create: `src/test/java/com/gongu/server/global/infrastructure/portone/PortOneTimeoutTest.java`

**Interfaces:**
- Consumes: `PortOneProperties` (현재 `record PortOneProperties(String apiSecret, String baseUrl)`), `PortOneClient#getPayment(String)`
- Produces: `PortOneProperties`에 `Duration connectTimeout, Duration readTimeout` 접근자 — Task 2/3은 사용 안 함

- [ ] **Step 1: 느린 PG에 대해 타임아웃이 2s대인지 검증하는 테스트 작성 (RED)**

`PortOneTimeoutTest.java` 생성. JDK `com.sun.net.httpserver.HttpServer`를 임의 포트에 띄우고 `/payments/{id}` 핸들러가 5초간 잠들게 한다. `portone.base-url`을 그 포트로 지정. `getPayment` 호출이 **~7초 안에**(2s×3 재시도 + 0.5s×2 ≈ 7s) `InfraException`으로 끝나는지, 그리고 **15초(5s×3)보다 확실히 빠른지** 검증.

```java
package com.gongu.server.global.infrastructure.portone;

import com.gongu.server.global.exception.InfraException;
import com.sun.net.httpserver.HttpServer;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.time.Duration;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest(properties = {
        "portone.api-secret=test-secret",
        // 재시도 대기는 그대로 두되(0.5s) 각 시도의 read-timeout이 짧아야 함
})
@DisplayName("PortOne 전용 HTTP 타임아웃 (#222)")
class PortOneTimeoutTest {

    private static HttpServer server;
    private static final AtomicInteger hits = new AtomicInteger();

    @DynamicPropertySource
    static void portOneBaseUrl(DynamicPropertyRegistry registry) throws IOException {
        server = HttpServer.create(new InetSocketAddress(0), 0);
        server.setExecutor(Executors.newCachedThreadPool());
        server.createContext("/payments/", exchange -> {
            hits.incrementAndGet();
            try {
                Thread.sleep(5_000); // read-timeout(2s)보다 확실히 김
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
            byte[] body = "{}".getBytes();
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
        registry.add("portone.base-url", () -> "http://localhost:" + server.getAddress().getPort());
    }

    @Autowired
    private PortOneClient portOneClient;
    @Autowired
    private CircuitBreakerRegistry circuitBreakerRegistry;

    @BeforeEach
    void reset() {
        hits.set(0);
        circuitBreakerRegistry.circuitBreaker("portone").reset();
    }

    @AfterEach
    void tearDownCb() {
        circuitBreakerRegistry.circuitBreaker("portone").reset();
    }

    @Test
    @DisplayName("느린 PG는 전용 read-timeout(2s)으로 시도마다 끊기고, 총 소요가 5s×3보다 빠르다")
    void slowPg_isBoundedByDedicatedReadTimeout() {
        long start = System.nanoTime();

        assertThatThrownBy(() -> portOneClient.getPayment("pg-timeout-1"))
                .isInstanceOf(InfraException.class);

        Duration elapsed = Duration.ofNanos(System.nanoTime() - start);
        // 3회 재시도 × ~2s + 2 × 0.5s ≈ 7s. 전역 5s가 적용됐다면 ≈16s.
        assertThat(elapsed).isLessThan(Duration.ofSeconds(12));
        assertThat(hits.get()).isEqualTo(3); // @Retry max-attempts
    }

    @org.junit.jupiter.api.AfterAll
    static void stopServer() {
        if (server != null) server.stop(0);
    }
}
```

> 구현 시 확인:
> - `@AfterAll` 정적 메서드가 `HttpServer` 종료. `server.stop(0)` 즉시 종료.
> - `getPayment`의 URI 템플릿은 `/payments/{paymentId}` → 컨텍스트 `/payments/` 프리픽스 매칭 OK.
> - `hits.get() == 3` — Task 1 시점에는 이미 #213 적용됐으므로 재시도 3회 정상. 만약 1이면 #213 회귀.
> - RED: 현재 전역 5s만 존재 → `elapsed`가 ~16s → `isLessThan(12s)` **FAIL**.

- [ ] **Step 2: RED 확인**

Run: `./gradlew test --tests 'com.gongu.server.global.infrastructure.portone.PortOneTimeoutTest' -x jacocoTestCoverageVerification`
Expected: FAIL — `elapsed` ~16s, `isLessThan(Duration.ofSeconds(12))` 실패. (테스트 자체가 ~16s 소요 후 실패)

- [ ] **Step 3: `PortOneProperties`에 타임아웃 필드 추가**

```java
package com.gongu.server.global.infrastructure.portone;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

@ConfigurationProperties(prefix = "portone")
public record PortOneProperties(
        String apiSecret,
        String baseUrl,
        Duration connectTimeout,
        Duration readTimeout
) {
}
```

- [ ] **Step 4: `RestClientConfig`에서 전용 타임아웃 적용**

```java
package com.gongu.server.global.config;

import com.gongu.server.global.infrastructure.portone.PortOneProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.http.client.ClientHttpRequestFactoryBuilder;
import org.springframework.boot.http.client.ClientHttpRequestFactorySettings;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestClient;

@Configuration
@EnableConfigurationProperties(PortOneProperties.class)
public class RestClientConfig {

    /**
     * PortOne 전용 RestClient. 전역 spring.http.client 타임아웃 대신
     * portone.connect-timeout / portone.read-timeout 을 적용한다 (#222).
     * KakaoApiClient 등 다른 소비자는 전역 설정을 그대로 쓴다.
     */
    @Bean
    public RestClient portOneRestClient(RestClient.Builder restClientBuilder, PortOneProperties props) {
        ClientHttpRequestFactorySettings settings = ClientHttpRequestFactorySettings.defaults()
                .withConnectTimeout(props.connectTimeout())
                .withReadTimeout(props.readTimeout());

        return restClientBuilder
                .baseUrl(props.baseUrl())
                .defaultHeader("Authorization", "PortOne " + props.apiSecret())
                .requestFactory(ClientHttpRequestFactoryBuilder.detect().build(settings))
                .build();
    }
}
```

> 구현 시 확인:
> - Spring Boot 3.5의 패키지: `org.springframework.boot.http.client.ClientHttpRequestFactorySettings` / `ClientHttpRequestFactoryBuilder` (구 `org.springframework.boot.web.client.ClientHttpRequestFactorySettings`는 deprecated). import 경로가 다르면 IDE/컴파일 에러로 즉시 드러남 — 실제 클래스패스에서 확인.
> - `restClientBuilder`(주입된 것)는 Spring Boot autoconfig가 프로토타입 스코프로 제공 → `KakaoApiClient`의 빌더 인스턴스와 별개. `.requestFactory()` 호출이 Kakao에 영향 없음.

- [ ] **Step 5: 운영 `application.yml`에 타임아웃 추가**

`portone:` 블록에 추가:
```yaml
portone:
  api-secret: ${PORTONE_API_SECRET}
  base-url: https://api.portone.io
  webhook-secret: ${PORTONE_WEBHOOK_SECRET:}
  # 전용 타임아웃 — 락+커넥션을 쥔 채 하는 호출이라 짧게 바운드 (#222, ADR-008 Option 0).
  # getPayment 최악 = 3회 × read-timeout + 2 × retry wait ≈ 7s. 실측 후 재조정(#209).
  connect-timeout: 2s
  read-timeout: 2s
```

- [ ] **Step 6: 테스트 `application.yml`에도 동일 추가**

`src/test/resources/application.yml`의 `portone:` 블록(없으면 추가 위치는 기존 파일 확인)에 `connect-timeout: 2s` / `read-timeout: 2s`. 없다면 `portone:` 블록 자체를 만들되 `api-secret`/`base-url`은 기존 테스트가 `@SpringBootTest(properties=...)`로 주입하므로 타임아웃 두 줄만.

> 구현 시 확인: 현재 `src/test/resources/application.yml`에 `portone:` 블록이 있는지. 없으면:
> ```yaml
> portone:
>   connect-timeout: 2s
>   read-timeout: 2s
> ```
> `PortOneProperties`는 record라 `api-secret`/`base-url`이 null이어도 빈 생성은 됨 (기존 `PortOneClientResilienceTest`가 그렇게 동작 중).

- [ ] **Step 7: GREEN 확인**

Run: `./gradlew test --tests 'com.gongu.server.global.infrastructure.portone.PortOneTimeoutTest' -x jacocoTestCoverageVerification`
Expected: PASS — `elapsed` ≈ 7s (< 12s), `hits == 3`.

- [ ] **Step 8: 전체 스위트 + Kakao 회귀 확인**

Run: `./gradlew test`
Expected: BUILD SUCCESSFUL. 특히 `KakaoApiClient` 관련 테스트(있다면)와 `PortOneClientResilienceTest`(mock RestClient 사용 → 타임아웃 무관) 통과.

> `PortOneProperties` 생성자 시그니처가 바뀌므로, 이를 직접 `new` 하는 테스트가 있으면 컴파일 에러. 구현 시 `grep -rn "new PortOneProperties(" src/test` 확인 후 수정.

- [ ] **Step 9: 커밋**

```bash
git add src/main/java/com/gongu/server/global/infrastructure/portone/PortOneProperties.java \
        src/main/java/com/gongu/server/global/config/RestClientConfig.java \
        src/main/resources/application.yml src/test/resources/application.yml \
        src/test/java/com/gongu/server/global/infrastructure/portone/PortOneTimeoutTest.java
git commit -m "fix: PortOne 전용 HTTP 타임아웃 2s 적용 (#222)"
```

---

## Task 2: `completePayment` Bulkhead + `BulkheadFullException` 매핑

**목표:** 동시 `completePayment` 실행을 20개로 제한한다. 초과분은 `BulkheadFullException` → 503(재시도 가능). 로직·상태 전이 무변경.

**Files:**
- Modify: `src/main/resources/application.yml`, `src/test/resources/application.yml` (`resilience4j.bulkhead.instances.payment-complete`)
- Modify: `src/main/java/com/gongu/server/domain/payment/service/PaymentService.java` (`@Bulkhead` 애노테이션만)
- Modify: `src/main/java/com/gongu/server/global/exception/GlobalExceptionHandler.java` (`BulkheadFullException` 핸들러)
- Create: `src/test/java/com/gongu/server/domain/payment/service/PaymentCompleteBulkheadTest.java`
- Modify/Create: `GlobalExceptionHandlerTest` (매핑 단위 테스트)

**Interfaces:**
- Consumes: `PaymentService#completePayment(String)`, `io.github.resilience4j.bulkhead.BulkheadRegistry`, `io.github.resilience4j.bulkhead.BulkheadFullException`
- Produces: 없음

- [ ] **Step 1: Bulkhead 설정 추가 (운영 + 테스트 yml)**

두 `application.yml` 모두 `resilience4j:` 아래에 추가 (기존 `circuitbreaker:` / `retry:` 형제로):

```yaml
resilience4j:
  bulkhead:
    instances:
      payment-complete:
        # completePayment 동시 실행 상한. 20 < HikariCP maximum-pool-size(25)로
        # 다른 엔드포인트·만료 스케줄러에 커넥션 여유를 남긴다. PG 지연이 확정 경로에
        # 몰려도 최대 20스레드만 락+커넥션을 점유 (#222, ADR-008 Option 0).
        max-concurrent-calls: 20
        # 정상 버스트는 흡수(대기 스레드는 커넥션 미보유), 지속 포화만 거절.
        max-wait-duration: 500ms
  circuitbreaker:
    # ... 기존 내용 그대로 (aspect-order 포함, 불변) ...
```

> `ResilienceConfigParityTest`가 `circuitbreaker`/`retry` instances만 비교하므로 bulkhead는 자동 커버 안 됨. 두 yml에 동일하게 넣는 것으로 충분 (parity 테스트 확장은 이 이슈 범위 밖 — 필요하면 후속).

- [ ] **Step 2: permit 소진 시 `BulkheadFullException` 발생을 검증하는 테스트 (RED)**

`PaymentCompleteBulkheadTest.java` — `@SpringBootTest`로 실제 애스펙트를 띄우고, `BulkheadRegistry`에서 `payment-complete` bulkhead의 permit 20개를 직접 소진한 뒤 `completePayment` 호출이 **메서드 본문 진입 전에** `BulkheadFullException`을 던지는지 확인 (DB 셋업 불필요 — 애스펙트가 먼저 막음).

```java
package com.gongu.server.domain.payment.service;

import io.github.resilience4j.bulkhead.Bulkhead;
import io.github.resilience4j.bulkhead.BulkheadFullException;
import io.github.resilience4j.bulkhead.BulkheadRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest(properties = {
        "portone.api-secret=test-secret",
        "portone.base-url=https://api.portone.test"
})
@DisplayName("completePayment Bulkhead (#222)")
class PaymentCompleteBulkheadTest {

    @Autowired
    private PaymentService paymentService;
    @Autowired
    private BulkheadRegistry bulkheadRegistry;

    private final List<Runnable> releasers = new ArrayList<>();

    @AfterEach
    void releaseAll() {
        releasers.forEach(Runnable::run);
        releasers.clear();
    }

    @Test
    @DisplayName("payment-complete permit이 모두 점유되면 completePayment는 BulkheadFullException을 던진다")
    void completePayment_rejectsWhenBulkheadFull() {
        Bulkhead bulkhead = bulkheadRegistry.bulkhead("payment-complete");
        assertThat(bulkhead.getBulkheadConfig().getMaxConcurrentCalls()).isEqualTo(20);

        for (int i = 0; i < 20; i++) {
            boolean acquired = bulkhead.tryAcquirePermission();
            assertThat(acquired).isTrue();
            releasers.add(bulkhead::releasePermission);
        }

        assertThatThrownBy(() -> paymentService.completePayment("no-such-payment"))
                .isInstanceOf(BulkheadFullException.class);
    }
}
```

> 원리: `max-wait-duration: 500ms`이므로 정확히는 500ms 대기 후 `BulkheadFullException`. 테스트는 그 지연을 감수(허용). `completePayment`가 `PAYMENT_NOT_FOUND`(BusinessException)를 던지기 전에 애스펙트가 막으므로 존재하지 않는 paymentId여도 무방.

- [ ] **Step 3: RED 확인**

Run: `./gradlew test --tests 'com.gongu.server.domain.payment.service.PaymentCompleteBulkheadTest' -x jacocoTestCoverageVerification`
Expected: FAIL — `bulkheadRegistry.bulkhead("payment-complete")`는 설정이 있으니 생성되지만, `completePayment`에 `@Bulkhead`가 없어 애스펙트가 안 걸림 → `BulkheadFullException` 대신 `BusinessException(PAYMENT_NOT_FOUND)`가 던져짐.

- [ ] **Step 4: `completePayment`에 `@Bulkhead` 추가**

`PaymentService.java` — `completePayment` 위. **다른 변경 없음.**

```java
import io.github.resilience4j.bulkhead.annotation.Bulkhead;

// ...

    @Bulkhead(name = "payment-complete")
    @Transactional(noRollbackFor = {BusinessException.class, InfraException.class})
    public VerifyPaymentResponse completePayment(String paymentId) {
```

> `@Bulkhead`는 fallbackMethod 없음 → `BulkheadFullException`이 그대로 전파되어 `GlobalExceptionHandler`가 처리 (Step 6). 애스펙트 순서: Bulkhead(LOWEST−1) > Transactional(LOWEST)에서 Bulkhead가 바깥 → permit 획득 후 트랜잭션 시작. 정상.

- [ ] **Step 5: GREEN 확인 (Bulkhead 동작)**

Run: `./gradlew test --tests 'com.gongu.server.domain.payment.service.PaymentCompleteBulkheadTest' -x jacocoTestCoverageVerification`
Expected: PASS.

- [ ] **Step 6: `GlobalExceptionHandler`에 `BulkheadFullException` → 503 매핑 + 단위 테스트**

먼저 매핑 테스트(RED). `GlobalExceptionHandlerTest`가 있으면 케이스 추가, 없으면 생성:

```java
    @Test
    @DisplayName("BulkheadFullException → 503 PAYMENT_PG_UNAVAILABLE")
    void bulkheadFull_returns503() {
        var bulkhead = io.github.resilience4j.bulkhead.Bulkhead.ofDefaults("t");
        var ex = io.github.resilience4j.bulkhead.BulkheadFullException.createBulkheadFullException(bulkhead);

        ResponseEntity<ErrorResponse> res = handler.handleBulkheadFull(ex);

        assertThat(res.getStatusCode().value()).isEqualTo(503);
        assertThat(res.getBody().code()).isEqualTo(PaymentErrorCode.PAYMENT_PG_UNAVAILABLE.getCode());
    }
```

그다음 핸들러 추가 (`CallNotPermittedException` 핸들러와 동일 패턴):

```java
    @ExceptionHandler(io.github.resilience4j.bulkhead.BulkheadFullException.class)
    public ResponseEntity<ErrorResponse> handleBulkheadFull(io.github.resilience4j.bulkhead.BulkheadFullException e) {
        log.warn("Payment bulkhead full: {}", e.getMessage());
        ErrorCode errorCode = PaymentErrorCode.PAYMENT_PG_UNAVAILABLE;
        return ResponseEntity
                .status(errorCode.getHttpStatus())
                .body(ErrorResponse.of(errorCode));
    }
```

> 구현 시 확인: `ErrorResponse`의 접근자명(`code()` vs `getCode()`), `handler` 필드가 테스트에 어떻게 준비되는지 (기존 `GlobalExceptionHandlerTest` 패턴 따름). 없으면 `new GlobalExceptionHandler()`.

- [ ] **Step 7: 웹훅 경로 회귀 확인**

`PaymentController.receiveWebhook`은 `catch (BusinessException e)`만 한다. `BulkheadFullException`은 `BusinessException`이 아니므로 잡히지 않고 전파 → advice가 503 → PortOne 재시도. **기대 동작.**
기존 `PaymentControllerTest`(webhook 케이스)가 깨지지 않는지 확인. `completePayment`를 `@MockitoBean`으로 대체하는 테스트라면 `@Bulkhead` 애스펙트 미적용 → 영향 없음.

- [ ] **Step 8: 전체 스위트**

Run: `./gradlew test`
Expected: BUILD SUCCESSFUL. `PaymentServiceTest`(순수 Mockito, 애스펙트 미적용) 불변 통과.

- [ ] **Step 9: 커밋**

```bash
git add src/main/resources/application.yml src/test/resources/application.yml \
        src/main/java/com/gongu/server/domain/payment/service/PaymentService.java \
        src/main/java/com/gongu/server/global/exception/GlobalExceptionHandler.java \
        src/test/java/com/gongu/server/domain/payment/service/PaymentCompleteBulkheadTest.java \
        src/test/java/com/gongu/server/global/exception/GlobalExceptionHandlerTest.java
git commit -m "fix: completePayment에 Bulkhead(동시 20) 적용 및 초과 요청 503 매핑 (#222)"
```

---

## Self-Review

**Spec coverage (이슈 #222 완료 기준):**

| 완료 기준 | 대응 |
|---|---|
| `getPayment`/`cancelPayment` 최악 소요가 타임아웃×재시도로 바운드 | Task 1 (`read-timeout: 2s`), `PortOneTimeoutTest` 총 소요 검증 |
| `KakaoApiClient` 타임아웃 불변 | Task 1 Step 4(전용 빌더) + Step 8 회귀 |
| `completePayment` 동시 실행 상한 + `BulkheadFullException` 503 | Task 2 |
| 웹훅 핸들러가 신규 예외를 비2xx로 전파 | Task 2 Step 7 (BusinessException 아님 → 자동 전파 → 503) |
| 기존 결제 테스트 전부 통과 | 각 Task 마지막 `./gradlew test` |
| ~~`completePayment` 락 대기 상한~~ | **이 이슈에서 제외** (D7) — ADR-008 D1에서 |

**Placeholder scan:** 코드 블록 전부 실제 내용. "구현 시 확인" 항목은 (a) Spring Boot 3.5 import 경로, (b) 기존 `src/test/resources/application.yml`의 `portone:` 블록 유무, (c) `ErrorResponse` 접근자명, (d) `GlobalExceptionHandlerTest` 존재 여부 — 모두 파일 열면 즉시 확정되는 사실 확인이며 설계 판단 아님.

**Type consistency:**
- `PortOneProperties` 4-arg record — Task 1에서 정의, `RestClientConfig`가 `props.connectTimeout()`/`props.readTimeout()` 사용
- `@Bulkhead(name = "payment-complete")` ↔ yml `resilience4j.bulkhead.instances.payment-complete` ↔ 테스트 `bulkheadRegistry.bulkhead("payment-complete")` — 이름 일치
- `max-concurrent-calls: 20` ↔ 테스트 `getMaxConcurrentCalls()).isEqualTo(20)`
- `BulkheadFullException` 핸들러는 `PaymentErrorCode.PAYMENT_PG_UNAVAILABLE` / 503 반환 — 기존 `CallNotPermittedException` 핸들러와 동일 패턴

**전제 확인 (구현자가 첫 스텝에서):**
- `org.springframework.boot.http.client.ClientHttpRequestFactorySettings` / `ClientHttpRequestFactoryBuilder`가 Spring Boot 3.5.14에 존재하고 시그니처가 위와 같은가 (deprecated 구 패키지와 혼동 금지)
- resilience4j-spring-boot3 2.2.0가 `@Bulkhead` 애스펙트를 AOP 자동 구성하는가 (starter에 포함 — 확인용으로 Task 2 Step 3 GREEN이 증명)

---

## 실행 후 (워크플로 8~12단계)

1. `git push -u origin fix/#222-payment-path-immediate-defense`
2. `gh pr create` — 제목 `[FIX] 결제 확정 경로 즉시 방어 — PG 타임아웃 / 벌크헤드 (#222)`, 본문에:
   - 2파트 요약 + 파라미터 값과 근거, Part 3(락 대기) 제외 사유
   - **배포 주의**: PortOne read-timeout 2s(정상 응답 대비 여유 확인 필요), bulkhead 20(부하 특성 관찰)
   - 실측 후 재조정 항목: 타임아웃 값, bulkhead 크기, CB 임계값(#214)
   - 후속: 락 대기 상한은 ADR-008 D1(`PaymentReconciler`)에서
3. 코드 리뷰 — Claude 서브에이전트 (`subagent-driven-development` 2단계 + 최종 whole-branch)
4. `.claude/review-process.md` 하드 게이트

## ADR-008 이행 순서에서의 위치

§7 이행 순서 **2번**. 완료 후: **3번 #209**(payment_events 이력 — 관측성) → **4번 D1 `PaymentReconciler`** → **5번 §5 실험**.
