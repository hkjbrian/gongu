# Redis Stock Crash Recovery Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** createOrder DB 예외 시 Redis 보상 처리 + 앱 크래시 후 재시작 시 Redis 재고 자동 복구

**Spec:** GitHub Issue #160 및 이슈 코멘트 (설계 논의 결과 정리)

**Tech Stack:** Spring Boot 3.5, Java 25, Redis (StringRedisTemplate), JPA

---

## 배경 및 결정 사항

- **Problem 1**: `createOrder`에서 Redis DECR 성공 후 DB 예외 발생 시 Redis 감소분이 복구되지 않음
- **Problem 2**: 앱 프로세스 크래시 시 try-catch 보상 코드 자체가 실행 불가. DB 트랜잭션은 자동 롤백되지만 Redis는 감소 상태로 남음

**해결 방향:**
- Problem 1 → `createOrder` try-catch 보상 (`releaseStock` 후 re-throw)
- Problem 2 → startup sync: 앱 재시작 시 `remainingStock - SUM(RESERVED 수량)`으로 Redis 재계산
- 안전망 → 재조정 Job: 주기적으로 불일치 감지 및 보정

**올바른 Redis 재고 계산 공식:**
```
Redis stock = product.remainingStock - SUM(orderItem.quantity WHERE order.status = RESERVED)
```
- `remainingStock`: 결제 확정 시점에 차감되는 DB 재고 (source of truth)
- `RESERVED`: 주문 생성 후 아직 결제되지 않은 상태
- PAID는 이미 `remainingStock`에서 차감됐으므로 이중 계산하지 않음

---

## 파일 맵

### 신규 생성
| 파일 | 역할 |
|---|---|
| `src/main/java/com/gongu/server/global/config/StockSyncRunner.java` | 앱 시작 시 Redis 재고 동기화 (ApplicationRunner) |
| `src/main/java/com/gongu/server/domain/product/scheduler/StockReconciliationScheduler.java` | 주기적 재고 재조정 스케줄러 |
| `src/main/java/com/gongu/server/domain/product/scheduler/StockReconciliationHelper.java` | 재조정 Job 트랜잭션 헬퍼 (REQUIRES_NEW) |
| `src/test/java/com/gongu/server/global/config/StockSyncRunnerTest.java` | StockSyncRunner 단위 테스트 |
| `src/test/java/com/gongu/server/domain/product/scheduler/StockReconciliationSchedulerTest.java` | 재조정 스케줄러 단위 테스트 |

### 수정
| 파일 | 변경 내용 |
|---|---|
| `src/main/java/com/gongu/server/domain/order/service/OrderService.java` | createOrder try-catch 보상 추가 |
| `src/main/java/com/gongu/server/domain/product/repository/ProductRepository.java` | `findAllByStatus` 쿼리 추가 |
| `src/main/java/com/gongu/server/domain/order/repository/OrderItemRepository.java` | `sumReservedQuantityByProductId` 쿼리 추가 |
| `src/test/java/com/gongu/server/domain/order/service/OrderServiceTest.java` | DB 예외 시 보상 테스트 케이스 추가 |

### 참고만 (수정 금지)
| 파일 | 참고 이유 |
|---|---|
| `src/main/java/com/gongu/server/domain/product/service/StockRedisService.java` | `reserveStock`, `releaseStock`, `initializeStock` 시그니처 확인 |
| `src/main/java/com/gongu/server/domain/product/scheduler/ProductStatusScheduler.java` | 스케줄러 + TransactionHelper 패턴 참고 |
| `src/main/java/com/gongu/server/domain/product/scheduler/ProductStatusTransactionHelper.java` | REQUIRES_NEW 트랜잭션 분리 패턴 참고 |
| `src/main/java/com/gongu/server/domain/order/service/OrderExpireService.java` | REQUIRES_NEW 패턴 참고 |
| `src/main/java/com/gongu/server/domain/order/entity/OrderStatus.java` | RESERVED 상태값 확인 |

---

## Task 1: createOrder DB 예외 시 Redis 보상

**참고 파일:**
- `src/main/java/com/gongu/server/domain/order/service/OrderService.java` — 현재 createOrder 구조
- `src/main/java/com/gongu/server/domain/product/service/StockRedisService.java` — releaseStock 시그니처

