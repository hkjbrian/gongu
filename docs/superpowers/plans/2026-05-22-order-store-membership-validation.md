# createOrder 매장 가입 여부 검증 구현 계획

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** `OrderService.createOrder` 호출 시 주문자가 해당 상품의 매장에 가입되어 있는지 검증하여, 미가입 매장 주문을 차단한다.

**Issue:** #82

**Tech Stack:** Spring Boot 3.5, Java 25, JUnit 5 + Mockito

---

## 정책 결정 (논의 완료)

- 주문자는 자신이 가입한 매장의 상품만 주문 가능하다.
- 미가입 매장 상품 주문 시 `ORDER_008 / 403 Forbidden` 반환.
- `product.getStore()` lazy load로 인한 추가 쿼리(N+1)는 **별도 이슈로 분리**하며 이번 범위에서 제외.

---

## File Map

| 상태 | 파일 경로 |
|------|----------|
| **Modify** | `src/main/java/com/gongu/server/global/exception/errorcode/OrderErrorCode.java` |
| **Modify** | `src/main/java/com/gongu/server/domain/order/service/OrderService.java` |
| **Modify** | `src/test/java/com/gongu/server/domain/order/service/OrderServiceTest.java` |
| **Reference** | `src/main/java/com/gongu/server/domain/store/repository/UserStoreRepository.java` |
| **Reference** | `src/main/java/com/gongu/server/domain/product/entity/Product.java` |
| **Reference** | `docs/adr/아키텍처_및_코드_컨벤션.md` |

---

## Task 1: OrderErrorCode에 STORE_NOT_JOINED 추가

**참고 문서/파일:**
- `src/main/java/com/gongu/server/global/exception/errorcode/OrderErrorCode.java` — 기존 코드 번호·패턴 파악

**수정 대상 파일:**
- Modify: `src/main/java/com/gongu/server/global/exception/errorcode/OrderErrorCode.java`

**금지 사항:**
- 기존 `ORDER_001`~`ORDER_007` 항목 변경 금지

**구현 방향:**
- `ORDER_007` 아래에 아래 항목 추가:
  ```java
  STORE_NOT_JOINED("ORDER_008", "가입하지 않은 매장의 상품은 주문할 수 없습니다", 403);
  ```

**검증:**
```bash
./gradlew compileJava
```
Expected: BUILD SUCCESSFUL

**커밋:**
```bash
git add src/main/java/com/gongu/server/global/exception/errorcode/OrderErrorCode.java
git commit -m "feat: OrderErrorCode에 STORE_NOT_JOINED(ORDER_008) 추가 (#82)"
```

---

## Task 2: OrderService.createOrder에 매장 가입 여부 검증 추가

**참고 문서/파일:**
- `src/main/java/com/gongu/server/domain/order/service/OrderService.java` — 현재 createOrder 구조(L42~59)
- `src/main/java/com/gongu/server/domain/store/repository/UserStoreRepository.java` — `existsByUserAndStore(User, Store): boolean` 메서드
- `src/main/java/com/gongu/server/domain/product/entity/Product.java` — `getStore()` 확인
- `docs/adr/아키텍처_및_코드_컨벤션.md` — 서비스 레이어 패턴

**수정 대상 파일:**
- Modify: `src/main/java/com/gongu/server/domain/order/service/OrderService.java`

**금지 사항:**
- `createOrder` 외 다른 메서드 변경 금지
- `findByIdWithLock` 쿼리 변경 금지 (N+1 개선은 별도 이슈)

**구현 방향:**
- `OrderService` 필드에 `UserStoreRepository userStoreRepository` 주입 추가 (`@RequiredArgsConstructor` 활용)
- `createOrder` 메서드에서 `product.decreaseStock()` 호출 **이전**에 아래 검증 삽입:
  ```
  Store store = product.getStore();
  if (!userStoreRepository.existsByUserAndStore(user, store)) {
      throw new BusinessException(OrderErrorCode.STORE_NOT_JOINED);
  }
  ```
