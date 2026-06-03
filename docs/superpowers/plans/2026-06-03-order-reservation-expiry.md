# Order 예약 만료 처리 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** RESERVED 상태 Order가 TTL(10분, 설정값) 초과 시 자동으로 CANCELLED 처리되고 재고가 복구된다

**Spec:** GitHub Issue #136

**Tech Stack:** Spring Boot 3.5, JPA/Hibernate, MySQL 8.0, Spring Scheduler

---

## 설계 요약 (구현자 필독)

- **만료 기준**: `created_at + TTL` 계산 — 별도 `expiredAt` 컬럼 없음
- **인덱스**: `(status, created_at)` 복합 인덱스로 만료 조회 쿼리 최적화
- **Scheduler 구조**: `OrderExpiryScheduler`(트랜잭션 없음) → `OrderExpireService.cancelExpiredOrder()`(`REQUIRES_NEW`)
  - 기존 `ProductStatusScheduler + ProductStatusTransactionHelper` 패턴과 동일
- **동시성**: `SELECT ... FOR UPDATE` + 락 획득 후 상태/만료 재검증(double-check)
  - Scheduler 취소 시도 중 Payment가 먼저 PAID 처리하면 재검증에서 조기 return
- **청크 처리**: 1회 실행 시 최대 100건, 각 건이 독립 트랜잭션이므로 하나 실패해도 나머지 계속

---

## File Map

| 파일 | 작업 |
|------|------|
| `src/main/java/com/gongu/server/domain/order/repository/OrderRepository.java` | Modify — 만료 ID 조회 쿼리 추가 |
| `docs/schema/ddl.sql` | Modify — 인덱스 DDL 추가 |
| `src/main/resources/application.yml` | Modify — TTL 설정값 추가 |
| `src/test/resources/application.yml` | Modify — TTL 설정값 추가 |
| `src/main/java/com/gongu/server/global/config/SchedulingConfig.java` | Modify — 스레드 풀 크기 설정 |
| `src/main/java/com/gongu/server/domain/order/service/OrderExpireService.java` | **Create** |
| `src/main/java/com/gongu/server/domain/order/scheduler/OrderExpiryScheduler.java` | **Create** |
| `src/test/java/com/gongu/server/domain/order/service/OrderExpireServiceTest.java` | **Create** |

**참조만 (수정 금지):**
- `src/main/java/com/gongu/server/domain/order/entity/Order.java` — `@Table` 수정 없음 (`@Index` 미사용, DDL이 SoT)
- `src/main/java/com/gongu/server/domain/product/scheduler/ProductStatusScheduler.java` — 패턴 참조
- `src/main/java/com/gongu/server/domain/product/scheduler/ProductStatusTransactionHelper.java` — 패턴 참조
- `src/main/java/com/gongu/server/domain/order/service/OrderService.java` — `cancelOrder` 재고 복구 패턴 참조
- `src/main/java/com/gongu/server/domain/order/entity/Order.java` — `cancel()` 메서드 확인
- `src/main/java/com/gongu/server/global/common/BaseEntity.java` — `createdAt` 필드명 확인

---

## Task 1: 인덱스 추가 + Repository 쿼리 + 설정값 + SchedulingConfig 스레드 풀

- [ ] 아래 단계를 순서대로 완료한다.

**참고 파일 (읽어야 할 것):**
- `src/main/java/com/gongu/server/domain/order/repository/OrderRepository.java` — 기존 쿼리 패턴 참조
- `docs/schema/ddl.sql` — 기존 인덱스 선언 스타일 확인 (`IDX_` 네이밍 컨벤션)
- `src/main/resources/application.yml` — 설정 파일 구조 확인
- `src/test/resources/application.yml` — 테스트 설정 파일 구조 확인
- `src/main/java/com/gongu/server/global/config/SchedulingConfig.java` — 현재 구조 확인

**수정 대상 파일:**
- Modify: `src/main/java/com/gongu/server/domain/order/repository/OrderRepository.java`
- Modify: `docs/schema/ddl.sql`
- Modify: `src/main/resources/application.yml`
- Modify: `src/test/resources/application.yml`
- Modify: `src/main/java/com/gongu/server/global/config/SchedulingConfig.java`

**금지 사항:**
- `Order.java` 수정 금지 — `@Index` 어노테이션 미사용. DDL 파일이 인덱스의 SoT
- `OrderService.java` 수정 금지 — Task 2에서 별도 서비스로 처리
- 기존 인덱스(`IDX_ORDERS_USER_ID`, `IDX_ORDERS_USER_STATUS`) 수정 금지

**구현 방향 (WHAT, not HOW):**

`OrderRepository.java`:
- 아래 쿼리 메서드 추가:
  ```java
  @Query("SELECT o.id FROM Order o WHERE o.status = 'RESERVED' AND o.createdAt < :threshold ORDER BY o.id")
  List<Long> findExpiredReservedOrderIds(@Param("threshold") LocalDateTime threshold, Pageable pageable);
  ```
  - `Pageable`은 `PageRequest.of(0, 100)` 형태로 호출측에서 전달 (Repository 자체는 Pageable 받음)
  - `LocalDateTime` import 추가

