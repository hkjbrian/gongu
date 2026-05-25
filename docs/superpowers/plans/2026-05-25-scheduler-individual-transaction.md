# 스케줄러 트랜잭션 경계 개선 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** `ProductStatusScheduler.activateUpcomingProducts()`의 단일 트랜잭션을 개별 `REQUIRES_NEW` 트랜잭션으로 분리하여 단일 상품 실패가 다른 상품 전이에 영향을 주지 않도록 한다.

**Issue:** #98

**Tech Stack:** Spring Boot 3.5, Java 25, JUnit 5 + Mockito

---

## 설계 결정 (논의 완료)

- **채택: 개별 트랜잭션 (`REQUIRES_NEW`)**
- Self-invocation 방지를 위해 별도 Helper 빈 도입
- `activateUpcomingProducts()`에서 `@Transactional` 제거 → Connection 이중 점유 방지
- 실패 상품은 `log.error`로 ID와 예외를 로깅하고 계속 진행
- 최대 처리 수 ~50개 이하 / 1분 주기 → N 트랜잭션 성능 부담 없음

---

## File Map

| 상태 | 파일 경로 |
|------|----------|
| **Create** | `src/main/java/com/gongu/server/domain/product/scheduler/ProductStatusTransactionHelper.java` |
| **Modify** | `src/main/java/com/gongu/server/domain/product/scheduler/ProductStatusScheduler.java` |
| **Modify** | `src/test/java/com/gongu/server/domain/product/scheduler/ProductStatusSchedulerTest.java` |
| **Reference** | `src/main/java/com/gongu/server/domain/product/entity/Product.java` — `activate()` 메서드 시그니처 확인 |
| **Reference** | `src/main/java/com/gongu/server/global/exception/BusinessException.java` — 예외 타입 확인 |
| **Reference** | `src/main/java/com/gongu/server/global/exception/errorcode/ProductErrorCode.java` — 에러코드 확인 |

---

## Task 1: `ProductStatusTransactionHelper` 생성

**참고 문서/파일:**
- `src/main/java/com/gongu/server/domain/product/entity/Product.java` — `activate()` 시그니처
- `src/main/java/com/gongu/server/global/exception/BusinessException.java` — catch 대상 타입

**수정 대상 파일:**
- Create: `src/main/java/com/gongu/server/domain/product/scheduler/ProductStatusTransactionHelper.java`

**금지 사항:**
- `ProductStatusScheduler.java` 수정 금지 (Task 2에서 처리)
- 기존 파일 일체 수정 금지

**구현 방향:**
- 패키지: `com.gongu.server.domain.product.scheduler`
- 클래스 어노테이션: `@Component`, `@RequiredArgsConstructor`
- 메서드 하나: `activateOne(Product product)`
  - `@Transactional(propagation = Propagation.REQUIRES_NEW)` 적용
  - 내부에서 `product.activate()` 호출
  - 예외를 catch하지 않는다 — 예외 전파를 스케줄러에서 처리

**검증:**
```bash
./gradlew compileJava
```
Expected: BUILD SUCCESSFUL

**커밋:**
```bash
git add src/main/java/com/gongu/server/domain/product/scheduler/ProductStatusTransactionHelper.java
git commit -m "feat: ProductStatusTransactionHelper REQUIRES_NEW 트랜잭션 헬퍼 추가 (#98)"
```

---

## Task 2: `ProductStatusScheduler` 수정

**참고 문서/파일:**
- `src/main/java/com/gongu/server/domain/product/scheduler/ProductStatusScheduler.java` — 현재 구조
- `src/main/java/com/gongu/server/domain/product/scheduler/ProductStatusTransactionHelper.java` — Task 1 결과물

**수정 대상 파일:**
- Modify: `src/main/java/com/gongu/server/domain/product/scheduler/ProductStatusScheduler.java`

**금지 사항:**
- `findActivatableUpcomingProducts()` 쿼리 로직 변경 금지
- `@Scheduled` cron 식 변경 금지

**구현 방향:**
- `ProductStatusTransactionHelper` 필드 주입 추가 (`@RequiredArgsConstructor` 활용)
- `activateUpcomingProducts()` 메서드에서 `@Transactional` 제거
- `forEach(Product::activate)` → 아래 루프로 교체:
  ```
  for (Product product : products) {
      try {
          helper.activateOne(product);
      } catch (Exception e) {
          log.error("상품 활성화 실패: id={}", product.getId(), e);
      }
  }
  ```
- 클래스에 `@Slf4j` (Lombok) 어노테이션 추가

**검증:**
```bash
./gradlew compileJava
```
Expected: BUILD SUCCESSFUL

**커밋:**
```bash
git add src/main/java/com/gongu/server/domain/product/scheduler/ProductStatusScheduler.java
git commit -m "fix: 스케줄러 단일 트랜잭션 → 상품별 REQUIRES_NEW 개별 트랜잭션 분리 (#98)"
```

---

## Task 3: `ProductStatusSchedulerTest` 수정

**참고 문서/파일:**
- `src/test/java/com/gongu/server/domain/product/scheduler/ProductStatusSchedulerTest.java` — 기존 테스트 패턴
- `src/main/java/com/gongu/server/domain/product/scheduler/ProductStatusScheduler.java` — 수정된 구조
- `src/main/java/com/gongu/server/domain/product/scheduler/ProductStatusTransactionHelper.java` — Mock 대상

**수정 대상 파일:**
- Modify: `src/test/java/com/gongu/server/domain/product/scheduler/ProductStatusSchedulerTest.java`

**금지 사항:**
- 기존 테스트 케이스 삭제 금지 (수정은 가능 — 스케줄러 의존성이 바뀌었으므로)

**구현 방향:**
- `@Mock ProductStatusTransactionHelper helper` 추가
- 기존 `@InjectMocks ProductStatusScheduler scheduler` 유지 — Helper도 자동 주입됨
- 기존 테스트 케이스 수정:
  - `forEach(Product::activate)` 대신 `helper.activateOne(product)`가 호출되므로, `helper.activateOne()` 호출 여부를 단언하거나 `doAnswer`로 실제 `product.activate()`를 실행하도록 stub 처리
  - 상태 단언(`product.getStatus() == ACTIVE`)은 유지하기 위해 stub에서 `product.activate()`를 직접 호출하는 방식 사용:
    ```
    doAnswer(inv -> { ((Product) inv.getArgument(0)).activate(); return null; })
        .when(helper).activateOne(any(Product.class));
    ```
- 신규 테스트 케이스 추가:
  - `activateUpcomingProducts_1개_실패_시_나머지_성공`: 3개 상품 중 두 번째 상품이 `activateOne()` 호출 시 `BusinessException` throw → 나머지 2개는 정상 전이 단언

**검증:**
```bash
./gradlew test --tests "com.gongu.server.domain.product.scheduler.*"
```
Expected: 모든 테스트 통과

```bash
./gradlew test
```
Expected: BUILD SUCCESSFUL (전체 테스트 통과)

**커밋:**
```bash
git add src/test/java/com/gongu/server/domain/product/scheduler/ProductStatusSchedulerTest.java
git commit -m "test: 스케줄러 개별 트랜잭션 단위 테스트 추가 (#98)"
```

---

## PR 생성 후

PR 생성 후 CLAUDE.md 9~11단계(Codex 리뷰 위임 → 판정 → 반영) 따름.
