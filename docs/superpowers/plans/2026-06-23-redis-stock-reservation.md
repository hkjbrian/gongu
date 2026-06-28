# Redis 재고 예약 계층 (A안) 구현 계획

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Redis DECR/INCR를 이용한 재고 예약 계층을 도입해 비관적 락(SELECT FOR UPDATE) 병목을 제거하고 주문 생성 TPS를 향상시킨다

**Spec:** GitHub 이슈 hkjbrian/gongu #144

**Tech Stack:** Spring Boot 3.5, Java 25, Redis (StringRedisTemplate), MySQL 8.0

---

## 변경 파일 맵

### 신규 생성
| 파일 | 역할 |
|------|------|
| `src/main/java/com/gongu/server/domain/product/service/StockRedisService.java` | Redis 재고 연산 캡슐화 |
| `src/test/java/com/gongu/server/domain/product/service/StockRedisServiceTest.java` | StockRedisService 단위 테스트 |

### 수정
| 파일 | 변경 내용 요약 |
|------|--------------|
| `src/main/java/com/gongu/server/domain/product/entity/Product.java` | `confirmStock(int quantity)` 메서드 추가 |
| `src/main/java/com/gongu/server/domain/product/service/ProductService.java` | `createProduct` 내 Redis 재고 초기화 추가 |
| `src/main/java/com/gongu/server/domain/order/service/OrderService.java` | `createOrder` SELECT FOR UPDATE 제거 → Redis DECR, `cancelOrder` restoreStock → Redis INCR |
| `src/main/java/com/gongu/server/domain/order/service/OrderExpireService.java` | `cancelExpiredOrder` restoreStock → Redis INCR |
| `src/main/java/com/gongu/server/domain/payment/service/PaymentService.java` | `completePayment` 결제 확정 시 MySQL remaining_stock 차감 추가 |
| `src/test/java/com/gongu/server/domain/order/service/OrderServiceTest.java` | StockRedisService 목 반영 |
| `src/test/java/com/gongu/server/domain/order/service/OrderExpireServiceTest.java` | StockRedisService 목 반영 |

### 참조 전용 (수정 금지)
- `src/main/java/com/gongu/server/global/security/jwt/RefreshTokenStore.java` — StringRedisTemplate 사용 패턴
- `src/main/java/com/gongu/server/domain/order/entity/Order.java` — cancel() 상태 조건 확인
- `src/main/java/com/gongu/server/domain/order/entity/OrderItem.java` — 필드 구조 확인
- `src/main/java/com/gongu/server/global/exception/errorcode/ProductErrorCode.java` — 기존 에러코드 확인
- `docs/schema/ddl.sql` — products 테이블 컬럼명 확인

### 수정 금지 (이번 범위 밖)
- `src/main/java/com/gongu/server/global/config/MetricsConfig.java` — 기존 메트릭 빈 유지
- `src/main/java/com/gongu/server/domain/product/service/ProductService.java` 내 `decreaseStock()` 메서드 — 현행 비관적 락 경로 유지 (대조군 보존)
- `src/main/java/com/gongu/server/domain/product/scheduler/ProductStatusTransactionHelper.java` — 스케줄러 활성화 로직 수정 없음 (Redis 초기화는 createProduct 시점에만)

---

## 설계 전제 (구현자 필독)

### Redis 키 구조
```
stock:product:{productId}   # String, Long 값, TTL 없음
```

### 재고 상태 분리
| 계층 | 저장소 | 의미 | 갱신 시점 |
|------|--------|------|---------|
| available_stock | Redis | 주문 가능한 재고 | createOrder(DECR), cancelOrder(INCR), expireOrder(INCR) |
| remaining_stock (MySQL) | DB | 결제 확정된 재고 차감 누계 | completePayment |

초기값: `available_stock = Redis = totalStock`, `remaining_stock = MySQL = totalStock`