- 검증 위치: `product` 조회 직후, `product.decreaseStock(quantity)` 직전

**검증:**
```bash
./gradlew compileJava
```
Expected: BUILD SUCCESSFUL

**커밋:**
```bash
git add src/main/java/com/gongu/server/domain/order/service/OrderService.java
git commit -m "feat: createOrder 매장 가입 여부 검증 추가 (#82)"
```

---

## Task 3: OrderServiceTest 업데이트

**참고 문서/파일:**
- `src/test/java/com/gongu/server/domain/order/service/OrderServiceTest.java` — 기존 테스트 패턴(픽스처 메서드, Mockito 스타일)
- `src/main/java/com/gongu/server/domain/store/repository/UserStoreRepository.java`

**수정 대상 파일:**
- Modify: `src/test/java/com/gongu/server/domain/order/service/OrderServiceTest.java`

**금지 사항:**
- 기존 테스트 케이스 삭제·변경 금지 (기존 테스트가 여전히 통과해야 함)

**구현 방향:**

1. **Mock 필드 추가** (`storeAdminRepository` Mock 아래):
   ```java
   @Mock
   private UserStoreRepository userStoreRepository;
   ```

2. **기존 `createOrder_정상_주문_생성` 테스트 수정**:
   - `product` 픽스처는 `product(1L, store(1L), 10)` 형태로 Store가 세팅된 상품 사용
   - given 블록에 아래 stubbing 추가:
     ```java
     given(userStoreRepository.existsByUserAndStore(any(User.class), any(Store.class))).willReturn(true);
     ```

3. **신규 테스트 케이스 2건 추가** (`createOrder_존재하지_않는_상품_PRODUCT_NOT_FOUND_예외` 아래):

   ```java
   @Test
   @DisplayName("createOrder_미가입_매장_STORE_NOT_JOINED_예외")
   void createOrder_미가입_매장_STORE_NOT_JOINED_예외() {
       // given
       User user = user(1L);
       Product product = product(1L, store(1L), 10);

       given(userRepository.findByIdAndDeletedAtIsNull(1L)).willReturn(Optional.of(user));
       given(productRepository.findByIdWithLock(1L)).willReturn(Optional.of(product));
       given(userStoreRepository.existsByUserAndStore(any(User.class), any(Store.class))).willReturn(false);

       // when & then
       assertThatThrownBy(() -> orderService.createOrder(1L, 1L, 1))
               .isInstanceOf(BusinessException.class)
               .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode())
                       .isEqualTo(OrderErrorCode.STORE_NOT_JOINED));
   }
   ```

   ```java
   @Test
   @DisplayName("createOrder_미가입_매장_주문_시도_재고_차감_없음")
   void createOrder_미가입_매장_주문_시도_재고_차감_없음() {
       // given
       User user = user(1L);
       Product product = product(1L, store(1L), 10);

       given(userRepository.findByIdAndDeletedAtIsNull(1L)).willReturn(Optional.of(user));
       given(productRepository.findByIdWithLock(1L)).willReturn(Optional.of(product));
       given(userStoreRepository.existsByUserAndStore(any(User.class), any(Store.class))).willReturn(false);

       // when
       assertThatThrownBy(() -> orderService.createOrder(1L, 1L, 1))
               .isInstanceOf(BusinessException.class);

       // then
       assertThat(product.getRemainingStock()).isEqualTo(10); // 재고 차감 없음
   }
   ```

**검증:**
```bash
./gradlew test --tests "com.gongu.server.domain.order.service.OrderServiceTest"
```
Expected: 기존 포함 전체 테스트 PASS

**커밋:**
```bash
git add src/test/java/com/gongu/server/domain/order/service/OrderServiceTest.java
git commit -m "test: createOrder 매장 가입 여부 검증 테스트 추가 (#82)"
```

---

## 완료 후

PR 생성 후 CLAUDE.md 9~11단계(Codex 리뷰 위임 → 판정 → 반영) 따름.
