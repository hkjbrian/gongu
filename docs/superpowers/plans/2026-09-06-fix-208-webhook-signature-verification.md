# #208 — 웹훅 서명 검증 부재 수정 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.
>
> **구현 위임:** Codex CLI 구독 종료(2026-09-02)로 실제 코드 구현은 Claude 서브에이전트(superpowers:subagent-driven-development)에 위임한다. Claude 본세션은 계획·검증·GitHub 관리만 수행한다.

**Goal:** `POST /payments/webhook`에 PortOne V2 서명 검증(Standard Webhooks 스펙)을 추가해, 서명이 없거나 불일치하거나 타임스탬프가 오래된 요청을 재시도를 유발하지 않는 400으로 거부한다.

**Architecture:** `permitAll`은 유지하되(서명이 인증 수단이 됨), 컨트롤러가 원문 바디를 `@RequestBody String`으로 받아 전용 검증기 `PortOneWebhookVerifier`로 HMAC-SHA256 서명·타임스탬프를 검증한 뒤 `ObjectMapper`로 직접 파싱한다. 검증기는 프로젝트가 PortOne SDK 대신 `PortOneClient`를 직접 구현한 관례에 맞춰 Standard Webhooks 스펙을 손수 구현한다(신규 의존성 0). 검증 실패는 `BusinessException(PaymentErrorCode.WEBHOOK_VERIFICATION_FAILED)` → 글로벌 예외 핸들러가 400 반환.

**Tech Stack:** Spring Boot 3.5, Java 25, `javax.crypto.Mac`(HMAC-SHA256), JUnit 5 + Mockito + AssertJ, Spring MockMvc (`@WebMvcTest`)

## Global Constraints