### SOLD_OUT 처리
결제 확정 시 MySQL `remaining_stock`이 0이 되면 `product.soldOut()` 호출.  
Redis available_stock이 0이 되는 시점에는 MySQL 상태 변경 없음 (실험 범위 외).

### 취소 대상 범위
`order.cancel()`은 `status == RESERVED`인 경우만 허용 (Order.java 확인).  
따라서 취소 시 MySQL `remaining_stock` 복원 불필요 — Redis INCR만 수행.

---

## Task 1: StockRedisService 생성

- [ ] `StockRedisService` 구현
- [ ] `StockRedisServiceTest` 작성

**참고 문서/파일 (읽어야 할 것):**
- `src/main/java/com/gongu/server/global/security/jwt/RefreshTokenStore.java` — `StringRedisTemplate` 사용 패턴 (`opsForValue()`, 빈 주입 방식)
- `src/main/java/com/gongu/server/global/exception/errorcode/ProductErrorCode.java` — `INSUFFICIENT_STOCK` 에러코드 확인

**수정 대상 파일:**
- Create: `src/main/java/com/gongu/server/domain/product/service/StockRedisService.java`
- Create: `src/test/java/com/gongu/server/domain/product/service/StockRedisServiceTest.java`

**금지 사항:**
- `StringRedisTemplate` 이외의 Redis 클라이언트 도입 금지 (이미 설정된 빈 재사용)
- TTL 설정 금지 (재고 키는 영구 보관)

**구현 방향:**

`StockRedisService`는 `@Service` + `@RequiredArgsConstructor`로 작성. `StringRedisTemplate` 단일 의존성.

세 개의 public 메서드 구현:

1. **`initializeStock(Long productId, int totalStock)`**
   - `stringRedisTemplate.opsForValue().set("stock:product:" + productId, String.valueOf(totalStock))`
   - 반환값 없음 (void)

2. **`reserveStock(Long productId, int quantity)`**
   - `stringRedisTemplate.opsForValue().decrement("stock:product:" + productId, quantity)`로 DECR
   - 반환된 `Long result`가 `< 0`이면 `increment("stock:product:" + productId, quantity)`로 롤백 후 `BusinessException(ProductErrorCode.INSUFFICIENT_STOCK)` 던짐
   - `result`가 `null`이면 (키 없음) `BusinessException(ProductErrorCode.PRODUCT_NOT_FOUND)` 던짐

3. **`releaseStock(Long productId, int quantity)`**
   - `stringRedisTemplate.opsForValue().increment("stock:product:" + productId, quantity)`
   - 반환값 없음 (void)

**`StockRedisServiceTest` 단위 테스트 (`@ExtendWith(MockitoExtension.class)`):**
- `@Mock StringRedisTemplate` 사용
- `reserveStock` 정상 케이스: decrement가 0 이상 반환 → 예외 없음
- `reserveStock` 재고 부족: decrement가 음수 반환 → `INSUFFICIENT_STOCK` 예외 + rollback INCR 호출 검증
- `reserveStock` 키 없음: decrement가 null 반환 → `PRODUCT_NOT_FOUND` 예외
- `releaseStock` 정상 케이스: increment 호출 검증
- `initializeStock` 정상 케이스: set 호출 검증

**검증:**
```bash
./gradlew test --tests "com.gongu.server.domain.product.service.StockRedisServiceTest"
```
Expected: BUILD SUCCESSFUL, 5개 테스트 통과

**커밋:**
```bash
git add src/main/java/com/gongu/server/domain/product/service/StockRedisService.java
git add src/test/java/com/gongu/server/domain/product/service/StockRedisServiceTest.java
git commit -m "feat: StockRedisService Redis 재고 예약 서비스 추가 (#144)"
```

---

## Task 2: Product.confirmStock 추가 + 상품 생성 시 Redis 초기화

- [ ] `Product.confirmStock` 메서드 추가
- [ ] `ProductService.createProduct` Redis 초기화 추가
- [ ] `ProductServiceTest` 업데이트

