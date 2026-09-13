# 결제 상태 전이 이력 + PG 응답 원문 보관 (#209) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 결제 상태가 바뀔 때마다(`Payment.confirm/fail/refund/expire`) `payment_histories`에 from/to 상태, 트리거 경로, 사유, 마스킹된 PG 응답 원문을 append-only로 남긴다.

**Architecture:** 상태를 바꾸는 두 서비스(`PaymentService.completePayment`, `PaymentExpireService.cancelExpiredPayment`)가 각 전이 직후 공용 `PaymentHistoryRecorder.record(...)`를 명시적으로 호출한다(B안 — 서비스 계층 명시적 기록, 아래 "설계 결정" 참고). PG 원문은 `PortOneClient`가 `String`으로 먼저 받아 파싱과 함께 보관하고, 저장 직전 화이트리스트 기반 `PgResponseMasker`로 마스킹한다.

**Tech Stack:** Spring Boot 3.5 / JPA(Hibernate) / MySQL 8 / Jackson `ObjectMapper` / JUnit5 + Mockito + `@DataJpaTest`/`@SpringBootTest`

## 설계 결정 (이슈 #209의 "판단 필요" 항목)

1. **테이블명**: `payment_histories` (이슈 #209 본문 기준. 지시서에는 `payment_events`로 적혀 있었으나 실제 이슈와 다름을 확인 — 사용자가 `payment_histories` 채택 확정).
2. **기록 지점 방식**: 이슈가 제시한 A(Entity Listener)/B(서비스 계층 명시적 기록)/C(파사드) 중 **B안**. 현재 상태 전이 호출부는 `PaymentService.completePayment()`와 `PaymentExpireService.cancelExpiredPayment()` 단 2개 파일, 6개 콜사이트뿐이고(grep으로 확인 완료), `#146`이 이 두 경로를 하나의 `PaymentReconciler`로 합치면 콜사이트가 1개로 더 줄어든다. 이 규모에서 A(트리거 컨텍스트 전달 불가 문제 있음)나 C(파사드, 투기적 추상화)는 과하다 — CLAUDE.md "Simplicity First"에 부합하는 쪽은 B.
   - **주의**: `#146`이 이 두 콜사이트를 `PaymentReconciler`로 옮길 때 `paymentHistoryRecorder.record(...)` 호출도 함께 옮겨야 한다. `#146` 계획서에 이 사실을 남겨둘 것.
3. **PG 원문 마스킹**: 화이트리스트 방식. PortOne REST V2 GetPayment 응답에는 `customer.name`/`customer.birthYear`(구매자 개인정보), 결제수단 상세(`method.card.*`) 등이 포함될 수 있음을 공식 문서(`https://developers.portone.io/api/rest-v2/payment`)로 확인했다. 정확한 전체 필드 목록은 문서에 다 나열되어 있지 않으므로, **알려진 안전한 경로만 허용하고 나머지는 전부 마스킹**하는 기본값-거부(default-deny) 방식을 쓴다. 새 필드가 추가돼도 화이트리스트에 없으면 자동으로 마스킹되어 안전하다.
4. **보관 기간**: 이번 PR은 **정책 문서화만** 한다 (실제 삭제/아카이빙 배치는 범위 밖 — 필요하면 별도 이슈). `ddl.sql` 주석에 "5년 보관"을 기본값으로 적어둔다 — 전자상거래법상 "대금결제 및 재화 등의 공급에 관한 기록" 보존 기준(5년)을 참고한 것이나, **정확한 법적 근거는 병합 전 재확인 필요**. 이 프로젝트가 실제 서비스가 아니라 포트폴리오용이면 임의의 기간으로 바꿔도 무방하다는 점을 PR 설명에 명시한다.

## Global Constraints

- 브랜치: `feat/#209-payment-status-history` (이미 생성됨, `origin/main` 기준 — 로컬 `main`은 23커밋 뒤처져 있으니 절대 베이스로 쓰지 않는다)
- 커밋 메시지: `type: 작업 내용 (#209)` — `Co-Authored-By` 절대 포함 금지
- PR 제목: `[FEAT] 작업 내용 (#209)`, 본문에 `close #209` + 마일스톤 연결
- 엔티티 추가 시 `@Table`/`@Column`/`@JoinColumn`을 `docs/schema/ddl.sql`과 직접 대조 (github-rules.md 체크리스트)
- 논리 단위로 커밋 분리 (엔티티+리포지토리 / PG 원문 캡처 / 마스킹 / 기록 컴포넌트 / 서비스 연결)
- `./gradlew test` 전체 통과 필수. 각 작업 단계 검증은 **최종 DB 상태**(응답 코드 아님)를 확인하는 통합 테스트로 한다
- CLAUDE.md: 요청 범위 밖 코드 변경 금지, 투기적 추상화 금지

---

### Task 1: `PaymentHistoryTrigger` enum + `PaymentHistory` 엔티티 + DDL

**Files:**
- Create: `src/main/java/com/gongu/server/domain/payment/domain/PaymentHistoryTrigger.java`
- Create: `src/main/java/com/gongu/server/domain/payment/domain/PaymentHistory.java`
- Modify: `docs/schema/ddl.sql`
- Test: `src/test/java/com/gongu/server/domain/payment/domain/PaymentHistoryTest.java`

**Interfaces:**
- Produces: `PaymentHistoryTrigger { CLIENT_VERIFY, WEBHOOK, EXPIRY_SCHEDULER }`, `PaymentHistory.record(Payment payment, PaymentStatus fromStatus, PaymentStatus toStatus, PaymentHistoryTrigger trigger, String reason, String pgRawResponse)` 정적 팩토리, getter 전부(`getPayment()/getFromStatus()/getToStatus()/getTrigger()/getReason()/getPgRawResponse()/getCreatedAt()`)

- [ ] **Step 1: enum 작성**

```java
package com.gongu.server.domain.payment.domain;

public enum PaymentHistoryTrigger {
    CLIENT_VERIFY, WEBHOOK, EXPIRY_SCHEDULER
}
```

- [ ] **Step 2: 실패하는 엔티티 테스트 작성**

```java
package com.gongu.server.domain.payment.domain;

import com.gongu.server.domain.order.entity.Order;
import com.gongu.server.domain.order.entity.OrderStatus;
import com.gongu.server.domain.store.entity.Store;
import com.gongu.server.domain.user.entity.User;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class PaymentHistoryTest {

    @Test
    void record_필드가_그대로_보관된다() {
        User user = User.builder().name("user").phone("010-0000-0000").isActive(true).build();
        Store store = Store.builder().name("store").address("addr").phone("02-000-0000").isActive(true).build();
        Order order = Order.create(user, store, 10000L);
        Payment payment = Payment.initiate(order, "idem-1", "pay-1", 10000L);

        PaymentHistory history = PaymentHistory.record(
                payment, PaymentStatus.PENDING, PaymentStatus.PAID,
                PaymentHistoryTrigger.CLIENT_VERIFY, null, "{\"status\":\"***\"}");

        assertThat(history.getPayment()).isSameAs(payment);
        assertThat(history.getFromStatus()).isEqualTo(PaymentStatus.PENDING);
        assertThat(history.getToStatus()).isEqualTo(PaymentStatus.PAID);
        assertThat(history.getTrigger()).isEqualTo(PaymentHistoryTrigger.CLIENT_VERIFY);
        assertThat(history.getReason()).isNull();
        assertThat(history.getPgRawResponse()).isEqualTo("{\"status\":\"***\"}");
    }
}
```

`User.builder()`/`Store.builder()`/`Order.create(...)`의 정확한 시그니처가 다르면(예: `Order.create`가 다른 인자를 요구하면) 기존 `PaymentServiceTest.java`의 `user(1L)`/`store(1L)`/`order(...)` 헬퍼 메서드를 그대로 참고해서 맞춘다. 이 테스트는 `PaymentHistory`가 아직 없으므로 컴파일 실패로 fail한다.

- [ ] **Step 3: 컴파일 실패 확인**

Run: `./gradlew compileTestJava`
Expected: `PaymentHistory`/`PaymentHistoryTrigger` 심볼 없음 컴파일 에러

- [ ] **Step 4: `PaymentHistory` 엔티티 구현**

```java
package com.gongu.server.domain.payment.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EntityListeners;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.Lob;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import java.time.LocalDateTime;

@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Entity
@Table(name = "payment_histories")
@EntityListeners(AuditingEntityListener.class)
public class PaymentHistory {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "payment_id", nullable = false)
    private Payment payment;

    @Enumerated(EnumType.STRING)
    @Column(name = "from_status", nullable = false, length = 20)
    private PaymentStatus fromStatus;

    @Enumerated(EnumType.STRING)
    @Column(name = "to_status", nullable = false, length = 20)
    private PaymentStatus toStatus;

    @Enumerated(EnumType.STRING)
    @Column(name = "trigger_type", nullable = false, length = 20)
    private PaymentHistoryTrigger trigger;

    @Column(name = "reason", length = 255)
    private String reason;

    @Lob
    @Column(name = "pg_raw_response")
    private String pgRawResponse;

    @CreatedDate
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    private PaymentHistory(Payment payment, PaymentStatus fromStatus, PaymentStatus toStatus,
                            PaymentHistoryTrigger trigger, String reason, String pgRawResponse) {
        this.payment = payment;
        this.fromStatus = fromStatus;
        this.toStatus = toStatus;
        this.trigger = trigger;
        this.reason = reason;
        this.pgRawResponse = pgRawResponse;
    }

    public static PaymentHistory record(Payment payment, PaymentStatus fromStatus, PaymentStatus toStatus,
                                         PaymentHistoryTrigger trigger, String reason, String pgRawResponse) {
        return new PaymentHistory(payment, fromStatus, toStatus, trigger, reason, pgRawResponse);
    }
}
```

`payment_histories`는 append-only라 `updated_at`/`deleted_at`이 필요 없다 — 그래서 `BaseEntity`를 상속하지 않고 `created_at`만 직접 둔다 (`Payment`가 `BaseEntity`를 상속하는 것과 의도적으로 다름).

- [ ] **Step 5: `docs/schema/ddl.sql` 갱신**

`payments` 테이블 정의(96~108행) 바로 뒤에 추가:

```sql
-- payment_histories 보관 정책: 5년 (전자상거래법상 대금결제 기록 보존 기준 참고 — 병합 전 재확인)
-- 실제 삭제/아카이빙 배치는 범위 밖 (#209). 필요 시 별도 이슈로 분리.
CREATE TABLE `payment_histories` (
    `id`               bigint       NOT NULL,
    `payment_id`       bigint       NOT NULL,
    `from_status`      varchar(20)  NOT NULL,
    `to_status`        varchar(20)  NOT NULL,
    `trigger_type`     varchar(20)  NOT NULL,
    `reason`           varchar(255) NULL,
    `pg_raw_response`  text         NULL,
    `created_at`       datetime     NOT NULL
);
```

"Primary Keys" 섹션(`ALTER TABLE payments ADD CONSTRAINT PK_PAYMENTS ...` 다음 줄)에 추가:

```sql
ALTER TABLE `payment_histories` ADD CONSTRAINT `PK_PAYMENT_HISTORIES` PRIMARY KEY (`id`);
```

"Foreign Key Constraints" 섹션에 `payments` 관련 FK들과 같은 스타일로 추가 (기존 FK 문구의 정확한 포맷은 `docs/schema/ddl.sql`의 `Foreign Key Constraints` 섹션을 열어 그대로 따라 쓴다):

```sql
ALTER TABLE `payment_histories`
    ADD CONSTRAINT `FK_PAYMENT_HISTORIES_PAYMENT_ID`
    FOREIGN KEY (`payment_id`) REFERENCES `payments` (`id`);
```

`docs/schema/table-definitions.md`에도 다른 테이블과 같은 포맷으로 `payment_histories` 설명을 추가한다 (컬럼별 설명 + 보관 정책 5년 명시).

- [ ] **Step 6: 테스트 통과 확인**

Run: `./gradlew test --tests "com.gongu.server.domain.payment.domain.PaymentHistoryTest"`
Expected: PASS

- [ ] **Step 7: 커밋**

```bash
git add src/main/java/com/gongu/server/domain/payment/domain/PaymentHistoryTrigger.java \
        src/main/java/com/gongu/server/domain/payment/domain/PaymentHistory.java \
        src/test/java/com/gongu/server/domain/payment/domain/PaymentHistoryTest.java \
        docs/schema/ddl.sql docs/schema/table-definitions.md
git commit -m "feat: payment_histories 엔티티 및 스키마 추가 (#209)"
```

---

### Task 2: `PaymentHistoryRepository`

**Files:**
- Create: `src/main/java/com/gongu/server/domain/payment/repository/PaymentHistoryRepository.java`
- Test: `src/test/java/com/gongu/server/domain/payment/repository/PaymentHistoryRepositoryTest.java`

**Interfaces:**
- Consumes: `PaymentHistory`(Task 1)
- Produces: `PaymentHistoryRepository extends JpaRepository<PaymentHistory, Long>`, `List<PaymentHistory> findByPaymentIdOrderByCreatedAtAsc(Long paymentId)`

- [ ] **Step 1: `@DataJpaTest`로 실패하는 테스트 작성**

```java
package com.gongu.server.domain.payment.repository;

import com.gongu.server.domain.order.entity.Order;
import com.gongu.server.domain.payment.domain.Payment;
import com.gongu.server.domain.payment.domain.PaymentHistory;
import com.gongu.server.domain.payment.domain.PaymentHistoryTrigger;
import com.gongu.server.domain.payment.domain.PaymentStatus;
import com.gongu.server.domain.order.repository.OrderRepository;
import com.gongu.server.domain.user.entity.User;
import com.gongu.server.domain.user.repository.UserRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@DataJpaTest
class PaymentHistoryRepositoryTest {

    @Autowired private PaymentHistoryRepository paymentHistoryRepository;
    @Autowired private PaymentRepository paymentRepository;
    @Autowired private OrderRepository orderRepository;
    @Autowired private UserRepository userRepository;

    @Test
    void findByPaymentIdOrderByCreatedAtAsc_생성순으로_반환() {
        // 실제 시그니처 확인 완료 (Task 1 리뷰에서 재확인): User.of(name, phone),
        // Order.create(user, totalPrice) — Order는 Store를 참조하지 않는다.
        User user = userRepository.save(User.of("u", "010-1111-2222"));
        Order order = orderRepository.save(Order.create(user, 10000L));
        Payment payment = paymentRepository.save(Payment.initiate(order, "idem-1", "pay-1", 10000L));

        paymentHistoryRepository.save(PaymentHistory.record(
                payment, PaymentStatus.PENDING, PaymentStatus.PAID,
                PaymentHistoryTrigger.CLIENT_VERIFY, null, null));
        paymentHistoryRepository.save(PaymentHistory.record(
                payment, PaymentStatus.PAID, PaymentStatus.REFUNDED,
                PaymentHistoryTrigger.WEBHOOK, "테스트", null));

        List<PaymentHistory> histories = paymentHistoryRepository.findByPaymentIdOrderByCreatedAtAsc(payment.getId());

        assertThat(histories).hasSize(2);
        assertThat(histories.get(0).getToStatus()).isEqualTo(PaymentStatus.PAID);
        assertThat(histories.get(1).getToStatus()).isEqualTo(PaymentStatus.REFUNDED);
    }
}
```

위 시그니처(`User.of(String, String)`, `Order.create(User, long)` — Store 없음, `Payment.initiate(Order, String, String, Long)`)는 실제 소스에서 확인됐다. 그래도 필드명·검증 규칙 등 세부사항이 다르면 `PaymentServiceTest.java` 상단의 헬퍼 메서드(`user(...)`, `order(...)`)를 열어 대조한다.

- [ ] **Step 2: 컴파일 실패 확인**

Run: `./gradlew compileTestJava`
Expected: `PaymentHistoryRepository` 없음 컴파일 에러

- [ ] **Step 3: 리포지토리 구현**

```java
package com.gongu.server.domain.payment.repository;

import com.gongu.server.domain.payment.domain.PaymentHistory;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface PaymentHistoryRepository extends JpaRepository<PaymentHistory, Long> {
    List<PaymentHistory> findByPaymentIdOrderByCreatedAtAsc(Long paymentId);
}
```

- [ ] **Step 4: 테스트 통과 확인**

Run: `./gradlew test --tests "com.gongu.server.domain.payment.repository.PaymentHistoryRepositoryTest"`
Expected: PASS

- [ ] **Step 5: 커밋**

```bash
git add src/main/java/com/gongu/server/domain/payment/repository/PaymentHistoryRepository.java \
        src/test/java/com/gongu/server/domain/payment/repository/PaymentHistoryRepositoryTest.java
git commit -m "feat: PaymentHistoryRepository 추가 (#209)"
```

---

### Task 3: `PortOneClient` — PG 응답 원문 캡처

**Files:**
- Create: `src/main/java/com/gongu/server/global/infrastructure/portone/dto/PortOnePaymentResult.java`
- Modify: `src/main/java/com/gongu/server/global/infrastructure/portone/PortOneClient.java`
- Modify: `src/test/java/com/gongu/server/global/infrastructure/portone/PortOneClientResilienceTest.java` (신규 테스트 메서드 1개 추가 — 새 파일 만들지 않음)
- Modify: `src/test/java/com/gongu/server/domain/payment/service/PaymentServiceTest.java` (9곳 — `portOneClient.getPayment(...)` 스텁)
- Modify: `src/test/java/com/gongu/server/domain/payment/service/PaymentCompleteBulkheadTest.java` (필요 시)

**Interfaces:**
- Produces: `record PortOnePaymentResult(PortOnePaymentResponse response, String rawBody)`, `PortOneClient.getPayment(String paymentId)`가 `PortOnePaymentResult` 반환(기존엔 `PortOnePaymentResponse`), `PortOneClient.cancelPayment(String paymentId, String reason)`도 `PortOnePaymentResult` 반환

- [ ] **Step 1: `PortOnePaymentResult` record 작성**

```java
package com.gongu.server.global.infrastructure.portone.dto;

public record PortOnePaymentResult(PortOnePaymentResponse response, String rawBody) {}
```

- [ ] **Step 2: 실패하는 테스트 작성 — `MockRestServiceServer`로 원문 확인**

이 프로젝트는 WireMock이 아니라 Spring의 `MockRestServiceServer`를 쓴다(`PortOneClientResilienceTest.java` 확인 완료 — `@TestConfiguration`으로 `MockRestServiceServer.bindTo(builder)`를 `@Primary RestClient` 빈으로 등록하는 구조). 새 테스트 클래스를 만들지 않고 **이 파일에 메서드를 추가**한다 — `MockServerConfig`/`@BeforeEach`/`@AfterEach`가 이미 있으므로 그대로 재사용한다.

`PortOneClientResilienceTest.java`의 마지막 `@Test` 메서드 뒤에 추가:

```java
@Test
@DisplayName("정상 응답 시 원문 바디가 파싱 결과와 함께 반환된다 (#209)")
void getPayment_원문_바디가_함께_반환된다() {
    String rawJson = "{\"id\":\"pg-tx-1\",\"status\":\"PAID\",\"amount\":{\"total\":10000},"
            + "\"paidAt\":\"2026-01-01T00:00:00+09:00\",\"customer\":{\"name\":\"홍길동\"}}";
    server.expect(requestTo(containsString("/payments/" + PAYMENT_ID)))
            .andRespond(withSuccess(rawJson, MediaType.APPLICATION_JSON));

    PortOnePaymentResult result = portOneClient.getPayment(PAYMENT_ID);

    assertThat(result.response().status()).isEqualTo("PAID");
    assertThat(result.rawBody()).isEqualTo(rawJson);
}
```

파일 상단 import에 추가:

```java
import com.gongu.server.global.infrastructure.portone.dto.PortOnePaymentResult;
import org.springframework.http.MediaType;

import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;
```

- [ ] **Step 3: 컴파일/실행 실패 확인**

Run: `./gradlew compileTestJava`
Expected: `PortOnePaymentResult` 타입 관련 컴파일 에러 또는 `getPayment` 반환 타입 불일치 에러

- [ ] **Step 4: `PortOneClient` 수정**

`ObjectMapper` 의존성 추가, `getPayment`/`cancelPayment`를 문자열로 먼저 받아 수동 파싱하도록 변경:

```java
package com.gongu.server.global.infrastructure.portone;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.gongu.server.global.exception.BusinessException;
import com.gongu.server.global.exception.InfraException;
import com.gongu.server.global.exception.errorcode.PaymentErrorCode;
import com.gongu.server.global.infrastructure.portone.dto.PortOnePaymentResponse;
import com.gongu.server.global.infrastructure.portone.dto.PortOnePaymentResult;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import io.github.resilience4j.retry.annotation.Retry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;

import java.util.Map;

@Slf4j
@Component
@RequiredArgsConstructor
public class PortOneClient {

    private final RestClient portOneRestClient;
    private final ObjectMapper objectMapper;

    @CircuitBreaker(name = "portone", fallbackMethod = "getPaymentFallback")
    @Retry(name = "portone")
    public PortOnePaymentResult getPayment(String paymentId) {
        try {
            String rawBody = portOneRestClient.get()
                    .uri("/payments/{paymentId}", paymentId)
                    .retrieve()
                    .body(String.class);
            return new PortOnePaymentResult(parseResponse(paymentId, rawBody), rawBody);
        } catch (HttpClientErrorException e) {
            log.warn("PortOne getPayment client error: paymentId={}, status={}", paymentId, e.getStatusCode());
            throw new BusinessException(PaymentErrorCode.PAYMENT_NOT_FOUND);
        }
    }

    private PortOnePaymentResult getPaymentFallback(String paymentId, Exception e) {
        if (e instanceof BusinessException businessException) {
            throw businessException;
        }
        log.error("PortOne circuit open or retry exhausted for getPayment: paymentId={}", paymentId, e);
        throw new InfraException(PaymentErrorCode.PAYMENT_PG_UNAVAILABLE);
    }

    @CircuitBreaker(name = "portone", fallbackMethod = "cancelPaymentFallback")
    @Retry(name = "portone")
    public PortOnePaymentResult cancelPayment(String paymentId, String reason) {
        try {
            String rawBody = portOneRestClient.post()
                    .uri("/payments/{paymentId}/cancel", paymentId)
                    .body(Map.of("reason", reason))
                    .retrieve()
                    .body(String.class);
            return new PortOnePaymentResult(parseResponse(paymentId, rawBody), rawBody);
        } catch (HttpClientErrorException e) {
            log.warn("PortOne cancelPayment client error: paymentId={}, status={}", paymentId, e.getStatusCode());
            if (e.getStatusCode() == HttpStatus.NOT_FOUND) {
                throw new BusinessException(PaymentErrorCode.PAYMENT_NOT_FOUND);
            }
            throw new BusinessException(PaymentErrorCode.PAYMENT_ALREADY_PROCESSED);
        }
    }

    private PortOnePaymentResult cancelPaymentFallback(String paymentId, String reason, Exception e) {
        if (e instanceof BusinessException businessException) {
            throw businessException;
        }
        log.warn("PortOne cancelPayment circuit open or retry exhausted: paymentId={}", paymentId, e);
        throw new InfraException(PaymentErrorCode.PAYMENT_PG_UNAVAILABLE);
    }

    private PortOnePaymentResponse parseResponse(String paymentId, String rawBody) {
        if (rawBody == null || rawBody.isBlank()) {
            return null;
        }
        try {
            return objectMapper.readValue(rawBody, PortOnePaymentResponse.class);
        } catch (JsonProcessingException e) {
            log.error("PortOne 응답 파싱 실패 — 원본 body 형식이 예상과 다름: paymentId={}", paymentId, e);
            throw new IllegalStateException("PortOne 응답 파싱 실패: paymentId=" + paymentId, e);
        }
    }
}
```

**왜 `InfraException`이 아니라 `IllegalStateException`인가 (기존 동작 보존 근거):** 기존 코드는 `.retrieve().body(PortOnePaymentResponse.class)`로 Jackson이 직접 역직렬화했다. 이때 (a) 빈 바디는 `null`을 반환했고(`PaymentService`의 `portOneResponse == null` 분기, `PaymentServiceTest.completePayment_PG_빈응답...` 테스트가 이 경로를 검증한다) (b) 형식이 깨진 JSON은 Jackson이 unchecked 예외(`HttpMessageNotReadableException` 등)를 던졌는데 이 예외는 `catch (HttpClientErrorException e)`에 잡히지 않아 그대로 위로 전파됐다 — 즉 `BusinessException`도 `InfraException`도 아니었다. `String.class`로 먼저 받아 수동 파싱하면 이 두 경로를 각각 재현해야 한다: 빈/공백 바디 → `null` 반환(위 (a) 보존), 그 외 파싱 실패 → **분류되지 않은 unchecked 예외**로 전파(위 (b) 보존). 만약 여기서 `InfraException`을 던지면 `completePayment`의 `catch (InfraException e)` 분기가 "PG 조회 실패, PENDING 유지"로 부드럽게 처리해버려 원래는 처리되지 않던 이례적 상황(PG가 JSON이 아닌 걸 200으로 보냄)을 조용히 정상 흐름처럼 삼키게 된다 — 이건 #209 범위 밖의 동작 변경이므로 피한다. 이 동작이 실제로 유지되는지는 Step 6에서 기존 테스트 실행으로 확인한다.

- [ ] **Step 5: `PaymentServiceTest.java`의 9개 스텁 갱신**

```bash
grep -n "portOneClient.getPayment(PAYMENT_ID)" src/test/java/com/gongu/server/domain/payment/service/PaymentServiceTest.java
```

각 스텁을 `PortOnePaymentResult`로 감싼다. 예를 들어 286행 근처:

```java
// 변경 전
PortOnePaymentResponse portOneResponse = new PortOnePaymentResponse(...);
given(portOneClient.getPayment(PAYMENT_ID)).willReturn(portOneResponse);

// 변경 후
PortOnePaymentResponse portOneResponse = new PortOnePaymentResponse(...);
String rawBody = "{\"id\":\"" + PAYMENT_ID + "\",\"status\":\"PAID\"}"; // 각 테스트 상황에 맞는 status로 조정
given(portOneClient.getPayment(PAYMENT_ID)).willReturn(new PortOnePaymentResult(portOneResponse, rawBody));
```

`given(portOneClient.getPayment(PAYMENT_ID)).willReturn(null)` (399행, PG 빈 응답 테스트)은 그대로 둔다 — `PortOneClient.getPayment` 자체가 `null`을 반환하는 상황(빈 바디)을 이 테스트가 Mockito 레벨에서 흉내내는 것이므로 여전히 유효하다.

`PortOnePaymentResult` import를 파일 상단에 추가한다.

- [ ] **Step 6: 컴파일 및 기존 테스트 통과 확인**

Run: `./gradlew compileTestJava`
Expected: 컴파일 성공 (실패하면 `grep -rn "portOneClient.getPayment\|portOneClient.cancelPayment" src/test`로 놓친 콜사이트를 찾아 같은 방식으로 고친다. `PortOneClientResilienceTest.java`의 기존 테스트들은 반환값을 쓰지 않으므로 Step 2에서 추가한 새 메서드 외에는 수정 불필요)

Run: `./gradlew test --tests "com.gongu.server.domain.payment.service.PaymentServiceTest" --tests "com.gongu.server.global.infrastructure.portone.*"`
Expected: 전부 PASS (빈 응답 시 PENDING 유지 동작 포함)

- [ ] **Step 7: 신규 테스트 통과 확인**

Run: `./gradlew test --tests "com.gongu.server.global.infrastructure.portone.PortOneClientResilienceTest"`
Expected: PASS (기존 서킷브레이커 테스트 + Step 2에서 추가한 원문 반환 테스트 모두 포함)

- [ ] **Step 8: 커밋**

```bash
git add src/main/java/com/gongu/server/global/infrastructure/portone/PortOneClient.java \
        src/main/java/com/gongu/server/global/infrastructure/portone/dto/PortOnePaymentResult.java \
        src/test/java/com/gongu/server/global/infrastructure/portone/PortOneClientResilienceTest.java \
        src/test/java/com/gongu/server/domain/payment/service/PaymentServiceTest.java
git commit -m "feat: PortOneClient가 PG 응답 원문을 함께 반환하도록 변경 (#209)"
```

---

### Task 4: `PgResponseMasker` — 화이트리스트 기반 마스킹

**Files:**
- Create: `src/main/java/com/gongu/server/global/infrastructure/portone/PgResponseMasker.java`
- Test: `src/test/java/com/gongu/server/global/infrastructure/portone/PgResponseMaskerTest.java`

**Interfaces:**
- Consumes: 없음 (Jackson `ObjectMapper`만 주입받음 — 프로젝트에 이미 빈으로 등록돼 있음)
- Produces: `PgResponseMasker.mask(String rawJson) -> String`

- [ ] **Step 1: 실패하는 테스트 작성**

```java
package com.gongu.server.global.infrastructure.portone;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class PgResponseMaskerTest {

    private final PgResponseMasker masker = new PgResponseMasker(new ObjectMapper());

    @Test
    void mask_화이트리스트_필드는_유지하고_나머지는_마스킹() {
        String raw = "{\"id\":\"pay-1\",\"status\":\"PAID\",\"amount\":{\"total\":10000,\"currency\":\"KRW\"},"
                + "\"customer\":{\"name\":\"홍길동\",\"birthYear\":\"1990\"}}";

        String masked = masker.mask(raw);

        assertThat(masked).contains("\"id\":\"pay-1\"");
        assertThat(masked).contains("\"status\":\"PAID\"");
        assertThat(masked).contains("\"total\":10000");
        assertThat(masked).doesNotContain("홍길동");
        assertThat(masked).doesNotContain("1990");
    }

    @Test
    void mask_null이나_빈문자열은_그대로_반환() {
        assertThat(masker.mask(null)).isNull();
        assertThat(masker.mask("")).isEmpty();
    }

    @Test
    void mask_파싱불가능한_문자열은_마스킹값으로_대체() {
        assertThat(masker.mask("not-a-json")).isEqualTo("***");
    }
}
```

- [ ] **Step 2: 컴파일 실패 확인**

Run: `./gradlew compileTestJava`
Expected: `PgResponseMasker` 없음 컴파일 에러

- [ ] **Step 3: 구현**

```java
package com.gongu.server.global.infrastructure.portone;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Set;

/**
 * PortOne 응답 원문을 저장하기 전 개인정보를 제거한다.
 * 화이트리스트(default-deny) 방식 — 여기 없는 필드는 스키마가 바뀌어도 자동으로 마스킹된다.
 * 참고: https://developers.portone.io/api/rest-v2/payment
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PgResponseMasker {

    private static final String MASKED = "***";

    private static final Set<String> ALLOWED_PATHS = Set.of(
            "id",
            "status",
            "transactionId",
            "currency",
            "amount.total",
            "amount.currency",
            "paidAt",
            "requestedAt",
            "method.type",
            "channel.pgProvider"
    );

    private final ObjectMapper objectMapper;

    public String mask(String rawJson) {
        if (rawJson == null || rawJson.isEmpty()) {
            return rawJson;
        }
        try {
            JsonNode root = objectMapper.readTree(rawJson);
            JsonNode masked = maskNode(root, "");
            return objectMapper.writeValueAsString(masked);
        } catch (Exception e) {
            log.warn("PG 응답 원문 마스킹 실패 — 전체 마스킹으로 대체", e);
            return MASKED;
        }
    }

    private JsonNode maskNode(JsonNode node, String path) {
        if (node.isObject()) {
            ObjectNode result = objectMapper.createObjectNode();
            node.fields().forEachRemaining(entry -> {
                String childPath = path.isEmpty() ? entry.getKey() : path + "." + entry.getKey();
                result.set(entry.getKey(), maskNode(entry.getValue(), childPath));
            });
            return result;
        }
        if (node.isArray()) {
            ArrayNode result = objectMapper.createArrayNode();
            node.forEach(element -> result.add(maskNode(element, path)));
            return result;
        }
        if (ALLOWED_PATHS.contains(path)) {
            return node;
        }
        return objectMapper.getNodeFactory().textNode(MASKED);
    }
}
```

- [ ] **Step 4: 테스트 통과 확인**

Run: `./gradlew test --tests "com.gongu.server.global.infrastructure.portone.PgResponseMaskerTest"`
Expected: PASS

- [ ] **Step 5: 커밋**

```bash
git add src/main/java/com/gongu/server/global/infrastructure/portone/PgResponseMasker.java \
        src/test/java/com/gongu/server/global/infrastructure/portone/PgResponseMaskerTest.java
git commit -m "feat: PG 응답 원문 화이트리스트 마스킹 추가 (#209)"
```

---

### Task 5: `PaymentHistoryRecorder`

**Files:**
- Create: `src/main/java/com/gongu/server/domain/payment/service/PaymentHistoryRecorder.java`
- Test: `src/test/java/com/gongu/server/domain/payment/service/PaymentHistoryRecorderTest.java`

**Interfaces:**
- Consumes: `PaymentHistoryRepository`(Task 2), `PgResponseMasker`(Task 4), `PaymentHistory.record(...)`(Task 1)
- Produces: `PaymentHistoryRecorder.record(Payment payment, PaymentStatus fromStatus, PaymentStatus toStatus, PaymentHistoryTrigger trigger, String reason, String rawPgResponse)` — `void`, `Propagation.MANDATORY` (트랜잭션 밖에서 호출하면 즉시 예외)

- [ ] **Step 1: 실패하는 단위 테스트 작성**

```java
package com.gongu.server.domain.payment.service;

import com.gongu.server.domain.payment.domain.Payment;
import com.gongu.server.domain.payment.domain.PaymentHistory;
import com.gongu.server.domain.payment.domain.PaymentHistoryTrigger;
import com.gongu.server.domain.payment.domain.PaymentStatus;
import com.gongu.server.domain.payment.repository.PaymentHistoryRepository;
import com.gongu.server.global.infrastructure.portone.PgResponseMasker;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class PaymentHistoryRecorderTest {

    @Mock private PaymentHistoryRepository paymentHistoryRepository;
    @Mock private PgResponseMasker pgResponseMasker;
    @InjectMocks private PaymentHistoryRecorder paymentHistoryRecorder;

    @Test
    void record_원문이_있으면_마스킹후_저장한다() {
        Payment payment = mock(Payment.class);
        given(pgResponseMasker.mask("raw")).willReturn("masked");

        paymentHistoryRecorder.record(payment, PaymentStatus.PENDING, PaymentStatus.PAID,
                PaymentHistoryTrigger.CLIENT_VERIFY, null, "raw");

        ArgumentCaptor<PaymentHistory> captor = ArgumentCaptor.forClass(PaymentHistory.class);
        verify(paymentHistoryRepository).save(captor.capture());
        assertThat(captor.getValue().getPgRawResponse()).isEqualTo("masked");
        assertThat(captor.getValue().getFromStatus()).isEqualTo(PaymentStatus.PENDING);
        assertThat(captor.getValue().getToStatus()).isEqualTo(PaymentStatus.PAID);
        assertThat(captor.getValue().getTrigger()).isEqualTo(PaymentHistoryTrigger.CLIENT_VERIFY);
    }

    @Test
    void record_원문이_null이면_마스킹을_호출하지_않는다() {
        Payment payment = mock(Payment.class);

        paymentHistoryRecorder.record(payment, PaymentStatus.PENDING, PaymentStatus.CANCELLED,
                PaymentHistoryTrigger.EXPIRY_SCHEDULER, "TTL 경과", null);

        ArgumentCaptor<PaymentHistory> captor = ArgumentCaptor.forClass(PaymentHistory.class);
        verify(paymentHistoryRepository).save(captor.capture());
        assertThat(captor.getValue().getPgRawResponse()).isNull();
    }
}
```

- [ ] **Step 2: 컴파일 실패 확인**

Run: `./gradlew compileTestJava`
Expected: `PaymentHistoryRecorder` 없음 컴파일 에러

- [ ] **Step 3: 구현**

```java
package com.gongu.server.domain.payment.service;

import com.gongu.server.domain.payment.domain.Payment;
import com.gongu.server.domain.payment.domain.PaymentHistory;
import com.gongu.server.domain.payment.domain.PaymentHistoryTrigger;
import com.gongu.server.domain.payment.domain.PaymentStatus;
import com.gongu.server.domain.payment.repository.PaymentHistoryRepository;
import com.gongu.server.global.infrastructure.portone.PgResponseMasker;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Component
@RequiredArgsConstructor
public class PaymentHistoryRecorder {

    private final PaymentHistoryRepository paymentHistoryRepository;
    private final PgResponseMasker pgResponseMasker;

    @Transactional(propagation = Propagation.MANDATORY)
    public void record(Payment payment, PaymentStatus fromStatus, PaymentStatus toStatus,
                        PaymentHistoryTrigger trigger, String reason, String rawPgResponse) {
        String maskedResponse = rawPgResponse == null ? null : pgResponseMasker.mask(rawPgResponse);
        paymentHistoryRepository.save(
                PaymentHistory.record(payment, fromStatus, toStatus, trigger, reason, maskedResponse));
    }
}
```

`Propagation.MANDATORY`를 쓰는 이유: 이 기록은 반드시 상태 변경과 같은 트랜잭션 안에서 커밋돼야 한다(상태는 바뀌었는데 이력만 없는 상황을 구조적으로 차단). 트랜잭션 없이 호출되면 `IllegalTransactionStateException`이 즉시 발생해 누락을 조용히 넘기지 않는다.

- [ ] **Step 4: 테스트 통과 확인**

Run: `./gradlew test --tests "com.gongu.server.domain.payment.service.PaymentHistoryRecorderTest"`
Expected: PASS

- [ ] **Step 5: 커밋**

```bash
git add src/main/java/com/gongu/server/domain/payment/service/PaymentHistoryRecorder.java \
        src/test/java/com/gongu/server/domain/payment/service/PaymentHistoryRecorderTest.java
git commit -m "feat: PaymentHistoryRecorder 추가 (#209)"
```

---

### Task 6: `PaymentService.completePayment` 연결 + trigger 파라미터화

**Files:**
- Modify: `src/main/java/com/gongu/server/domain/payment/service/PaymentService.java:101-214`
- Modify: `src/main/java/com/gongu/server/domain/payment/controller/PaymentController.java:69,93`
- Modify: `src/test/java/com/gongu/server/domain/payment/service/PaymentServiceTest.java` (시그니처 변경으로 15곳)
- Modify: `src/test/java/com/gongu/server/domain/payment/controller/PaymentControllerTest.java` (17곳)
- Modify: `src/test/java/com/gongu/server/domain/payment/controller/PaymentSecurityTest.java` (1곳)
- Modify: `src/test/java/com/gongu/server/domain/payment/service/PaymentCompleteBulkheadTest.java` (1곳)
- Test: `src/test/java/com/gongu/server/domain/payment/service/PaymentHistoryIntegrationTest.java` (신규, `@SpringBootTest`)

**Interfaces:**
- Consumes: `PaymentHistoryRecorder.record(...)`(Task 5), `PaymentHistoryTrigger`(Task 1), `PortOnePaymentResult`(Task 3)
- Produces: `PaymentService.completePayment(String paymentId, PaymentHistoryTrigger trigger)` — 시그니처 변경 (기존 `completePayment(String paymentId)`에서 파라미터 추가)

이 태스크는 실제 DB 커밋 결과를 보는 통합 테스트가 핵심이므로 TDD 순서를 "통합 테스트 먼저"로 조정한다: 컴파일이 되어야 통합 테스트를 돌릴 수 있으므로, 먼저 시그니처와 배선을 최소로 맞추고 통합 테스트로 검증한다.

- [ ] **Step 1: `PaymentService.completePayment` 시그니처 변경 + 6개 지점에 기록 연결**

`PaymentService.java`에 임포트 추가:

```java
import com.gongu.server.domain.payment.domain.PaymentHistoryTrigger;
import com.gongu.server.global.infrastructure.portone.dto.PortOnePaymentResult;
```

생성자 필드에 추가:

```java
private final PaymentHistoryRecorder paymentHistoryRecorder;
```

101~191행(`completePayment` 전체)을 아래로 교체:

```java
@Bulkhead(name = "payment-complete")
@Transactional(noRollbackFor = {BusinessException.class, InfraException.class})
public VerifyPaymentResponse completePayment(String paymentId, PaymentHistoryTrigger trigger) {
    Payment payment = paymentRepository.findByMerchantUidWithLock(paymentId)
            .orElseThrow(() -> new BusinessException(PaymentErrorCode.PAYMENT_NOT_FOUND));

    if (payment.getStatus() == PaymentStatus.PAID) {
        return VerifyPaymentResponse.of(payment.getOrder(), payment);
    }

    Order order = orderRepository.findByIdWithLock(payment.getOrder().getId())
            .orElseThrow(() -> new BusinessException(OrderErrorCode.ORDER_NOT_FOUND));

    if (order.getStatus() == OrderStatus.CANCELLED) {
        if (payment.getStatus() == PaymentStatus.REFUNDED) {
            paymentFailedOrderExpiredIdempotentCounter.increment();
            throw new BusinessException(PaymentErrorCode.ORDER_EXPIRED_REFUNDED);
        }
        if (payment.getStatus() != PaymentStatus.PENDING
                && payment.getStatus() != PaymentStatus.CANCELLED) {
            throw new BusinessException(PaymentErrorCode.PAYMENT_INVALID_STATE_TRANSITION);
        }
        PaymentStatus beforeExpiredBranch = payment.getStatus();
        PgCancelOutcome cancelOutcome = executePGCancel(paymentId, "주문 만료로 인한 자동 환불");
        if (cancelOutcome.cancelled()) {
            payment.refund();
            paymentHistoryRecorder.record(payment, beforeExpiredBranch, payment.getStatus(), trigger,
                    "주문 만료로 인한 자동 환불", cancelOutcome.rawBody());
        } else if (beforeExpiredBranch == PaymentStatus.PENDING) {
            payment.expire();
            paymentHistoryRecorder.record(payment, beforeExpiredBranch, payment.getStatus(), trigger,
                    "주문 만료 - PG에 결제 없음", null);
        }
        paymentFailedOrderExpiredCancelCounter.increment();
        throw new BusinessException(PaymentErrorCode.ORDER_EXPIRED_REFUNDED);
    }

    if (payment.getStatus() != PaymentStatus.PENDING) {
        throw new BusinessException(PaymentErrorCode.PAYMENT_INVALID_STATE_TRANSITION);
    }

    PortOnePaymentResult portOneResult;
    try {
        portOneResult = portOneClient.getPayment(paymentId);
    } catch (InfraException e) {
        log.warn("PortOne 조회 실패 — payment PENDING 유지, 재시도 대기: paymentId={}", paymentId, e);
        paymentFailedPgErrorCounter.increment();
        throw e;
    }

    if (portOneResult == null || portOneResult.response() == null) {
        log.warn("PortOne 빈 응답 — payment PENDING 유지, 재시도 대기: paymentId={}", paymentId);
        paymentFailedPgNullCounter.increment();
        throw new BusinessException(PaymentErrorCode.PAYMENT_PG_UNAVAILABLE);
    }

    PortOnePaymentResponse portOneResponse = portOneResult.response();

    if (!"PAID".equals(portOneResponse.status())) {
        PaymentStatus beforeFail = payment.getStatus();
        payment.fail();
        paymentHistoryRecorder.record(payment, beforeFail, payment.getStatus(), trigger,
                "PG 상태 불일치: " + portOneResponse.status(), portOneResult.rawBody());
        paymentFailedPgStatusMismatchCounter.increment();
        throw new BusinessException(PaymentErrorCode.PAYMENT_NOT_COMPLETED);
    }

    Long expectedAmount = order.getTotalPrice();
    Long actualAmount = portOneResponse.amount().total();

    if (expectedAmount.equals(actualAmount)) {
        PaymentStatus beforeConfirm = payment.getStatus();
        order.pay();
        payment.confirm(actualAmount, portOneResponse.paidAt().toLocalDateTime());
        paymentHistoryRecorder.record(payment, beforeConfirm, payment.getStatus(), trigger,
                null, portOneResult.rawBody());

        List<OrderItem> items = orderItemRepository.findAllByOrder(order);
        items.forEach(item -> {
            Product product = productRepository.findByIdWithLock(item.getProduct().getId())
                    .orElseThrow(() -> new BusinessException(ProductErrorCode.PRODUCT_NOT_FOUND));
            product.confirmStock(Math.toIntExact(item.getQuantity()));
        });

        paymentCompletedCounter.increment();
        return VerifyPaymentResponse.of(order, payment);
    } else {
        PaymentStatus beforeMismatch = payment.getStatus();
        PgCancelOutcome cancelOutcome = executePGCancel(paymentId, "결제 금액 불일치");
        payment.refund();
        paymentHistoryRecorder.record(payment, beforeMismatch, payment.getStatus(), trigger,
                "결제 금액 불일치", cancelOutcome.rawBody() != null ? cancelOutcome.rawBody() : portOneResult.rawBody());
        paymentFailedAmountMismatchCounter.increment();
        order.cancel("결제 금액 불일치");
        List<OrderItem> cancelledItems = orderItemRepository.findAllByOrder(order);
        cancelledItems.forEach(item ->
                stockRedisService.releaseStockAfterCommit(item.getProduct().getId(), Math.toIntExact(item.getQuantity()))
        );
        throw new BusinessException(PaymentErrorCode.PAYMENT_AMOUNT_MISMATCH);
    }
}

private record PgCancelOutcome(boolean cancelled, String rawBody) {}

private PgCancelOutcome executePGCancel(String paymentId, String reason) {
    try {
        PortOnePaymentResult result = portOneClient.cancelPayment(paymentId, reason);
        return new PgCancelOutcome(true, result == null ? null : result.rawBody());
    } catch (BusinessException e) {
        if (e.getErrorCode() == PaymentErrorCode.PAYMENT_ALREADY_PROCESSED) {
            log.info("PortOne cancel idempotent: paymentId={}, reason={}", paymentId, e.getErrorCode().getCode());
            return new PgCancelOutcome(true, null);
        } else if (e.getErrorCode() == PaymentErrorCode.PAYMENT_NOT_FOUND) {
            log.info("PortOne cancel skipped - payment not found in PG: paymentId={}", paymentId);
            return new PgCancelOutcome(false, null);
        } else {
            throw e;
        }
    }
}
```

`executePGCancel`의 반환 타입이 `boolean` → `PgCancelOutcome`으로 바뀌었으므로 이 메서드를 쓰는 다른 곳이 없는지 확인한다: `grep -n "executePGCancel" src/main/java/com/gongu/server/domain/payment/service/PaymentService.java` — 이 파일 안의 2곳(위 코드에 이미 반영됨) 외에는 없어야 정상이다.

- [ ] **Step 2: `PaymentController` 호출부 갱신**

```java
// verifyPayment (69행)
VerifyPaymentResponse result = paymentService.completePayment(request.paymentId(), PaymentHistoryTrigger.CLIENT_VERIFY);

// receiveWebhook (93행)
paymentService.completePayment(paymentId, PaymentHistoryTrigger.WEBHOOK);
```

`import com.gongu.server.domain.payment.domain.PaymentHistoryTrigger;` 추가.

- [ ] **Step 3: 컴파일 실패로 기존 테스트 콜사이트 전부 확인**

Run: `./gradlew compileTestJava`
Expected: `completePayment(String)` 시그니처 불일치로 다수 컴파일 에러. 에러 메시지의 파일:라인을 그대로 목록으로 받는다.

- [ ] **Step 4: `PaymentServiceTest.java` 15곳 일괄 치환**

이 파일의 모든 호출은 검증 대상이 되는 비즈니스 로직(멱등/상태전이/금액비교 등)이지 trigger 값 자체가 아니므로, 전부 `PaymentHistoryTrigger.CLIENT_VERIFY`로 통일해도 테스트 의미가 바뀌지 않는다.

```bash
sed -i '' 's/paymentService\.completePayment(PAYMENT_ID)/paymentService.completePayment(PAYMENT_ID, PaymentHistoryTrigger.CLIENT_VERIFY)/g' \
  src/test/java/com/gongu/server/domain/payment/service/PaymentServiceTest.java
```

파일 상단에 `import com.gongu.server.domain.payment.domain.PaymentHistoryTrigger;` 추가. 또한 이 클래스가 `@InjectMocks PaymentService`를 쓴다면 `@Mock private PaymentHistoryRecorder paymentHistoryRecorder;` 필드를 추가한다 (`grep -n "@InjectMocks\|@Mock" src/test/java/com/gongu/server/domain/payment/service/PaymentServiceTest.java`로 기존 목록 확인 후 같은 스타일로 추가). `portOneClient.getPayment(...)`를 스텁하는 각 테스트에서 `PortOnePaymentResult`의 `rawBody()`가 무엇이든(빈 문자열도 무방) `PgResponseMasker`가 실제로 호출되지는 않는다 — `PaymentHistoryRecorder`가 mock이므로 내부 마스킹 로직은 이 테스트에서 실행되지 않는다.

- [ ] **Step 5: `PaymentCompleteBulkheadTest.java` 1곳 수정**

```bash
grep -n "completePayment(" src/test/java/com/gongu/server/domain/payment/service/PaymentCompleteBulkheadTest.java
```

`paymentService.completePayment("no-such-payment")`를 `paymentService.completePayment("no-such-payment", PaymentHistoryTrigger.CLIENT_VERIFY)`로 바꾸고 import를 추가한다. 이 테스트가 `@SpringBootTest` 계열이라면(벌크헤드 실제 동작 검증이므로 가능성 높음) `PaymentHistoryRecorder`는 실제 빈이 주입되므로 별도 mock 처리가 필요 없다 — 파일 상단의 `@SpringBootTest`/`@MockBean` 구성을 확인해 실제 빈 구조를 그대로 따른다.

- [ ] **Step 6: `PaymentControllerTest.java` 17곳 수정**

이 파일은 `MockMvc` + `@MockBean PaymentService`로 컨트롤러만 테스트한다. 각 테스트 메서드가 `.perform(post("/payments/verify")...)`를 쓰는지 `.perform(post("/payments/webhook")...)`를 쓰는지 먼저 확인한다:

```bash
grep -n "post(\"/payments" src/test/java/com/gongu/server/domain/payment/controller/PaymentControllerTest.java
```

- `/payments/verify` 관련 테스트: `given(paymentService.completePayment(anyString()))` → `given(paymentService.completePayment(anyString(), any(PaymentHistoryTrigger.class)))`, `verify(paymentService).completePayment("pay-uuid-001")` → `verify(paymentService).completePayment("pay-uuid-001", PaymentHistoryTrigger.CLIENT_VERIFY)`
- `/payments/webhook` 관련 테스트: 위와 동일하되 검증 값은 `PaymentHistoryTrigger.WEBHOOK`
- `verify(paymentService, never()).completePayment(anyString())` → `verify(paymentService, never()).completePayment(anyString(), any(PaymentHistoryTrigger.class))`

`import static org.mockito.ArgumentMatchers.any;`와 `import com.gongu.server.domain.payment.domain.PaymentHistoryTrigger;`를 추가한다.

- [ ] **Step 7: `PaymentSecurityTest.java` 1곳 수정**

같은 패턴으로 `given(paymentService.completePayment(anyString()))` → `given(paymentService.completePayment(anyString(), any(PaymentHistoryTrigger.class)))`.

- [ ] **Step 8: 컴파일 및 전체 결제 테스트 통과 확인**

Run: `./gradlew compileTestJava`
Expected: 컴파일 성공. 실패하면 에러가 가리키는 파일:라인을 위와 같은 패턴으로 계속 고친다 — 이 신호가 놓친 콜사이트를 전부 찾아주는 안전망이다.

Run: `./gradlew test --tests "com.gongu.server.domain.payment.*"`
Expected: 전부 PASS

- [ ] **Step 9: 통합 테스트 작성 — 최종 DB 상태 확인 (핵심 검증)**

`PaymentServiceTest`나 `PaymentCompleteBulkheadTest` 상단에서 `@SpringBootTest`로 실제 DB(H2 또는 테스트 MySQL, `src/test/resources/application.yml` 설정)를 쓰는 기존 통합 테스트 패턴을 확인하고 그대로 따른다. 예:

```java
package com.gongu.server.domain.payment.service;

import com.gongu.server.domain.order.entity.Order;
import com.gongu.server.domain.order.repository.OrderRepository;
import com.gongu.server.domain.payment.domain.Payment;
import com.gongu.server.domain.payment.domain.PaymentHistory;
import com.gongu.server.domain.payment.domain.PaymentHistoryTrigger;
import com.gongu.server.domain.payment.domain.PaymentStatus;
import com.gongu.server.domain.payment.repository.PaymentHistoryRepository;
import com.gongu.server.domain.payment.repository.PaymentRepository;
import com.gongu.server.global.infrastructure.portone.PortOneClient;
import com.gongu.server.global.infrastructure.portone.dto.PortOnePaymentResponse;
import com.gongu.server.global.infrastructure.portone.dto.PortOnePaymentResult;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.context.ActiveProfiles;

import java.time.OffsetDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;

@SpringBootTest
@ActiveProfiles("test")
class PaymentHistoryIntegrationTest {

    @Autowired private PaymentService paymentService;
    @Autowired private PaymentRepository paymentRepository;
    @Autowired private PaymentHistoryRepository paymentHistoryRepository;
    @Autowired private OrderRepository orderRepository;
    @MockBean private PortOneClient portOneClient;

    @Test
    void completePayment_확정되면_PAID_이력이_원문과_함께_DB에_남는다() {
        // given: 기존 PaymentServiceTest의 order/payment 생성 헬퍼를 그대로 재사용해
        // PENDING 상태의 Payment 1건을 실제로 저장해둔다 (paymentId, orderId 확보).
        // ... (헬퍼로 order, payment 저장)

        String rawJson = "{\"id\":\"pay-int-1\",\"status\":\"PAID\",\"amount\":{\"total\":10000},\"paidAt\":\"2026-01-01T00:00:00+09:00\"}";
        given(portOneClient.getPayment("pay-int-1")).willReturn(new PortOnePaymentResult(
                new PortOnePaymentResponse("pay-int-1", "PAID", new PortOnePaymentResponse.Amount(10000L), OffsetDateTime.parse("2026-01-01T00:00:00+09:00")),
                rawJson));

        paymentService.completePayment("pay-int-1", PaymentHistoryTrigger.CLIENT_VERIFY);

        Payment saved = paymentRepository.findByMerchantUid("pay-int-1").orElseThrow();
        assertThat(saved.getStatus()).isEqualTo(PaymentStatus.PAID);

        List<PaymentHistory> histories = paymentHistoryRepository.findByPaymentIdOrderByCreatedAtAsc(saved.getId());
        assertThat(histories).hasSize(1);
        assertThat(histories.get(0).getFromStatus()).isEqualTo(PaymentStatus.PENDING);
        assertThat(histories.get(0).getToStatus()).isEqualTo(PaymentStatus.PAID);
        assertThat(histories.get(0).getTrigger()).isEqualTo(PaymentHistoryTrigger.CLIENT_VERIFY);
        assertThat(histories.get(0).getPgRawResponse()).contains("\"status\":\"PAID\"");
    }
}
```

given/헬퍼 부분은 `PaymentServiceTest.java`의 실제 `@BeforeEach`/헬퍼 메서드(`user(...)`, `store(...)`, `order(...)`, `Payment.initiate(...)`)를 그대로 열어보고 동일하게 채운다 — 이 파일만 봐서는 정확한 시그니처를 알 수 없으므로 반드시 원본을 확인한다.

- [ ] **Step 10: 통합 테스트 통과 확인**

Run: `./gradlew test --tests "com.gongu.server.domain.payment.service.PaymentHistoryIntegrationTest"`
Expected: PASS — 이 테스트가 이슈 #209 완료기준 1번("결제 상태 변경이 빠짐없이 이력으로 남음")과 3번("PG 응답 원문이 마스킹 정책에 따라 보관됨")의 근거가 된다.

- [ ] **Step 11: 전체 회귀 테스트**

Run: `./gradlew test`
Expected: BUILD SUCCESSFUL, 0 failures

- [ ] **Step 12: 커밋 (논리 단위 2개로 분리)**

```bash
git add src/main/java/com/gongu/server/domain/payment/service/PaymentService.java \
        src/main/java/com/gongu/server/domain/payment/controller/PaymentController.java
git commit -m "feat: completePayment에 상태 이력 기록 연결 (#209)"

git add src/test/java/com/gongu/server/domain/payment/service/PaymentServiceTest.java \
        src/test/java/com/gongu/server/domain/payment/service/PaymentCompleteBulkheadTest.java \
        src/test/java/com/gongu/server/domain/payment/controller/PaymentControllerTest.java \
        src/test/java/com/gongu/server/domain/payment/controller/PaymentSecurityTest.java \
        src/test/java/com/gongu/server/domain/payment/service/PaymentHistoryIntegrationTest.java
git commit -m "test: completePayment 시그니처 변경에 따른 테스트 보정 및 이력 통합 테스트 추가 (#209)"
```

---

### Task 7: `PaymentExpireService.cancelExpiredPayment` 연결

**Files:**
- Modify: `src/main/java/com/gongu/server/domain/payment/service/PaymentExpireService.java`
- Modify: `src/test/java/com/gongu/server/domain/payment/service/PaymentExpireServiceTest.java`

**Interfaces:**
- Consumes: `PaymentHistoryRecorder.record(...)`(Task 5)

- [ ] **Step 1: 실패하는 테스트 추가**

`PaymentExpireServiceTest.java`에 `@Mock private PaymentHistoryRecorder paymentHistoryRecorder;` 필드를 추가하고, 기존 `cancelExpiredPayment_만료된_PENDING_Payment_취소...` 테스트 바로 아래에 새 테스트를 추가:

이 파일 하단(218행 부근)에 이미 `user(Long id)`/`store(Long id)`/`order(Long id, User user, long totalPrice)`/`payment(Order order)` private 헬퍼가 정의돼 있다. `order(...)`는 내부적으로 `Order.create(user, totalPrice)`를 호출하고, `Order.create()`는 항상 `RESERVED` 상태로 생성하며, `Order`는 애초에 `Store`를 참조하지 않는다 — 그래서 이 테스트엔 `store`/`product`도, 상태를 다시 세팅하는 코드도 필요 없다. 기존 헬퍼를 그대로 재사용한다:

```java
@Test
@DisplayName("만료_처리_시_PaymentHistory가_EXPIRY_SCHEDULER_트리거로_기록된다")
void cancelExpiredPayment_이력_기록() {
    LocalDateTime threshold = LocalDateTime.now().minusMinutes(10);
    User user = user(1L);
    Order order = order(1L, user, 10_000L);
    ReflectionTestUtils.setField(order, "createdAt", threshold.minusMinutes(1));
    Payment payment = payment(order);

    given(paymentRepository.findByIdWithLock(1L)).willReturn(Optional.of(payment));
    given(orderRepository.findByIdWithLock(1L)).willReturn(Optional.of(order));
    given(orderItemRepository.findAllByOrder(order)).willReturn(List.of());

    paymentExpireService.cancelExpiredPayment(1L, threshold);

    verify(paymentHistoryRecorder).record(payment, PaymentStatus.PENDING, PaymentStatus.CANCELLED,
            com.gongu.server.domain.payment.domain.PaymentHistoryTrigger.EXPIRY_SCHEDULER,
            "TTL 경과 - PG 미확인 취소", null);
}
```

- [ ] **Step 2: 컴파일 실패 확인**

Run: `./gradlew compileTestJava`
Expected: `PaymentHistoryRecorder` 관련 심볼 없음 또는 mock 없음 에러

- [ ] **Step 3: `PaymentExpireService` 수정**

```java
package com.gongu.server.domain.payment.service;

import com.gongu.server.domain.order.entity.Order;
import com.gongu.server.domain.order.entity.OrderItem;
import com.gongu.server.domain.order.entity.OrderStatus;
import com.gongu.server.domain.order.repository.OrderItemRepository;
import com.gongu.server.domain.order.repository.OrderRepository;
import com.gongu.server.domain.payment.domain.Payment;
import com.gongu.server.domain.payment.domain.PaymentHistoryTrigger;
import com.gongu.server.domain.payment.domain.PaymentStatus;
import com.gongu.server.domain.payment.repository.PaymentRepository;
import com.gongu.server.domain.product.service.StockRedisService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

@Service
@RequiredArgsConstructor
public class PaymentExpireService {

    private final PaymentRepository paymentRepository;
    private final OrderRepository orderRepository;
    private final OrderItemRepository orderItemRepository;
    private final StockRedisService stockRedisService;
    private final PaymentHistoryRecorder paymentHistoryRecorder;

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void cancelExpiredPayment(Long paymentId, LocalDateTime threshold) {
        Optional<Payment> optionalPayment = paymentRepository.findByIdWithLock(paymentId);
        if (optionalPayment.isEmpty()) {
            return;
        }

        Payment payment = optionalPayment.get();

        if (payment.getStatus() != PaymentStatus.PENDING) {
            return;
        }

        Optional<Order> optionalOrder = orderRepository.findByIdWithLock(payment.getOrder().getId());
        if (optionalOrder.isEmpty()) {
            return;
        }

        Order order = optionalOrder.get();

        if (order.getStatus() != OrderStatus.RESERVED) {
            return;
        }

        if (!order.getCreatedAt().isBefore(threshold)) {
            return;
        }

        List<OrderItem> items = orderItemRepository.findAllByOrder(order);

        PaymentStatus fromStatus = payment.getStatus();
        payment.expire();
        order.cancel("결제 시간 초과");
        paymentHistoryRecorder.record(payment, fromStatus, payment.getStatus(),
                PaymentHistoryTrigger.EXPIRY_SCHEDULER, "TTL 경과 - PG 미확인 취소", null);

        items.forEach(item ->
                stockRedisService.releaseStockAfterCommit(item.getProduct().getId(), Math.toIntExact(item.getQuantity()))
        );
    }
}
```

`reason`을 "TTL 경과 - PG 미확인 취소"로 명시한 이유: `#215`가 이 경로에 PG 확인을 추가하면 이 reason 문구와 호출 위치 자체가 바뀐다. `#215` 작업 시작 전 이 파일의 이 지점을 다시 확인하라고 `#215` 계획서에 남겨둔다.

- [ ] **Step 4: 테스트 통과 확인**

Run: `./gradlew test --tests "com.gongu.server.domain.payment.service.PaymentExpireServiceTest"`
Expected: PASS

- [ ] **Step 5: 커밋**

```bash
git add src/main/java/com/gongu/server/domain/payment/service/PaymentExpireService.java \
        src/test/java/com/gongu/server/domain/payment/service/PaymentExpireServiceTest.java
git commit -m "feat: 만료 취소에 상태 이력 기록 연결 (#209)"
```

---

### Task 8: 전체 회귀 + 마무리

**Files:**
- Modify: 없음 (검증 전용)

- [ ] **Step 1: 전체 테스트 스위트 실행**

Run: `./gradlew test`
Expected: BUILD SUCCESSFUL, 0 failures, jacoco 통과

- [ ] **Step 2: `./gradlew compileJava` 확인 (커밋 전 체크리스트)**

Run: `./gradlew compileJava`
Expected: BUILD SUCCESSFUL

- [ ] **Step 3: `docs/schema/ddl.sql` 대조 재확인**

`PaymentHistory.java`의 `@Table`/`@Column`/`@JoinColumn` 이름을 `docs/schema/ddl.sql`의 `payment_histories` 정의와 한 줄씩 대조한다 (github-rules.md 체크리스트 항목).

- [ ] **Step 4: 결제_장애_수렴_매트릭스.md 갱신**

`/Users/hankyungjun/projects/gongu/server/docs/adr/결제_장애_수렴_매트릭스.md`를 읽고, #209가 닫는 "I4 전 구간"과 "2-15" 행의 "현재" 열을 이번 PR 내용으로 갱신한다. "검증" 열은 `PaymentHistoryIntegrationTest`(Task 6) 테스트명을 근거로 채운다. 2-15는 Redis 해제 위치 문제도 함께 있는 행이므로 — `#188`이 이미 머지되어 `releaseStockAfterCommit`로 해결된 상태임을 반영해 그 부분 판정도 함께 갱신한다 (이번 PR이 그 부분을 직접 고친 건 아니므로, "#188에서 해결됨"이라고 근거를 구분해서 적는다).

- [ ] **Step 5: 최종 커밋**

```bash
git add docs/adr/결제_장애_수렴_매트릭스.md
git commit -m "docs: 결제 장애 매트릭스 #209 반영 (#209)"
```

- [ ] **Step 6: PR 생성 준비 보고**

여기서 멈추고 사용자에게 PR 생성 여부를 확인한다 (workflow.md의 리뷰 게이트 — Codex 대신 subagent-driven-development 리뷰를 먼저 거친 뒤).