- 베이스 패키지: `com.gongu.server`
- 소스 루트: `src/main/java/com/gongu/server/` (작업 디렉터리는 워크트리 `server/.claude/worktrees/fix-208/`)
- 빌드/테스트: `./gradlew` (워크트리 루트 기준). 전체 검증은 `./gradlew test`
- 커밋 메시지 형식: `type: 작업 내용 (#208)` — **`Co-Authored-By` 절 절대 포함 금지** (`.claude/github-rules.md`)
- 브랜치: `fix/#208-webhook-signature-verification` (이미 생성됨, 워크트리에서 작업)
- Surgical Changes: 요청 범위 밖 코드 수정 금지
- 커밋 단위 분리: Config / 검증기 / 컨트롤러 연결 / 문서를 각각 별도 커밋 (`feedback_commit_granularity`)
- 서명 대상·알고리즘은 Standard Webhooks 스펙(<https://www.standardwebhooks.com/>) 준수:
  - 서명 대상 문자열: `{webhook-id}.{webhook-timestamp}.{원문 바디}`
  - 키: `secret`에서 `whsec_` 접두사(있으면) 제거 후 **Base64 디코드한 바이트**
  - HMAC-SHA256 결과를 Base64 인코딩
  - `webhook-signature` 헤더: 공백으로 구분된 `{버전},{base64 서명}` 토큰 목록 — **하나라도** 일치하면 통과
  - 타임스탬프 허용 오차: **±300초**, 초 단위 Unix epoch
- 테스트 웹훅 시크릿(고정): `whsec_MfKQ9r8GKYqrTwjUPD8ILPZIo2LaLaSw`

---

## 배경 — 현재 코드의 문제

`PaymentController.receiveWebhook` (`src/main/java/com/gongu/server/domain/payment/controller/PaymentController.java:64`):

```java
@PostMapping("/webhook")
public ResponseEntity<Void> receiveWebhook(@Valid @RequestBody PortOneWebhookPayload payload) {
    if ("Transaction.Paid".equals(payload.type())) {
        try {
            paymentService.completePayment(payload.data().paymentId());
        } catch (BusinessException e) {
            if (WEBHOOK_TERMINAL_CODES.contains(e.getErrorCode())) {
                return ResponseEntity.ok().build();
            }
            throw e;
        }
    }
    return ResponseEntity.ok().build();
}
```

- `SecurityConfig`에서 `.requestMatchers(HttpMethod.POST, "/payments/webhook").permitAll()` — 인증 없음.
- 서명 검증 없음. 파싱된 객체만 받아 원문 바이트에 접근 불가.
- 피해: 위조 웹훅으로 결제 성립·금액 조작은 `completePayment`의 PG 재검증이 차단하나, **정상 결제 방해**(결제창 입력 중 위조 웹훅 → `payment.fail()` → 복구 불가)와 **PG 조회 API 남용**(Circuit Breaker open 유발)이 가능. `#207`과 결합 시 피해 확대.

## 결정 사항 (사용자 합의 완료)

1. **직접 구현.** Standard Webhooks HMAC-SHA256 검증을 `global/infrastructure/portone/PortOneWebhookVerifier`에 손수 구현. `io.portone:server-sdk` 도입 안 함(무거운 전이 의존성 대비 이득 없음, `PortOneClient` 직접 구현 관례와 일치). ADR로 근거 남김.
2. **원문 바디 = `@RequestBody String`.** 필터/래퍼 인프라 없이 컨트롤러에서 검증 후 `ObjectMapper`로 직접 파싱. `/payments/webhook`은 이 컨트롤러가 유일 소비자.
3. **`permitAll` 유지.** 서명이 인증을 대체. 검증은 컨트롤러 레벨(필터 아님) — 테스트 용이.
4. **검증 실패 = 400, 재시도 미유발.** 헤더 누락·서명 불일치·타임스탬프 초과·페이로드 파손을 모두 단일 코드 `WEBHOOK_VERIFICATION_FAILED`(400)로 거부하고 WARN 로그. `PAYMENT_PG_UNAVAILABLE`(503, 재시도 유효)은 서명 검증 통과 후 `completePayment` 경로에서만 발생하므로 영향 없음.
5. **시크릿 미설정 시 fail-closed.** `portone.webhook-secret`이 비어 있으면 모든 웹훅을 거부(ERROR 로그). perf 프로파일은 웹훅 서명 경로를 검증하지 않으므로 무방.
6. **범위 밖:** `webhook-id` 기반 중복 수신 저장/차단(완료 처리는 `completePayment`가 이미 멱등), 공식 SDK 마이그레이션, `#207` 재시도 로직 변경, `load-test/mock-pg`의 서명 발송(현재 mock 웹훅 바디에 `type` 필드가 없어 이미 no-op이며 어떤 k6 assertion도 깨지지 않음 — 별도 이슈로 추적).

---

## File Structure

| 파일 | 책임 | 변경 |
|------|------|------|
| `src/main/java/com/gongu/server/global/infrastructure/portone/PortOneProperties.java` | PortOne 설정 바인딩 | 수정 — `webhookSecret` 필드 추가 |
| `src/main/resources/application.yml` | 운영 기본 설정 | 수정 — `portone.webhook-secret` |
| `src/main/resources/application-local.yml` | 로컬 설정 | 수정 — `portone.webhook-secret` |
| `src/test/resources/application.yml` | 테스트 컨텍스트 설정 | 수정 — 고정 테스트 시크릿 |
| `docker-compose.yml` | perf 컨테이너 env | 수정 — `PORTONE_WEBHOOK_SECRET` 전달 |
| `src/main/java/com/gongu/server/global/exception/errorcode/PaymentErrorCode.java` | 결제 에러 코드 | 수정 — `WEBHOOK_VERIFICATION_FAILED` 추가 |
| `src/main/java/com/gongu/server/global/infrastructure/portone/PortOneWebhookVerifier.java` | Standard Webhooks 서명·타임스탬프 검증 | **신규** |
| `src/test/java/com/gongu/server/global/infrastructure/portone/PortOneWebhookVerifierTest.java` | 검증기 단위 테스트 | **신규** |
| `src/main/java/com/gongu/server/domain/payment/controller/PaymentController.java` | 웹훅 수신 | 수정 — 원문 바디 + 검증기 연결 |
| `src/main/java/com/gongu/server/domain/payment/dto/request/PortOneWebhookPayload.java` | 웹훅 페이로드 DTO | 수정 — 사용되지 않는 검증 애너테이션 제거 |
| `src/test/java/com/gongu/server/domain/payment/controller/WebhookSignatures.java` | 테스트용 서명 헤더 생성 헬퍼 | **신규** |
| `src/test/java/com/gongu/server/domain/payment/controller/PaymentControllerTest.java` | 컨트롤러 슬라이스 테스트 | 수정 — 서명 헤더 부착 |
| `src/test/java/com/gongu/server/domain/payment/controller/PaymentSecurityTest.java` | 시큐리티 통합 테스트 | 수정 — 유효/무효 서명 케이스 |
| `docs/adr/웹훅_서명_검증_전략.md` | 결정 기록 | **신규** |

---

## Task 1: 웹훅 시크릿 설정 값 추가

**Files:**
- Modify: `src/main/java/com/gongu/server/global/infrastructure/portone/PortOneProperties.java`
- Modify: `src/main/resources/application.yml:41-43`
- Modify: `src/main/resources/application-local.yml:35-37`
- Modify: `src/test/resources/application.yml` (파일 끝에 `portone` 블록 추가)
- Modify: `docker-compose.yml:129`

**Interfaces:**
- Produces: `PortOneProperties(String apiSecret, String baseUrl, String webhookSecret)` — record 컴포넌트 `webhookSecret()` 접근자.

- [ ] **Step 1: `PortOneProperties`에 `webhookSecret` 추가**

```java
package com.gongu.server.global.infrastructure.portone;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "portone")
public record PortOneProperties(String apiSecret, String baseUrl, String webhookSecret) {
}
```

- [ ] **Step 2: `application.yml` `portone` 블록 수정**

`src/main/resources/application.yml` 의 `portone:` 블록을 다음으로 교체 (`:41`):

```yaml
portone:
  api-secret: ${PORTONE_API_SECRET}
  base-url: https://api.portone.io
  webhook-secret: ${PORTONE_WEBHOOK_SECRET:}
```

- [ ] **Step 3: `application-local.yml` `portone` 블록 수정**

`src/main/resources/application-local.yml` 의 `portone:` 블록(`:35`)을 동일하게 교체:

```yaml
portone:
  api-secret: ${PORTONE_API_SECRET}
  base-url: https://api.portone.io
  webhook-secret: ${PORTONE_WEBHOOK_SECRET:}
```

- [ ] **Step 4: `src/test/resources/application.yml` 에 고정 테스트 시크릿 추가**

파일 맨 끝(`order:` 블록 뒤)에 추가:

```yaml
portone:
  webhook-secret: whsec_MfKQ9r8GKYqrTwjUPD8ILPZIo2LaLaSw
```

- [ ] **Step 5: `docker-compose.yml` server 서비스 env에 시크릿 전달**

`docker-compose.yml:129` `PORTONE_API_SECRET: ${PORTONE_API_SECRET}` 아래 줄 추가:

```yaml
      PORTONE_API_SECRET: ${PORTONE_API_SECRET}
      PORTONE_WEBHOOK_SECRET: ${PORTONE_WEBHOOK_SECRET:-}
```

- [ ] **Step 6: 컴파일 확인**

Run: `./gradlew compileJava compileTestJava`
Expected: BUILD SUCCESSFUL (record 컴포넌트 추가로 인한 컴파일 에러 없음 — `PortOneProperties`는 수동 생성 지점이 없다)

- [ ] **Step 7: 커밋**

```bash
git add src/main/java/com/gongu/server/global/infrastructure/portone/PortOneProperties.java \
        src/main/resources/application.yml src/main/resources/application-local.yml \
        src/test/resources/application.yml docker-compose.yml
git commit -m "chore: 웹훅 시크릿 설정 값 추가 (#208)"
```

---

## Task 2: PortOneWebhookVerifier — 서명·타임스탬프 검증기

**Files:**
- Modify: `src/main/java/com/gongu/server/global/exception/errorcode/PaymentErrorCode.java`
- Create: `src/main/java/com/gongu/server/global/infrastructure/portone/PortOneWebhookVerifier.java`
- Test: `src/test/java/com/gongu/server/global/infrastructure/portone/PortOneWebhookVerifierTest.java`

**Interfaces:**
- Consumes: `PortOneProperties.webhookSecret()` (Task 1)
- Produces:
  - `PaymentErrorCode.WEBHOOK_VERIFICATION_FAILED` — code `"PAYMENT_010"`, httpStatus `400`
  - `PortOneWebhookVerifier` — Spring `@Component`, 생성자 `PortOneWebhookVerifier(PortOneProperties properties)`
  - `void verify(String rawBody, String webhookId, String webhookTimestamp, String webhookSignature)` — 검증 실패 시 `BusinessException(PaymentErrorCode.WEBHOOK_VERIFICATION_FAILED)` throw, 성공 시 void 반환

- [ ] **Step 1: `PaymentErrorCode`에 에러 코드 추가**

`src/main/java/com/gongu/server/global/exception/errorcode/PaymentErrorCode.java` 의 enum 상수 목록 마지막(`PAYMENT_ACTIVE_EXISTS` 뒤)에 추가:

```java
    PAYMENT_ACTIVE_EXISTS("PAYMENT_009", "이미 활성 결제가 존재합니다", 409),
    WEBHOOK_VERIFICATION_FAILED("PAYMENT_010", "웹훅 검증에 실패했습니다", 400);
```

- [ ] **Step 2: 실패하는 테스트 작성**

Create `src/test/java/com/gongu/server/global/infrastructure/portone/PortOneWebhookVerifierTest.java`:

```java
package com.gongu.server.global.infrastructure.portone;

import com.gongu.server.global.exception.BusinessException;
import com.gongu.server.global.exception.errorcode.PaymentErrorCode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PortOneWebhookVerifierTest {

    private static final String SECRET = "whsec_MfKQ9r8GKYqrTwjUPD8ILPZIo2LaLaSw";
    private static final String BODY = "{\"type\":\"Transaction.Paid\",\"data\":{\"paymentId\":\"pay-uuid-001\"}}";

    private final PortOneWebhookVerifier verifier =
            new PortOneWebhookVerifier(new PortOneProperties(null, null, SECRET));

    private static String sign(String id, String timestamp, String body) throws Exception {
        byte[] key = Base64.getDecoder().decode(SECRET.substring("whsec_".length()));
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(key, "HmacSHA256"));
        byte[] sig = mac.doFinal((id + "." + timestamp + "." + body).getBytes(StandardCharsets.UTF_8));
        return "v1," + Base64.getEncoder().encodeToString(sig);
    }

    private static String now() {
        return Long.toString(System.currentTimeMillis() / 1000L);
    }

    @Test
    @DisplayName("유효한 서명 + 최신 타임스탬프 → 통과")
    void validSignature_passes() throws Exception {
        String ts = now();
        assertThatCode(() -> verifier.verify(BODY, "msg_1", ts, sign("msg_1", ts, BODY)))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("여러 서명 토큰 중 하나만 유효해도 통과")
    void oneValidTokenAmongMany_passes() throws Exception {
        String ts = now();
        String header = "v1,AAAA " + sign("msg_1", ts, BODY);
        assertThatCode(() -> verifier.verify(BODY, "msg_1", ts, header))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("바디 변조 → WEBHOOK_VERIFICATION_FAILED")
    void tamperedBody_rejected() throws Exception {
        String ts = now();
        String sig = sign("msg_1", ts, BODY);
        assertThatThrownBy(() -> verifier.verify(BODY + " ", "msg_1", ts, sig))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(PaymentErrorCode.WEBHOOK_VERIFICATION_FAILED);
    }

    @Test
    @DisplayName("다른 시크릿으로 만든 서명 → 거부")
    void wrongSecretSignature_rejected() {
        String ts = now();
        String bogus = "v1," + Base64.getEncoder().encodeToString("not-a-real-sig".getBytes(StandardCharsets.UTF_8));
        assertThatThrownBy(() -> verifier.verify(BODY, "msg_1", ts, bogus))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    @DisplayName("서명 헤더 누락(null) → 거부")
    void missingSignatureHeader_rejected() {
        assertThatThrownBy(() -> verifier.verify(BODY, "msg_1", now(), null))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    @DisplayName("webhook-id 누락(빈 문자열) → 거부")
    void missingId_rejected() throws Exception {
        String ts = now();
        assertThatThrownBy(() -> verifier.verify(BODY, "  ", ts, sign("", ts, BODY)))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    @DisplayName("오래된 타임스탬프(-400초) → 거부 (재전송 방지)")
    void staleTimestamp_rejected() throws Exception {
        String ts = Long.toString(System.currentTimeMillis() / 1000L - 400);
        assertThatThrownBy(() -> verifier.verify(BODY, "msg_1", ts, sign("msg_1", ts, BODY)))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    @DisplayName("미래 타임스탬프(+400초) → 거부")
    void futureTimestamp_rejected() throws Exception {
        String ts = Long.toString(System.currentTimeMillis() / 1000L + 400);
        assertThatThrownBy(() -> verifier.verify(BODY, "msg_1", ts, sign("msg_1", ts, BODY)))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    @DisplayName("숫자가 아닌 타임스탬프 → 거부")
    void nonNumericTimestamp_rejected() throws Exception {
        assertThatThrownBy(() -> verifier.verify(BODY, "msg_1", "not-a-number", sign("msg_1", "not-a-number", BODY)))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    @DisplayName("시크릿 미설정 → 모든 웹훅 거부 (fail-closed)")
    void secretNotConfigured_rejectsAll() throws Exception {
        PortOneWebhookVerifier noSecret =
                new PortOneWebhookVerifier(new PortOneProperties(null, null, "  "));
        String ts = now();
        assertThatThrownBy(() -> noSecret.verify(BODY, "msg_1", ts, sign("msg_1", ts, BODY)))
                .isInstanceOf(BusinessException.class);
    }
}
```

- [ ] **Step 3: 테스트 실패 확인**

Run: `./gradlew test --tests '*PortOneWebhookVerifierTest'`
Expected: FAIL — `PortOneWebhookVerifier` 클래스 없음(컴파일 에러) / `PortOneProperties` 생성자 인자 수 불일치

- [ ] **Step 4: `PortOneWebhookVerifier` 구현**

Create `src/main/java/com/gongu/server/global/infrastructure/portone/PortOneWebhookVerifier.java`:

```java
package com.gongu.server.global.infrastructure.portone;

import com.gongu.server.global.exception.BusinessException;
import com.gongu.server.global.exception.errorcode.PaymentErrorCode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.util.Base64;

/**
 * PortOne V2 웹훅 서명 검증 — Standard Webhooks 스펙(https://www.standardwebhooks.com/) 준수.
 *
 * <ul>
 *   <li>서명 대상: {@code "{webhook-id}.{webhook-timestamp}.{원문 바디}"}</li>
 *   <li>키: 시크릿에서 {@code whsec_} 접두사(있으면) 제거 후 Base64 디코드한 바이트</li>
 *   <li>알고리즘: HMAC-SHA256 → Base64 인코딩</li>
 *   <li>{@code webhook-signature} 헤더: 공백 구분 {@code {버전},{base64 서명}} 토큰 목록. 하나라도 일치하면 통과</li>
 *   <li>타임스탬프 허용 오차: ±300초 (재전송 공격 차단)</li>
 * </ul>
 */
@Slf4j
@Component
public class PortOneWebhookVerifier {

    private static final long TIMESTAMP_TOLERANCE_SECONDS = 300L;
    private static final String SECRET_PREFIX = "whsec_";
    private static final String HMAC_ALGORITHM = "HmacSHA256";

    /** 시크릿 미설정 시 null — 모든 웹훅을 fail-closed로 거부한다. */
    private final byte[] secretKey;

    public PortOneWebhookVerifier(PortOneProperties properties) {
        String secret = properties.webhookSecret();
        if (!StringUtils.hasText(secret)) {
            this.secretKey = null;
            return;
        }
        String base64 = secret.startsWith(SECRET_PREFIX)
                ? secret.substring(SECRET_PREFIX.length())
                : secret;
        this.secretKey = Base64.getDecoder().decode(base64);
    }

    /**
     * @throws BusinessException {@link PaymentErrorCode#WEBHOOK_VERIFICATION_FAILED}
     *         — 시크릿 미설정 · 헤더 누락 · 타임스탬프 형식 오류/허용 오차 초과 · 서명 불일치
     */
    public void verify(String rawBody, String webhookId, String webhookTimestamp, String webhookSignature) {
        if (secretKey == null) {
            log.error("웹훅 시크릿 미설정 — 모든 웹훅 거부");
            throw reject();
        }
        if (rawBody == null
                || !StringUtils.hasText(webhookId)
                || !StringUtils.hasText(webhookTimestamp)
                || !StringUtils.hasText(webhookSignature)) {
            log.warn("웹훅 필수 헤더/바디 누락");
            throw reject();
        }

        verifyTimestamp(webhookTimestamp);

        byte[] expected = hmacSha256(webhookId + "." + webhookTimestamp + "." + rawBody);

        for (String token : webhookSignature.split(" ")) {
            int comma = token.indexOf(',');
            if (comma < 0) {
                continue;
            }
            byte[] provided;
            try {
                provided = Base64.getDecoder().decode(token.substring(comma + 1));
            } catch (IllegalArgumentException e) {
                continue;
            }
            if (MessageDigest.isEqual(expected, provided)) {
                return;
            }
        }
        log.warn("웹훅 서명 불일치: webhookId={}", webhookId);
        throw reject();
    }

    private void verifyTimestamp(String webhookTimestamp) {
        final long ts;
        try {
            ts = Long.parseLong(webhookTimestamp.trim());
        } catch (NumberFormatException e) {
            log.warn("웹훅 타임스탬프 형식 오류: {}", webhookTimestamp);
            throw reject();
        }
        long now = System.currentTimeMillis() / 1000L;
        if (Math.abs(now - ts) > TIMESTAMP_TOLERANCE_SECONDS) {
            log.warn("웹훅 타임스탬프 허용 오차 초과: ts={}, now={}", ts, now);
            throw reject();
        }
    }

    private byte[] hmacSha256(String data) {
        try {
            Mac mac = Mac.getInstance(HMAC_ALGORITHM);
            mac.init(new SecretKeySpec(secretKey, HMAC_ALGORITHM));
            return mac.doFinal(data.getBytes(StandardCharsets.UTF_8));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("HMAC-SHA256 계산 실패", e);
        }
    }

    private static BusinessException reject() {
        return new BusinessException(PaymentErrorCode.WEBHOOK_VERIFICATION_FAILED);
    }
}
```

- [ ] **Step 5: 테스트 통과 확인**

Run: `./gradlew test --tests '*PortOneWebhookVerifierTest'`
Expected: PASS (10개 테스트 전부)

- [ ] **Step 6: 커밋**

```bash
git add src/main/java/com/gongu/server/global/exception/errorcode/PaymentErrorCode.java \
        src/main/java/com/gongu/server/global/infrastructure/portone/PortOneWebhookVerifier.java \
        src/test/java/com/gongu/server/global/infrastructure/portone/PortOneWebhookVerifierTest.java
git commit -m "feat: PortOne 웹훅 서명 검증기 추가 (#208)"
```

---

## Task 3: 웹훅 서명 검증을 결제 컨트롤러에 연결

**Files:**
- Modify: `src/main/java/com/gongu/server/domain/payment/controller/PaymentController.java`
- Modify: `src/main/java/com/gongu/server/domain/payment/dto/request/PortOneWebhookPayload.java`
- Create: `src/test/java/com/gongu/server/domain/payment/controller/WebhookSignatures.java`
- Modify: `src/test/java/com/gongu/server/domain/payment/controller/PaymentControllerTest.java`
- Modify: `src/test/java/com/gongu/server/domain/payment/controller/PaymentSecurityTest.java`

**Interfaces:**
- Consumes: `PortOneWebhookVerifier.verify(...)` (Task 2), `PaymentErrorCode.WEBHOOK_VERIFICATION_FAILED` (Task 2)
- Produces:
  - `PaymentController` 생성자에 `PortOneWebhookVerifier`, `ObjectMapper` 의존성 추가
  - `receiveWebhook(String rawBody, String webhookId, String webhookTimestamp, String webhookSignature)` — 헤더는 `@RequestHeader(required = false)`
  - `WebhookSignatures.signedHeaders(String body)` → `org.springframework.test.web.servlet.request.RequestPostProcessor` (헤더 `webhook-id` / `webhook-timestamp` / `webhook-signature` 부착)
  - `WebhookSignatures.TEST_SECRET` = `"whsec_MfKQ9r8GKYqrTwjUPD8ILPZIo2LaLaSw"`

- [ ] **Step 1: 테스트 헬퍼 작성**

Create `src/test/java/com/gongu/server/domain/payment/controller/WebhookSignatures.java`:

```java
package com.gongu.server.domain.payment.controller;

import org.springframework.test.web.servlet.request.RequestPostProcessor;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.util.Base64;

/**
 * 테스트에서 Standard Webhooks 서명 헤더를 생성한다.
 * 운영 시크릿과 무관한 고정 테스트 시크릿을 사용한다 (src/test/resources/application.yml 과 동일).
 */
final class WebhookSignatures {

    static final String TEST_SECRET = "whsec_MfKQ9r8GKYqrTwjUPD8ILPZIo2LaLaSw";

    private WebhookSignatures() {
    }

    /** 유효한 서명 + 현재 타임스탬프 헤더를 부착하는 RequestPostProcessor. */
    static RequestPostProcessor signedHeaders(String body) {
        String id = "msg_test";
        String timestamp = Long.toString(System.currentTimeMillis() / 1000L);
        String signature = "v1," + sign(id + "." + timestamp + "." + body);
        return request -> {
            request.addHeader("webhook-id", id);
            request.addHeader("webhook-timestamp", timestamp);
            request.addHeader("webhook-signature", signature);
            return request;
        };
    }

    private static String sign(String signedContent) {
        try {
            byte[] key = Base64.getDecoder().decode(TEST_SECRET.substring("whsec_".length()));
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key, "HmacSHA256"));
            return Base64.getEncoder().encodeToString(
                    mac.doFinal(signedContent.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
```

- [ ] **Step 2: `PaymentControllerTest` 웹훅 테스트에 서명 헤더 부착 (실패 확인용)**

`PaymentControllerTest`는 `@WebMvcTest(PaymentController.class)` + `addFilters = false`. 다음을 수정한다:

1. `@TestConfiguration static class SecurityConfig {}` 옆에 검증기 실 빈을 등록:

```java
    @TestConfiguration
    @EnableMethodSecurity
    static class SecurityConfig {
        @org.springframework.context.annotation.Bean
        com.gongu.server.global.infrastructure.portone.PortOneWebhookVerifier portOneWebhookVerifier() {
            return new com.gongu.server.global.infrastructure.portone.PortOneWebhookVerifier(
                    new com.gongu.server.global.infrastructure.portone.PortOneProperties(
                            null, null, WebhookSignatures.TEST_SECRET));
        }
    }
```

2. 기존 웹훅 테스트 6종(`receiveWebhook_성공_200`, `receiveWebhook_비결제타입_200_completePayment_미호출`, `receiveWebhook_PG조회실패_503`, `receiveWebhook_PG장애_InfraException_503`, `receiveWebhook_주문만료환불완료_200`, `receiveWebhook_PG미결제확정_200`, `receiveWebhook_이미터미널_200`, `receiveWebhook_금액불일치_200`)의 `mockMvc.perform(post("/payments/webhook") ...)` 호출에 `.with(WebhookSignatures.signedHeaders(webhookBody))` 를 추가한다. 예:

```java
        mockMvc.perform(post("/payments/webhook")
                        .with(WebhookSignatures.signedHeaders(webhookBody))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(webhookBody))
                .andExpect(status().isOk());
```

3. 새 테스트 2종 추가:

```java
    @Test
    @DisplayName("POST /payments/webhook 서명 헤더 없음 → 400 (재시도 미유발)")
    void receiveWebhook_서명없음_400() throws Exception {
        String webhookBody = "{\"type\":\"Transaction.Paid\",\"data\":{\"paymentId\":\"pay-uuid-001\"}}";

        mockMvc.perform(post("/payments/webhook")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(webhookBody))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("PAYMENT_010"));

        verify(paymentService, never()).completePayment(anyString());
    }

    @Test
    @DisplayName("POST /payments/webhook 서명 불일치(바디 변조) → 400")
    void receiveWebhook_서명불일치_400() throws Exception {
        String signedBody = "{\"type\":\"Transaction.Paid\",\"data\":{\"paymentId\":\"pay-uuid-001\"}}";
        String tamperedBody = "{\"type\":\"Transaction.Paid\",\"data\":{\"paymentId\":\"pay-uuid-999\"}}";

        mockMvc.perform(post("/payments/webhook")
                        .with(WebhookSignatures.signedHeaders(signedBody))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(tamperedBody))
                .andExpect(status().isBadRequest());

        verify(paymentService, never()).completePayment(anyString());
    }
```

- [ ] **Step 3: 테스트 실패 확인**

Run: `./gradlew test --tests '*PaymentControllerTest'`
Expected: FAIL — 컨트롤러가 아직 `String` 바디/헤더를 받지 않고 `@Valid PortOneWebhookPayload`를 받으므로 서명 헤더 무시, 새 400 테스트가 실패(현재는 415/400 아님 또는 200)

- [ ] **Step 4: `PortOneWebhookPayload`에서 미사용 검증 애너테이션 제거**

원문 바디를 수동 파싱하므로 bean validation은 더 이상 적용되지 않는다. 오해를 줄이기 위해 `jakarta.validation` 애너테이션과 import를 제거한다:

```java
package com.gongu.server.domain.payment.dto.request;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * PortOne V2 웹훅 페이로드.
 * 참고: https://developers.portone.io/opi/ko/integration/webhook/readme-v2
 * 서명 검증 후 {@code PaymentController}에서 ObjectMapper로 직접 파싱한다.
 */
public record PortOneWebhookPayload(
        @JsonProperty("type") String type,
        @JsonProperty("data") WebhookData data
) {
    public record WebhookData(
            @JsonProperty("paymentId") String paymentId
    ) {}
}
```

- [ ] **Step 5: `PaymentController.receiveWebhook` 구현**

`PaymentController`:

1. 클래스에 `@Slf4j` 추가, 생성자 주입 필드 2개 추가:

```java
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.gongu.server.domain.payment.dto.request.PortOneWebhookPayload;
import com.gongu.server.global.infrastructure.portone.PortOneWebhookVerifier;
import lombok.extern.slf4j.Slf4j;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.RequestHeader;
// ...

@Slf4j
@RestController
@RequestMapping("/payments")
@RequiredArgsConstructor
public class PaymentController {

    private final PaymentService paymentService;
    private final PortOneWebhookVerifier portOneWebhookVerifier;
    private final ObjectMapper objectMapper;
```

2. `receiveWebhook` 교체:

```java
    @PostMapping("/webhook")
    public ResponseEntity<Void> receiveWebhook(
            @RequestBody String rawBody,
            @RequestHeader(value = "webhook-id", required = false) String webhookId,
            @RequestHeader(value = "webhook-timestamp", required = false) String webhookTimestamp,
            @RequestHeader(value = "webhook-signature", required = false) String webhookSignature) {

        portOneWebhookVerifier.verify(rawBody, webhookId, webhookTimestamp, webhookSignature);

        PortOneWebhookPayload payload = parseWebhookPayload(rawBody);

        if ("Transaction.Paid".equals(payload.type())) {
            String paymentId = payload.data() == null ? null : payload.data().paymentId();
            if (!StringUtils.hasText(paymentId)) {
                log.warn("웹훅 페이로드에 paymentId 없음");
                throw new BusinessException(PaymentErrorCode.WEBHOOK_VERIFICATION_FAILED);
            }
            try {
                paymentService.completePayment(paymentId);
            } catch (BusinessException e) {
                if (WEBHOOK_TERMINAL_CODES.contains(e.getErrorCode())) {
                    // 재처리해도 결과가 동일한 확정 상태 — PortOne 재시도를 멈추기 위해 200 반환
                    return ResponseEntity.ok().build();
                }
                // 판정 불가(PAYMENT_PG_UNAVAILABLE) 등 — 재시도가 의미 있으므로 비2xx 전파
                throw e;
            }
        }
        return ResponseEntity.ok().build();
    }

    private PortOneWebhookPayload parseWebhookPayload(String rawBody) {
        try {
            return objectMapper.readValue(rawBody, PortOneWebhookPayload.class);
        } catch (JsonProcessingException e) {
            log.warn("웹훅 페이로드 파싱 실패", e);
            throw new BusinessException(PaymentErrorCode.WEBHOOK_VERIFICATION_FAILED);
        }
    }
```

3. `PortOneWebhookPayload` import가 이미 있으면 유지. `PaymentErrorCode` import 확인.

- [ ] **Step 6: `PaymentControllerTest` 통과 확인**

Run: `./gradlew test --tests '*PaymentControllerTest'`
Expected: PASS

- [ ] **Step 7: `PaymentSecurityTest` 수정**

`PaymentSecurityTest`는 실 `SecurityConfig`를 `@Import`한다. 검증기 실 빈을 `@TestConfiguration`으로 제공하고 웹훅 테스트를 유효/무효 서명으로 갈음한다:

```java
@WebMvcTest(PaymentController.class)
@AutoConfigureMockMvc
@Import({
        SecurityConfig.class,
        JwtAuthenticationEntryPoint.class,
        JwtAccessDeniedHandler.class,
        PaymentSecurityTest.VerifierConfig.class
})
class PaymentSecurityTest {

    @TestConfiguration
    static class VerifierConfig {
        @Bean
        com.gongu.server.global.infrastructure.portone.PortOneWebhookVerifier portOneWebhookVerifier() {
            return new com.gongu.server.global.infrastructure.portone.PortOneWebhookVerifier(
                    new com.gongu.server.global.infrastructure.portone.PortOneProperties(
                            null, null, WebhookSignatures.TEST_SECRET));
        }
    }

    // ... 기존 필드 ...

    @Test
    @DisplayName("POST /payments/webhook — 인증 없이 유효 서명이면 200 (permitAll)")
    void receiveWebhook_validSignature_noAuth_returns200() throws Exception {
        given(paymentService.completePayment(anyString()))
                .willReturn(new com.gongu.server.domain.payment.dto.response.VerifyPaymentResponse(
                        1L, "pay-uuid-001", 10_000L,
                        com.gongu.server.domain.payment.domain.PaymentStatus.PAID,
                        java.time.LocalDateTime.now(),
                        com.gongu.server.domain.order.entity.OrderStatus.PAID));

        String webhookBody = "{\"type\":\"Transaction.Paid\",\"data\":{\"paymentId\":\"pay-uuid-001\"}}";

        mockMvc.perform(post("/payments/webhook")
                        .with(WebhookSignatures.signedHeaders(webhookBody))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(webhookBody))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("POST /payments/webhook — 서명 없으면 400 (permitAll이어도 검증에서 거부)")
    void receiveWebhook_noSignature_returns400() throws Exception {
        String webhookBody = "{\"type\":\"Transaction.Paid\",\"data\":{\"paymentId\":\"pay-uuid-001\"}}";

        mockMvc.perform(post("/payments/webhook")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(webhookBody))
                .andExpect(status().isBadRequest());
    }
```

필요한 import: `org.springframework.boot.test.context.TestConfiguration`, `org.springframework.context.annotation.Bean`, `static org.mockito.BDDMockito.given`, `static org.mockito.ArgumentMatchers.anyString`. 기존 `receiveWebhook_withoutAuthentication_returns200` 테스트는 위 2개로 대체(삭제).

- [ ] **Step 8: 전체 결제 테스트 통과 확인**

Run: `./gradlew test --tests '*Payment*'`
Expected: PASS

- [ ] **Step 9: 커밋**

```bash
git add src/main/java/com/gongu/server/domain/payment/controller/PaymentController.java \
        src/main/java/com/gongu/server/domain/payment/dto/request/PortOneWebhookPayload.java \
        src/test/java/com/gongu/server/domain/payment/controller/WebhookSignatures.java \
        src/test/java/com/gongu/server/domain/payment/controller/PaymentControllerTest.java \
        src/test/java/com/gongu/server/domain/payment/controller/PaymentSecurityTest.java
git commit -m "feat: 웹훅 서명 검증을 결제 컨트롤러에 연결 (#208)"
```

---

## Task 4: ADR — 웹훅 서명 검증 전략

**Files:**
- Create: `docs/adr/웹훅_서명_검증_전략.md`

**Interfaces:** 없음 (문서)

- [ ] **Step 1: ADR 작성**

Create `docs/adr/웹훅_서명_검증_전략.md`:

```markdown
# ADR: 웹훅 서명 검증 전략

- 상태: 채택
- 이슈: #208
- 관련: #207 (PG 조회 실패 시 PENDING 유지)

## 배경

`POST /payments/webhook`은 `permitAll`이며 서명을 검증하지 않았다. `completePayment`의
서버 사이드 PG 재검증이 결제 위조·금액 조작은 막지만, 위조 웹훅으로 다음이 가능했다.

1. **정상 결제 방해** — 결제창 입력 중 위조 웹훅 → 그 시점 PG 상태는 결제 전 → `payment.fail()`
   → `FAILED` 확정 → 이후 정상 결제도 상태 가드에 막힘. #207과 결합 시 복구 불가.
2. **PG 조회 API 남용** — 임의 `paymentId` 대량 요청으로 PG 조회 유발 → 비용 + Circuit Breaker open.

## 결정

### 1. Standard Webhooks 서명을 직접 검증한다

PortOne V2 웹훅은 Standard Webhooks 스펙(<https://www.standardwebhooks.com/>)을 따른다.
`io.portone:server-sdk`가 `WebhookVerifier`를 제공하지만 도입하지 않는다.

- 이 프로젝트는 이미 PortOne SDK 대신 `PortOneClient`(RestClient)를 직접 구현했다 — 일관성.
- 검증 로직은 HMAC-SHA256 + 상수 시간 비교 + 타임스탬프 검사로 ~40줄이며, SDK의
  Kotlin stdlib·HTTP 클라이언트 등 무거운 전이 의존성을 서명 검증 하나 때문에 들일 이유가 없다.

`PortOneWebhookVerifier`:
- 서명 대상: `{webhook-id}.{webhook-timestamp}.{원문 바디}`
- 키: 시크릿에서 `whsec_` 접두사 제거 후 Base64 디코드
- HMAC-SHA256 → Base64, `webhook-signature`의 공백 구분 `{버전},{서명}` 토큰 중 하나라도
  `MessageDigest.isEqual`로 일치하면 통과
- 타임스탬프 허용 오차 ±300초 (재전송 공격 차단)

### 2. 원문 바디는 `@RequestBody String`으로 받는다

서명 대상은 파싱 전 원문 바이트다. 필터(`ContentCachingRequestWrapper`)나 `RequestBodyAdvice`
대신 컨트롤러가 `String`으로 받아 검증 후 `ObjectMapper`로 직접 파싱한다 —
`/payments/webhook`은 단일 소비자라 인프라를 들일 필요가 없다.

### 3. `permitAll` 유지, 검증은 컨트롤러 레벨

서명이 인증을 대체한다. 필터 대신 컨트롤러에서 검증해 슬라이스 테스트로 커버한다.

### 4. 검증 실패 = 400, 재시도 미유발

헤더 누락·서명 불일치·타임스탬프 초과·페이로드 파손을 단일 코드
`PaymentErrorCode.WEBHOOK_VERIFICATION_FAILED`(`PAYMENT_010`, 400)로 거부하고 WARN 로그.
서명 불일치는 재시도해도 통과할 수 없으므로 PortOne 재시도(최대 5회)를 유발하지 않는 4xx가 적절하다.
`PAYMENT_PG_UNAVAILABLE`(503, 재시도 유효)은 서명 통과 후 `completePayment` 경로에서만 발생한다.

### 5. 시크릿 미설정 시 fail-closed

`portone.webhook-secret`이 비어 있으면 모든 웹훅을 거부한다(ERROR 로그).
`PORTONE_WEBHOOK_SECRET` 환경변수로 주입하며 저장소에 실제 값을 두지 않는다.
perf 프로파일은 웹훅 서명 경로를 검증하지 않는다.

## 범위 밖

- `webhook-id` 기반 중복 수신 저장/차단 — 완료 처리는 `completePayment`가 이미 멱등이고,
  타임스탬프 윈도우가 재전송을 제한한다.
- 공식 SDK 마이그레이션.
- `load-test/mock-pg`의 서명 발송 — 현재 mock 웹훅 바디에 `type` 필드가 없어 이미 no-op이다.

## 결과

- 유효 서명의 웹훅만 처리, 서명 없음·불일치·오래된 타임스탬프는 400으로 거부.
- 완료 기준(이슈 #208) 5개 항목 충족.
```

- [ ] **Step 2: 커밋**

```bash
git add docs/adr/웹훅_서명_검증_전략.md
git commit -m "docs: 웹훅 서명 검증 전략 ADR 추가 (#208)"
```

---

## Task 5: 전체 검증

- [ ] **Step 1: 전체 테스트 실행**

Run: `./gradlew clean test`
Expected: BUILD SUCCESSFUL, 전체 스위트 PASS, JaCoCo 커버리지 게이트(80%) 통과

- [ ] **Step 2: 이슈 완료 기준 대조**

이슈 #208 완료 기준을 코드로 대조:

| 완료 기준 | 충족 근거 |
|---|---|
| 유효한 서명의 웹훅만 처리, 서명 없음·불일치는 거부 | `PortOneWebhookVerifierTest`, `PaymentControllerTest.receiveWebhook_서명없음_400` / `_서명불일치_400` |
| 오래된 타임스탬프 요청 거부 (재전송 방지) | `PortOneWebhookVerifierTest.staleTimestamp_rejected` / `futureTimestamp_rejected` |
| 시크릿이 설정으로 주입, 저장소 미포함 | `PortOneProperties.webhookSecret` ← `${PORTONE_WEBHOOK_SECRET:}`, `docker-compose.yml` |
| 검증 실패 시 재시도 미유발 응답 코드 | `WEBHOOK_VERIFICATION_FAILED` httpStatus 400 |
| 정상 웹훅 처리 흐름 회귀 없음 | 기존 `PaymentControllerTest` 웹훅 6종 + 터미널 코드 200 처리 유지 |

- [ ] **Step 3: 커밋할 것 없음 확인**

Run: `git status`
Expected: nothing to commit, working tree clean

---

## Self-Review

**Spec coverage:** 이슈 "수정 범위" 4항목(서명 검증 / 원문 바디 / 시크릿 관리 / 검증 실패 응답 정책) → Task 1(시크릿), Task 2(검증기), Task 3(원문 바디 + 연결), Task 4(정책 문서화). "완료 기준" 5항목 → Task 5 Step 2 대조표.

**Placeholder scan:** 모든 코드 스텝에 실제 코드 포함. "적절한 에러 처리" 류 표현 없음.

**Type consistency:**
- `PortOneProperties(String apiSecret, String baseUrl, String webhookSecret)` — Task 1 정의, Task 2/3 테스트에서 `new PortOneProperties(null, null, SECRET)`로 일관 사용.
- `PortOneWebhookVerifier.verify(String, String, String, String)` — Task 2 정의, Task 3 컨트롤러/테스트에서 동일 시그니처 호출.
- `PaymentErrorCode.WEBHOOK_VERIFICATION_FAILED` (`PAYMENT_010`, 400) — Task 2 정의, Task 3 테스트가 `jsonPath("$.code").value("PAYMENT_010")`로 확인.
- `WebhookSignatures.signedHeaders(String)` / `TEST_SECRET` — Task 3 Step 1 정의, 같은 Task 내 사용.
```