**참고 문서/파일 (읽어야 할 것):**
- `src/main/java/com/gongu/server/domain/product/entity/Product.java` — 기존 `decreaseStock`, `soldOut`, `restoreStock` 패턴
- `src/main/java/com/gongu/server/domain/product/service/ProductService.java` — `createProduct` 메서드 전체
- `src/test/java/com/gongu/server/domain/product/service/ProductServiceTest.java` — 기존 테스트 구조

**수정 대상 파일:**
- Modify: `src/main/java/com/gongu/server/domain/product/entity/Product.java`
- Modify: `src/main/java/com/gongu/server/domain/product/service/ProductService.java`
- Modify: `src/test/java/com/gongu/server/domain/product/service/ProductServiceTest.java`

**금지 사항:**
- `Product.decreaseStock()` 수정 금지 (현행 비관적 락 경로에서 계속 사용)
- `ProductService.decreaseStock()` 수정 금지 (대조군 보존)
- `ProductService` 내 다른 메서드(`updateProduct`, `closeProduct` 등) 수정 금지

**구현 방향:**

**`Product.java`에 `confirmStock(int quantity)` 추가:**
- `remainingStock -= quantity`
- `remainingStock < 0`이면 `throw new BusinessException(ProductErrorCode.INSUFFICIENT_STOCK)` (안전장치)
- `remainingStock == 0`이고 `status == ProductStatus.ACTIVE`이면 `this.status = ProductStatus.SOLD_OUT`

**`ProductService.createProduct` 수정:**
- `StockRedisService` 필드 추가: `private final StockRedisService stockRedisService;`
- `productRepository.save(product)` 직후 (save 반환 후 product.getId() 사용 가능한 시점):
  `stockRedisService.initializeStock(product.getId(), product.getTotalStock());`
- 나머지 로직 변경 없음

**`ProductServiceTest` 업데이트:**
- `@Mock StockRedisService stockRedisService;` 추가
- 생성자 주입 구성에 `stockRedisService` 추가
- `createProduct` 테스트에 `verify(stockRedisService).initializeStock(any(), anyInt())` 검증 추가

**검증:**
```bash
./gradlew test --tests "com.gongu.server.domain.product.service.ProductServiceTest"
./gradlew test --tests "com.gongu.server.domain.product.entity.ProductTest"
```
Expected: BUILD SUCCESSFUL

**커밋:**
```bash
git add src/main/java/com/gongu/server/domain/product/entity/Product.java
git add src/main/java/com/gongu/server/domain/product/service/ProductService.java
git add src/test/java/com/gongu/server/domain/product/service/ProductServiceTest.java
git commit -m "feat: 상품 생성 시 Redis 재고 초기화 + Product.confirmStock 추가 (#144)"
```

---

## Task 3: OrderService — createOrder/cancelOrder Redis 재고 연산으로 교체

- [ ] `createOrder` 비관적 락 → Redis DECR 교체
- [ ] `cancelOrder` restoreStock → Redis INCR 교체
- [ ] `OrderServiceTest` 업데이트

**참고 문서/파일 (읽어야 할 것):**
- `src/main/java/com/gongu/server/domain/order/service/OrderService.java` — 현재 `createOrder`, `cancelOrder` 전체
- `src/main/java/com/gongu/server/domain/order/entity/Order.java` — `cancel()` 메서드: `status == RESERVED`만 허용
- `src/main/java/com/gongu/server/domain/product/entity/Product.java` — `getStatus()`, `getPrice()` 확인
- `src/test/java/com/gongu/server/domain/order/service/OrderServiceTest.java` — 전체 테스트 구조

**수정 대상 파일:**
- Modify: `src/main/java/com/gongu/server/domain/order/service/OrderService.java`
- Modify: `src/test/java/com/gongu/server/domain/order/service/OrderServiceTest.java`