**수정 대상:**
- Modify: `src/main/java/com/gongu/server/domain/order/service/OrderService.java`

**금지 사항:**
- `createOrder` 외 다른 메서드 변경 금지
- `StockRedisService` 내부 수정 금지

**구현 방향:**
- `createOrder` 내 `reserveStock` 호출 이후 DB 작업(`orderRepository.save`, `orderItemRepository.save`) 전체를 try-catch로 감싼다
- catch 블록에서: `stockRedisService.releaseStock(productId, quantity)` 호출 후 원래 예외를 re-throw
- `reserveStock` 자체의 예외(재고 부족, 키 없음)는 catch 대상이 아님 — `reserveStock` 호출 이전에 예외가 터지므로 catch 블록 밖에 위치
- catch 대상 예외 타입: `Exception` (DB 예외는 `DataAccessException` 계열이지만 모든 런타임 예외 포함)
- log.error로 보상 발생 사실 기록: `"createOrder 보상 실행: productId={}, quantity={}"` 형태

**검증:**
```bash
./gradlew test --tests "*.OrderServiceTest"
```
Expected: 기존 테스트 전체 통과

---

## Task 2: Repository 쿼리 추가

**참고 파일:**
- `src/main/java/com/gongu/server/domain/product/repository/ProductRepository.java` — 기존 쿼리 패턴
- `src/main/java/com/gongu/server/domain/order/repository/OrderItemRepository.java` — 기존 쿼리 패턴
- `src/main/java/com/gongu/server/domain/order/entity/OrderStatus.java` — RESERVED 확인

**수정 대상:**
- Modify: `src/main/java/com/gongu/server/domain/product/repository/ProductRepository.java`
- Modify: `src/main/java/com/gongu/server/domain/order/repository/OrderItemRepository.java`

**금지 사항:**
- 기존 쿼리 메서드 수정 금지

**구현 방향 — ProductRepository:**
- Spring Data JPA 파생 메서드로 추가: `List<Product> findAllByStatus(ProductStatus status)`
- ACTIVE 상품 전체 조회에 사용됨 (startup sync + 재조정 Job)

**구현 방향 — OrderItemRepository:**
- 다음 JPQL 쿼리 추가:
  ```java
  @Query("SELECT SUM(oi.quantity) FROM OrderItem oi WHERE oi.product.id = :productId AND oi.order.status = :status")
  Long sumQuantityByProductIdAndOrderStatus(@Param("productId") Long productId, @Param("status") OrderStatus status);
  ```
- 반환 타입 `Long` (주문 없으면 null 반환 → 호출부에서 null-safe 처리 필요)

**검증:**
```bash
./gradlew compileJava
```
Expected: 컴파일 오류 없음

---

## Task 3: StockSyncRunner (startup sync)

**참고 파일:**
- `src/main/java/com/gongu/server/domain/product/entity/ProductStatus.java` — ACTIVE 상태값
- `src/main/java/com/gongu/server/domain/product/service/StockRedisService.java` — initializeStock 시그니처
- `src/main/java/com/gongu/server/domain/order/entity/OrderStatus.java` — RESERVED 상태값

**수정 대상:**
- Create: `src/main/java/com/gongu/server/global/config/StockSyncRunner.java`

**금지 사항:**
- `StockRedisService`, `ProductRepository`, `OrderItemRepository` 수정 금지 (Task 2 결과를 읽기만 함)

**구현 방향:**
- `ApplicationRunner` 인터페이스 구현, `@Component` 등록
- `@Transactional(readOnly = true)` 적용
- `run()` 메서드:
  1. `productRepository.findAllByStatus(ProductStatus.ACTIVE)`로 ACTIVE 상품 전체 조회
  2. 각 product에 대해:
     - `reserved = orderItemRepository.sumQuantityByProductIdAndOrderStatus(product.getId(), OrderStatus.RESERVED)` (null이면 0)
     - `correctStock = product.getRemainingStock() - reserved`
     - `stockRedisService.initializeStock(product.getId(), correctStock)`
  3. log.info: `"startup sync 완료: {} 개 상품 Redis 재고 재계산"` (처리 건수 기록)
  4. 개별 상품 처리 실패 시 log.error 후 continue (전체 실패 방지)
