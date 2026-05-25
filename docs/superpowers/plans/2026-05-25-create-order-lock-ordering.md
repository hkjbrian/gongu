# createOrder 쓰기 락 순서 최적화 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** `OrderService.createOrder()`에서 비회원 요청이 PESSIMISTIC_WRITE 락을 불필요하게 선점하지 않도록, 락 없는 조회 → 가입 여부 확인 → 락 포함 재조회 순서로 변경한다.

**Issue:** #104

**Tech Stack:** Spring Boot 3.5, Java 25, JUnit 5 + Mockito

---

## 설계 결정 (이슈에서 확정)

변경 전:
```
findByIdWithLock(productId)   ← 락 선점
existsByUserAndStore()        ← 가입 여부 확인
decreaseStock()
```

변경 후:
```
findById(productId)           ← 락 없는 존재 확인
existsByUserAndStore()        ← 가입 여부 확인
findByIdWithLock(productId)   ← 검증 통과 후에만 락 획득
decreaseStock()
```

---

## File Map

| 상태 | 파일 경로 |
|------|----------|
| **Modify** | `src/main/java/com/gongu/server/domain/order/service/OrderService.java` |
| **Modify** | `src/test/java/com/gongu/server/domain/order/service/OrderServiceTest.java` |
| **Reference** | `src/main/java/com/gongu/server/domain/product/repository/ProductRepository.java` — `findById` 상속 여부 확인 |

---

## Task 1: OrderService.createOrder() 락 순서 수정

**참고 파일:**
- `src/main/java/com/gongu/server/domain/order/service/OrderService.java` — createOrder() 현재 구조 (L47~69)
- `src/main/java/com/gongu/server/domain/product/repository/ProductRepository.java` — findById() 상속 확인

**수정 대상:** `src/main/java/com/gongu/server/domain/order/service/OrderService.java`

**금지 사항:**
- `createOrder()` 외 다른 메서드 수정 금지
- 에러코드(ProductErrorCode.PRODUCT_NOT_FOUND, OrderErrorCode.STORE_NOT_JOINED 등) 변경 금지

**구현 방향:**
`createOrder()` 내 아래 두 줄을:
```java
Product product = productRepository.findByIdWithLock(productId)
    .orElseThrow(() -> new BusinessException(ProductErrorCode.PRODUCT_NOT_FOUND));
```
다음으로 교체:
```java
Product product = productRepository.findById(productId)
    .orElseThrow(() -> new BusinessException(ProductErrorCode.PRODUCT_NOT_FOUND));
```

그리고 `existsByUserAndStore()` 검증 블록 **직후**, `product.decreaseStock(quantity)` **직전**에 아래를 삽입:
```java
entityManager.detach(product);  // ⚠️ JPA 1차 캐시 stale 방지 필수
product = productRepository.findByIdWithLock(productId)
    .orElseThrow(() -> new BusinessException(ProductErrorCode.PRODUCT_NOT_FOUND));
```

> **주의**: `findById`로 로드된 Product가 1차 캐시에 있으면 `findByIdWithLock`이 DB 락은 획득하지만
> 기존 인스턴스를 반환해 stale `remainingStock`으로 재고 차감이 실행될 수 있다.
> `entityManager.detach(product)` 호출로 1차 캐시에서 제거해야 `findByIdWithLock`이 DB에서 fresh load한다.
> `OrderService`에 `@PersistenceContext private EntityManager entityManager` 필드 추가 필요.

**검증:**
```bash
./gradlew compileJava
```
Expected: BUILD SUCCESSFUL

**커밋:**
```bash
git add src/main/java/com/gongu/server/domain/order/service/OrderService.java
git commit -m "feat: createOrder 비회원 조기 차단으로 쓰기 락 불필요 선점 방지 (#104)"
```

---

## Task 2: OrderServiceTest 업데이트

**참고 파일:**
- `src/test/java/com/gongu/server/domain/order/service/OrderServiceTest.java` — 기존 테스트 패턴 전체

**수정 대상:** `src/test/java/com/gongu/server/domain/order/service/OrderServiceTest.java`

**금지 사항:**
- 기존 테스트 케이스 삭제 금지

**구현 방향:**
`createOrder` 관련 테스트 케이스별 stub 변경:

1. `createOrder_정상_주문_생성` (정상 케이스):
   - `given(productRepository.findByIdWithLock(...))` → `given(productRepository.findById(...))` stub 유지
   - **추가**: `given(productRepository.findByIdWithLock(1L)).willReturn(Optional.of(product))` stub 추가 (2차 락 조회용)

2. `createOrder_존재하지_않는_상품_PRODUCT_NOT_FOUND_예외`:
   - `given(productRepository.findByIdWithLock(999L)).willReturn(Optional.empty())` → `given(productRepository.findById(999L)).willReturn(Optional.empty())` 로 변경

3. `createOrder_미가입_매장_STORE_NOT_JOINED_예외`:
   - `given(productRepository.findByIdWithLock(1L))...` → `given(productRepository.findById(1L))...` 로 변경
   - `findByIdWithLock` stub 불필요 (가입 검증 실패 후 락 조회 미도달)

4. `createOrder_미가입_매장_주문_시도_재고_차감_없음`:
   - 동일하게 `findById` stub으로 변경

**검증:**
```bash
./gradlew test --tests "com.gongu.server.domain.order.service.OrderServiceTest"
```
Expected: 모든 테스트 통과

```bash
./gradlew test
```
Expected: BUILD SUCCESSFUL

**커밋:**
```bash
git add src/test/java/com/gongu/server/domain/order/service/OrderServiceTest.java
git commit -m "test: createOrder 락 순서 변경에 따른 테스트 stub 업데이트 (#104)"
```

---

## PR 생성 후

PR 생성 후 CLAUDE.md 9~11단계(Codex 리뷰 위임 → 판정 → 반영) 따름.