**금지 사항:**
- `receiveOrder`, `arriveOrder`, `getMyOrders`, `getOrder`, `getOrdersByProduct`, `getOrdersByUser` 수정 금지
- `lockWaitOrderTimer` 제거 금지 (`cancelOrder`에서 order 행 락에 여전히 사용)
- `MetricsConfig.java` 수정 금지

**구현 방향:**

**`OrderService` 필드 변경:**
- `StockRedisService stockRedisService` 필드 추가
- `@PersistenceContext EntityManager entityManager` 필드 제거 (Task 3 이후 불필요)
- `@Qualifier("lockWaitProductTimer") Timer lockWaitProductTimer` 필드 제거

**`createOrder` 메서드 변경:**
1. `productRepository.findById(productId)` 호출 유지 — 상품 존재 여부 + 가격 조회용
2. 이후 `userStoreRepository.existsByUserAndStore(user, store)` 체크 유지
3. **제거할 코드 블록 전체:**
   ```java
   entityManager.detach(product);
   product = lockWaitProductTimer
           .record(() -> productRepository.findByIdWithLock(productId))
           .orElseThrow(() -> new BusinessException(ProductErrorCode.PRODUCT_NOT_FOUND));
   product.decreaseStock(quantity);
   ```
4. **추가할 코드 (위 블록 자리에):**
   ```java
   // status는 findById 결과의 product에서 확인 (ACTIVE 여부)
   if (product.getStatus() != ProductStatus.ACTIVE) {
       throw new BusinessException(ProductErrorCode.INVALID_PRODUCT_STATUS);
   }
   if (quantity <= 0) {
       throw new BusinessException(ProductErrorCode.INVALID_PRODUCT_DATA);
   }
   stockRedisService.reserveStock(productId, quantity);
   ```
5. `totalPrice` 계산은 기존 `(long) product.getPrice() * quantity` 유지 (findById 결과 사용)

**`cancelOrder` 메서드 변경:**
1. `order.cancel(reason)` 호출 유지 (order 행 락 + 상태 변경)
2. **제거할 코드:**
   ```java
   items.stream()
       .sorted(Comparator.comparingLong(item -> item.getProduct().getId()))
       .forEach(item -> {
           Product product = lockWaitProductTimer
                   .record(() -> productRepository.findByIdWithLock(item.getProduct().getId()))
                   .orElseThrow(() -> ...);
           product.restoreStock(Math.toIntExact(item.getQuantity()));
       });
   ```
3. **추가할 코드 (위 블록 자리에):**
   ```java
   items.forEach(item ->
       stockRedisService.releaseStock(item.getProduct().getId(), Math.toIntExact(item.getQuantity()))
   );
   ```
   (정렬 불필요 — Redis 연산은 락 없음)
4. `productRepository` 필드가 `cancelOrder`에서만 사용되었다면 제거 가능. 단, 다른 메서드에서 사용하면 유지.

**import 정리:** `ProductStatus` import 추가, `EntityManager`/`PersistenceContext` import 제거, `Comparator` import 확인 (다른 곳에서 사용 없으면 제거)

**`OrderServiceTest` 업데이트:**
- `@Mock StockRedisService stockRedisService` 추가
- `EntityManager` mock 제거
- `lockWaitProductTimer` mock 제거
- `createOrder` 성공 케이스: `productRepository.findByIdWithLock` mock 제거, `stockRedisService.reserveStock` 호출 검증 추가
- `createOrder` 실패 케이스 (재고 부족): `stockRedisService.reserveStock`이 `INSUFFICIENT_STOCK` 예외를 던지도록 stubbing
- `createOrder` 실패 케이스 (ACTIVE 아님): `product.getStatus()` 반환값 `UPCOMING`으로 설정 → `INVALID_PRODUCT_STATUS` 예외 검증
- `cancelOrder` 성공 케이스: `stockRedisService.releaseStock` 호출 검증