- `@RequiredArgsConstructor`, `@Slf4j` 적용

**검증:**
```bash
./gradlew test --tests "*.StockSyncRunnerTest"
```
Expected: 테스트 통과

---

## Task 4: StockSyncRunner 단위 테스트

**참고 파일:**
- `src/test/java/com/gongu/server/domain/product/service/StockRedisServiceTest.java` — Mock 테스트 패턴
- `src/test/java/com/gongu/server/domain/order/service/OrderServiceTest.java` — 픽스처 패턴 참고

**수정 대상:**
- Create: `src/test/java/com/gongu/server/global/config/StockSyncRunnerTest.java`

**구현 방향 (테스트 케이스):**

| 테스트명 | 시나리오 | 검증 |
|---|---|---|
| `startup_sync_ACTIVE_상품_Redis_정상_세팅` | ACTIVE 상품 2개, 각각 RESERVED 수량 있음 | `initializeStock` 각각 올바른 값으로 호출됨 |
| `startup_sync_RESERVED_주문_없는_경우_remainingStock_그대로` | RESERVED 주문 없음 (sumQuantity = null) | `initializeStock(productId, remainingStock)` 호출됨 |
| `startup_sync_ACTIVE_상품_없으면_아무것도_안함` | ACTIVE 상품 없음 | `initializeStock` 호출 안 됨 |
| `startup_sync_단건_실패해도_나머지_처리_계속` | 2개 상품 중 1개에서 initializeStock 예외 | 나머지 1개는 정상 처리됨 |

- `@ExtendWith(MockitoExtension.class)` 사용
- Mock: `ProductRepository`, `OrderItemRepository`, `StockRedisService`

**검증:**
```bash
./gradlew test --tests "*.StockSyncRunnerTest"
```
Expected: 4개 테스트 통과

---

## Task 5: StockReconciliationHelper + StockReconciliationScheduler

**참고 파일:**
- `src/main/java/com/gongu/server/domain/product/scheduler/ProductStatusScheduler.java` — 스케줄러 구조
- `src/main/java/com/gongu/server/domain/product/scheduler/ProductStatusTransactionHelper.java` — REQUIRES_NEW 헬퍼 패턴
- `src/main/java/com/gongu/server/domain/product/service/StockRedisService.java` — releaseStock 시그니처

**수정 대상:**
- Create: `src/main/java/com/gongu/server/domain/product/scheduler/StockReconciliationHelper.java`
- Create: `src/main/java/com/gongu/server/domain/product/scheduler/StockReconciliationScheduler.java`

**금지 사항:**
- `ProductStatusScheduler`, `ProductStatusTransactionHelper` 수정 금지

**구현 방향 — StockReconciliationHelper:**
- `@Component`, `@Slf4j`, `@RequiredArgsConstructor`
- `reconcileOne(Long productId)` 메서드, `@Transactional(propagation = REQUIRES_NEW)`
  1. `productRepository.findById(productId)` — 없으면 return
  2. `reserved = orderItemRepository.sumQuantityByProductIdAndOrderStatus(productId, RESERVED)` (null → 0)
  3. `correctStock = product.getRemainingStock() - reserved`
  4. `currentStock = stockRedisService.getCurrentStock(productId)` — **주의: StockRedisService에 `getCurrentStock` 메서드 추가 필요**
  5. `currentStock`이 null (키 없음): `stockRedisService.initializeStock(productId, correctStock)` 후 log.warn
  6. `currentStock < correctStock`: `stockRedisService.releaseStock(productId, correctStock - currentStock)` 후 log.warn
  7. `currentStock == correctStock`: 정상, 아무것도 안 함
  8. `currentStock > correctStock`: log.warn만 (DECR 하지 않음 — 과다보정 방지)

**구현 방향 — StockReconciliationScheduler:**
- `@Component`, `@Slf4j`, `@RequiredArgsConstructor`
- `@Scheduled(cron = "0 */5 * * * *")` (5분마다)
- `reconcile()` 메서드:
  1. `productRepository.findAllByStatus(ACTIVE)` 조회
  2. 각 product에 대해 `reconciliationHelper.reconcileOne(product.getId())` 호출
  3. 예외 발생 시 log.error 후 continue