`docs/schema/ddl.sql`:
- 기존 인덱스 선언 블록 끝에 아래 추가:
  ```sql
  -- 만료된 RESERVED 주문 스캔 (OrderExpiryScheduler)
  CREATE INDEX `IDX_ORDERS_STATUS_CREATED_AT` ON `orders` (`status`, `created_at`);
  ```

`src/main/resources/application.yml`:
- 최상단 또는 기존 설정 블록 끝에 추가:
  ```yaml
  order:
    reservation-ttl-minutes: 10
  ```

`src/test/resources/application.yml`:
- 동일하게 추가:
  ```yaml
  order:
    reservation-ttl-minutes: 10
  ```

`src/main/java/com/gongu/server/global/config/SchedulingConfig.java`:
- `SchedulingConfigurer` 구현으로 변경
- `configureTasks(ScheduledTaskRegistrar)` 메서드에서 `ThreadPoolTaskScheduler` 생성:
  - `poolSize = 2` (현재 스케줄러 수: ProductStatusScheduler + OrderExpiryScheduler)
  - `threadNamePrefix = "scheduler-"`
  - `initialize()` 후 `taskRegistrar.setTaskScheduler(scheduler)` 설정

**검증:**
```bash
./gradlew compileJava
```
Expected: `BUILD SUCCESSFUL` (컴파일 오류 없음)

**커밋:**
```bash
git add src/main/java/com/gongu/server/domain/order/repository/OrderRepository.java \
        docs/schema/ddl.sql \
        src/main/resources/application.yml \
        src/test/resources/application.yml \
        src/main/java/com/gongu/server/global/config/SchedulingConfig.java
git commit -m "feat: orders 복합 인덱스 추가 및 만료 주문 조회 쿼리 추가 (#136)"
```

---

## Task 2: OrderExpireService 구현

- [ ] 아래 단계를 순서대로 완료한다.

**참고 파일 (읽어야 할 것):**
- `src/main/java/com/gongu/server/domain/order/service/OrderService.java` — `cancelOrder` 재고 복구 패턴 (product.id 오름차순 정렬 → 데드락 방지)
- `src/main/java/com/gongu/server/domain/order/repository/OrderRepository.java` — `findByIdWithLock` 시그니처 확인
- `src/main/java/com/gongu/server/domain/product/repository/ProductRepository.java` — `findByIdWithLock` 시그니처 확인
- `src/main/java/com/gongu/server/domain/order/entity/Order.java` — `cancel(String reason)` 시그니처, `getCreatedAt()` 경로 확인
- `src/main/java/com/gongu/server/domain/product/scheduler/ProductStatusTransactionHelper.java` — `REQUIRES_NEW` 전파 패턴 참조
- `docs/adr/예외_처리_전략.md` — 예외 처리 컨벤션 확인

**수정 대상 파일:**
- Create: `src/main/java/com/gongu/server/domain/order/service/OrderExpireService.java`

**금지 사항:**
- `OrderService.java` 수정 금지 — 기존 `cancelOrder`는 그대로 유지
- `Order.java`의 `cancel()` 메서드 수정 금지 — 기존 상태 검증 로직 활용

**구현 방향 (WHAT, not HOW):**

`OrderExpireService.java` (`src/main/java/com/gongu/server/domain/order/service/` 패키지):
- `@Service`, `@RequiredArgsConstructor`
- `cancelExpiredOrder(Long orderId, LocalDateTime threshold)` 메서드:
  - `@Transactional(propagation = Propagation.REQUIRES_NEW)` — 호출자 트랜잭션과 독립
  - `orderRepository.findByIdWithLock(orderId)` — PESSIMISTIC_WRITE 락 획득
  - **Double-check #1**: `order.getStatus() != OrderStatus.RESERVED` → `return` (이미 PAID/CANCELLED 처리됨)
  - **Double-check #2**: `!order.getCreatedAt().isBefore(threshold)` → `return` (아직 유효한 주문)
  - 재고 복구: `orderItemRepository.findAllByOrder(order)` → product.id 오름차순 정렬 → 각 item의 product에 `findByIdWithLock` + `restoreStock(quantity)` (데드락 방지를 위해 `cancelOrder`와 동일한 정렬 순서)
  - `order.cancel("결제 시간 초과")` 호출

**검증:**
```bash
./gradlew compileJava
```
Expected: `BUILD SUCCESSFUL`

**커밋:**
```bash
git add src/main/java/com/gongu/server/domain/order/service/OrderExpireService.java
git commit -m "feat: OrderExpireService 구현 (만료 주문 자동 취소) (#136)"
```

---

## Task 3: OrderExpiryScheduler 구현

- [ ] 아래 단계를 순서대로 완료한다.