**검증:**
```bash
./gradlew test --tests "com.gongu.server.domain.order.service.OrderServiceTest"
```
Expected: BUILD SUCCESSFUL

**커밋:**
```bash
git add src/main/java/com/gongu/server/domain/order/service/OrderService.java
git add src/test/java/com/gongu/server/domain/order/service/OrderServiceTest.java
git commit -m "feat: createOrder 비관적 락 제거, Redis 재고 예약으로 교체 (#144)"
```

---

## Task 4: OrderExpireService — 만료 취소 시 Redis 재고 반환

- [ ] `cancelExpiredOrder` restoreStock → Redis INCR 교체
- [ ] `OrderExpireServiceTest` 업데이트

**참고 문서/파일 (읽어야 할 것):**
- `src/main/java/com/gongu/server/domain/order/service/OrderExpireService.java` — 전체
- `src/test/java/com/gongu/server/domain/order/service/OrderExpireServiceTest.java` — 전체

**수정 대상 파일:**
- Modify: `src/main/java/com/gongu/server/domain/order/service/OrderExpireService.java`
- Modify: `src/test/java/com/gongu/server/domain/order/service/OrderExpireServiceTest.java`

**금지 사항:**
- `lockWaitOrderTimer` 제거 금지 (order 행 락에 여전히 사용)
- `paymentRepository` 관련 코드 수정 금지

**구현 방향:**

**`OrderExpireService` 필드 변경:**
- `StockRedisService stockRedisService` 필드 추가
- `@Qualifier("lockWaitProductTimer") Timer lockWaitProductTimer` 필드 제거
- `ProductRepository productRepository` 의존성 제거 (Task 4 이후 불필요)

**`cancelExpiredOrder` 메서드 변경:**
- **제거할 코드:**
  ```java
  items.stream()
      .sorted(Comparator.comparingLong(item -> item.getProduct().getId()))
      .forEach(item -> {
          Product product = lockWaitProductTimer
                  .record(() -> productRepository.findByIdWithLock(item.getProduct().getId()))
                  .orElseThrow(() -> ...);
          product.restoreStock(Math.toIntExact(item.getQuantity()));
      });
  ```
- **추가할 코드 (위 블록 자리에):**
  ```java
  items.forEach(item ->
      stockRedisService.releaseStock(item.getProduct().getId(), Math.toIntExact(item.getQuantity()))
  );
  ```
- `order.cancel("결제 시간 초과")` 호출 위치 및 기타 로직 변경 없음

**`OrderExpireServiceTest` 업데이트:**
- `@Mock StockRedisService stockRedisService` 추가
- `productRepository` mock 제거
- `lockWaitProductTimer` mock 제거
- 만료 취소 성공 케이스: `stockRedisService.releaseStock` 호출 검증 추가

**검증:**
```bash
./gradlew test --tests "com.gongu.server.domain.order.service.OrderExpireServiceTest"
```
Expected: BUILD SUCCESSFUL

**커밋:**
```bash
git add src/main/java/com/gongu/server/domain/order/service/OrderExpireService.java
git add src/test/java/com/gongu/server/domain/order/service/OrderExpireServiceTest.java
git commit -m "feat: 주문 만료 취소 시 Redis 재고 반환으로 교체 (#144)"
```

---

## Task 5: PaymentService — 결제 확정 시 MySQL remaining_stock 차감

- [ ] `completePayment` 결제 확정 후 `Product.confirmStock` 호출
- [ ] `PaymentExpireServiceTest` 영향 없음 확인

**참고 문서/파일 (읽어야 할 것):**
- `src/main/java/com/gongu/server/domain/payment/service/PaymentService.java` — `completePayment` 전체 (특히 `order.pay()` 호출 후 분기)
- `src/main/java/com/gongu/server/domain/order/entity/OrderItem.java` — 필드 구조 (`getProduct()`, `getQuantity()`)
- `src/main/java/com/gongu/server/domain/product/entity/Product.java` — `confirmStock` 시그니처 (Task 2에서 추가)
- `src/main/java/com/gongu/server/domain/order/repository/OrderItemRepository.java` — `findAllByOrder(Order order)` 메서드 존재 여부