**StockRedisService 수정 (추가 메서드):**
- Modify: `src/main/java/com/gongu/server/domain/product/service/StockRedisService.java`
- `getCurrentStock(Long productId)` 메서드 추가:
  ```java
  public Long getCurrentStock(Long productId) {
      String value = stringRedisTemplate.opsForValue().get(stockKey(productId));
      return value == null ? null : Long.parseLong(value);
  }
  ```

**검증:**
```bash
./gradlew compileJava
```
Expected: 컴파일 오류 없음

---

## Task 6: 재조정 스케줄러 단위 테스트 + OrderServiceTest 보완

**참고 파일:**
- `src/test/java/com/gongu/server/domain/product/scheduler/ProductStatusSchedulerTest.java` — 스케줄러 테스트 패턴
- `src/test/java/com/gongu/server/domain/order/service/OrderServiceTest.java` — 기존 테스트, 픽스처 패턴

**수정 대상:**
- Create: `src/test/java/com/gongu/server/domain/product/scheduler/StockReconciliationSchedulerTest.java`
- Modify: `src/test/java/com/gongu/server/domain/order/service/OrderServiceTest.java`

**구현 방향 — StockReconciliationSchedulerTest:**

| 테스트명 | 시나리오 | 검증 |
|---|---|---|
| `reconcileOne_Redis값_정상_일치` | currentStock == correctStock | releaseStock, initializeStock 호출 없음 |
| `reconcileOne_Redis_낮음_INCR_보정` | currentStock < correctStock | `releaseStock(productId, 차이값)` 호출 |
| `reconcileOne_Redis_키_없음_초기화` | currentStock == null | `initializeStock(productId, correctStock)` 호출 |
| `reconcileOne_Redis_높음_DECR_안함` | currentStock > correctStock | releaseStock 호출 없음, log.warn만 |
| `reconcileOne_상품_없으면_아무것도_안함` | product 없음 | releaseStock, initializeStock 호출 없음 |
| `reconcile_스케줄러_전체_상품_순회` | ACTIVE 상품 2개 | reconcileOne 2회 호출 |
| `reconcile_단건_실패해도_나머지_처리` | 1개 예외 | 나머지 1개 reconcileOne 호출됨 |

**구현 방향 — OrderServiceTest 추가 케이스:**

| 테스트명 | 시나리오 | 검증 |
|---|---|---|
| `createOrder_DB저장_예외_시_Redis_재고_복원` | `orderRepository.save` 에서 RuntimeException | `releaseStock(productId, quantity)` 호출됨, 예외 re-throw |
| `createOrder_DB예외_보상_후_예외_재전파` | 위와 동일 | 원래 RuntimeException이 caller까지 전파됨 |

**검증:**
```bash
./gradlew test
```
Expected: 전체 테스트 통과

---

## 커밋 단위

```bash
# Task 1
git add src/main/java/com/gongu/server/domain/order/service/OrderService.java
git commit -m "fix: createOrder Redis 보상 처리 추가 — DB 예외 시 releaseStock (#160)"

# Task 2
git add src/main/java/com/gongu/server/domain/product/repository/ProductRepository.java \
        src/main/java/com/gongu/server/domain/order/repository/OrderItemRepository.java
git commit -m "feat: 재고 동기화용 Repository 쿼리 추가 (#160)"

# Task 3 + 4
git add src/main/java/com/gongu/server/global/config/StockSyncRunner.java \
        src/test/java/com/gongu/server/global/config/StockSyncRunnerTest.java
git commit -m "feat: 앱 시작 시 Redis 재고 startup sync 추가 (#160)"

# Task 5 + 6
git add src/main/java/com/gongu/server/domain/product/service/StockRedisService.java \
        src/main/java/com/gongu/server/domain/product/scheduler/StockReconciliationHelper.java \
        src/main/java/com/gongu/server/domain/product/scheduler/StockReconciliationScheduler.java \
        src/test/java/com/gongu/server/domain/product/scheduler/StockReconciliationSchedulerTest.java \
        src/test/java/com/gongu/server/domain/order/service/OrderServiceTest.java
git commit -m "feat: Redis 재고 재조정 스케줄러 및 테스트 추가 (#160)"
```

---

## PR 생성 후

CLAUDE.md 9~11단계 (Codex 리뷰 위임 → 판정 → 반영) 따름