**참고 파일 (읽어야 할 것):**
- `src/main/java/com/gongu/server/domain/product/scheduler/ProductStatusScheduler.java` — Scheduler 구조 및 예외 격리 패턴 참조
- `src/main/java/com/gongu/server/global/config/SchedulingConfig.java` — Task 1에서 스레드 풀 설정 완료된 상태 확인
- `src/main/resources/application.yml` — `order.reservation-ttl-minutes` 경로 확인

**수정 대상 파일:**
- Create: `src/main/java/com/gongu/server/domain/order/scheduler/OrderExpiryScheduler.java`

**금지 사항:**
- `SchedulingConfig.java` 수정 금지 — Task 1에서 이미 처리됨
- `OrderExpireService.java` 수정 금지

**구현 방향 (WHAT, not HOW):**

`OrderExpiryScheduler.java` (`src/main/java/com/gongu/server/domain/order/scheduler/` 패키지):
- `@Slf4j`, `@Component`, `@RequiredArgsConstructor`
- 필드:
  - `OrderExpireService orderExpireService`
  - `OrderRepository orderRepository`
  - `@Value("${order.reservation-ttl-minutes}") long reservationTtlMinutes`
- `expireReservedOrders()` 메서드:
  - `@Scheduled(fixedDelay = 60_000)` — 60초 간격 (이전 실행 완료 후 60초)
  - **트랜잭션 없음** — 개별 처리는 `OrderExpireService`가 담당
  - `threshold = LocalDateTime.now().minusMinutes(reservationTtlMinutes)`
  - `orderRepository.findExpiredReservedOrderIds(threshold, PageRequest.of(0, 100))` 로 후보 ID 조회
  - for-each로 각 id 처리:
    ```
    try {
        orderExpireService.cancelExpiredOrder(id, threshold)
    } catch (Exception e) {
        log.warn("만료 주문 취소 실패: orderId={}", id, e)
    }
    ```
  - 처리 건수 log.info 출력 (예: "만료 주문 처리 완료: {}건", count)

**검증:**
```bash
./gradlew compileJava
```
Expected: `BUILD SUCCESSFUL`

**커밋:**
```bash
git add src/main/java/com/gongu/server/domain/order/scheduler/OrderExpiryScheduler.java
git commit -m "feat: OrderExpiryScheduler 추가 (60초 주기 만료 주문 자동 취소) (#136)"
```

---

## Task 4: 테스트 작성

- [ ] 아래 단계를 순서대로 완료한다.

**참고 파일 (읽어야 할 것):**
- `src/test/java/com/gongu/server/domain/order/service/OrderServiceTest.java` — 기존 테스트 구조 및 픽스처 패턴 참조
- `src/main/java/com/gongu/server/domain/order/service/OrderExpireService.java` — 테스트 대상 메서드 시그니처 확인
- `src/main/java/com/gongu/server/domain/order/entity/Order.java` — 상태 검증 로직 이해
- `docs/adr/아키텍처_및_코드_컨벤션.md` — 테스트 컨벤션 확인

**수정 대상 파일:**
- Create: `src/test/java/com/gongu/server/domain/order/service/OrderExpireServiceTest.java`

**금지 사항:**
- `OrderServiceTest.java` 수정 금지
- `OrderExpireService.java` 수정 금지

**구현 방향 (WHAT, not HOW):**

`OrderExpireServiceTest.java` (`@ExtendWith(MockitoExtension.class)` 기반 단위 테스트):

아래 케이스를 모두 포함한다:

1. **만료된 RESERVED → 취소 + 재고 복구**
   - `createdAt`이 `threshold`보다 이전인 RESERVED Order
   - `order.cancel("결제 시간 초과")` 호출됨 검증
   - `product.restoreStock(quantity)` 호출됨 검증

2. **이미 PAID → skip (상태 재검증)**
   - `status = PAID`인 Order
   - `order.cancel()` 미호출 검증 (`verify(order, never()).cancel(...)`)

3. **아직 유효한 RESERVED → skip (시각 재검증)**
   - `createdAt`이 `threshold`보다 이후인 RESERVED Order
   - `order.cancel()` 미호출 검증

4. **Order 없음 → 조기 return (예외 없음)**
   - `orderRepository.findByIdWithLock()` → `Optional.empty()` 반환
   - 예외 발생 없이 정상 종료 검증

**검증:**
```bash
./gradlew test --tests "com.gongu.server.domain.order.service.OrderExpireServiceTest"
```
Expected: 4개 테스트 모두 PASS

이후 전체 테스트:
```bash
./gradlew test
```
Expected: `BUILD SUCCESSFUL`

**커밋:**
```bash
git add src/test/java/com/gongu/server/domain/order/service/OrderExpireServiceTest.java
git commit -m "test: OrderExpireService 단위 테스트 추가 (#136)"
```

---

PR 생성 후 CLAUDE.md 9~11단계(Codex 리뷰 위임 → 판정 → 반영) 따름