**수정 대상 파일:**
- Modify: `src/main/java/com/gongu/server/domain/payment/service/PaymentService.java`

**금지 사항:**
- `PortOneClient`, `PaymentRepository` 관련 로직 수정 금지
- 결제 금액 불일치(`order.cancel()`) 케이스에서 Redis INCR 추가 금지 (이 경우 order는 RESERVED→CANCELLED지만 결제는 실패이므로 별도 정합성 처리 필요 — 이번 범위 밖)

**구현 방향:**

**`PaymentService` 필드 추가:**
- `private final OrderItemRepository orderItemRepository;`
- `private final ProductRepository productRepository;`

**`completePayment` 수정 — 결제 금액 일치 분기 내:**

현재 코드:
```java
if (expectedAmount.equals(actualAmount)) {
    order.pay();
    payment.confirm(actualAmount, portOneResponse.paidAt().toLocalDateTime());
    paymentCompletedCounter.increment();
    return VerifyPaymentResponse.of(order, payment);
}
```

변경 후:
```java
if (expectedAmount.equals(actualAmount)) {
    order.pay();
    payment.confirm(actualAmount, portOneResponse.paidAt().toLocalDateTime());

    // MySQL remaining_stock 차감 (결제 확정 시점)
    List<OrderItem> items = orderItemRepository.findAllByOrder(order);
    items.forEach(item -> {
        Product product = productRepository.findByIdWithLock(item.getProduct().getId())
                .orElseThrow(() -> new BusinessException(ProductErrorCode.PRODUCT_NOT_FOUND));
        product.confirmStock(Math.toIntExact(item.getQuantity()));
    });

    paymentCompletedCounter.increment();
    return VerifyPaymentResponse.of(order, payment);
}
```

**import 추가:**
- `com.gongu.server.domain.order.entity.OrderItem`
- `com.gongu.server.domain.order.repository.OrderItemRepository`
- `com.gongu.server.domain.product.entity.Product`
- `com.gongu.server.domain.product.repository.ProductRepository`
- `com.gongu.server.global.exception.errorcode.ProductErrorCode`
- `java.util.List`

**검증:**
```bash
./gradlew test --tests "com.gongu.server.domain.payment.service.*"
```
Expected: BUILD SUCCESSFUL (기존 PaymentService 테스트 통과)

**커밋:**
```bash
git add src/main/java/com/gongu/server/domain/payment/service/PaymentService.java
git commit -m "feat: 결제 확정 시 MySQL remaining_stock 차감 추가 (#144)"
```

---

## Task 6: 전체 빌드 및 통합 검증

- [ ] 전체 빌드 통과
- [ ] 전체 테스트 통과

**참고 문서/파일 (읽어야 할 것):**
- `src/test/java/com/gongu/server/domain/product/service/ProductStockConcurrencyTest.java` — 기존 동시성 테스트 구조

**수정 대상 파일:**
- 없음 (빌드/테스트 검증만)

**검증:**
```bash
./gradlew build
```
Expected: BUILD SUCCESSFUL, 전체 테스트 통과

---

## 완료 후 작업

PR 생성 후 CLAUDE.md 9~11단계(Codex 리뷰 위임 → 판정 → 반영) 적용.

### 브랜치명
```
feat/#144-redis-stock-reservation
```

### PR 제목
```
[FEAT] Redis 재고 예약 계층 도입 — 비관적 락 병목 해소 (#144)
```

### TPS 측정 (PR 머지 전)
```bash
docker compose up --build -d server
./load-test/cleanup.sh
docker compose run --rm k6 run /scripts/scenarios/07-order-tps.js
```
결과를 이슈 #144 코멘트에 베이스라인과 비교 업데이트.
