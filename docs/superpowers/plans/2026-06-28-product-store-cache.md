# Product/Store Redis 캐싱 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** createOrder 핫패스의 DB read 4건을 Redis 캐싱으로 줄여 주문 처리 성능을 개선한다.

**Spec:** GitHub Issue #163

**Tech Stack:** Spring Boot 3.5, Spring Cache (`@EnableCaching`), Spring Data Redis, Jackson (JSON 직렬화)

---

## 파일 맵

### 생성
- `src/main/java/com/gongu/server/global/config/CacheConfig.java` — `@EnableCaching` + `RedisCacheManager` TTL 설정
- `src/main/java/com/gongu/server/domain/product/dto/ProductCacheDto.java` — 캐시용 경량 DTO (record)
- `src/main/java/com/gongu/server/domain/product/service/ProductCacheService.java` — Product 캐시 read/evict
- `src/main/java/com/gongu/server/domain/store/service/UserStoreCacheService.java` — 구독 여부 캐시 read/evict
- `src/test/java/com/gongu/server/domain/product/service/ProductCacheServiceTest.java`
- `src/test/java/com/gongu/server/domain/store/service/UserStoreCacheServiceTest.java`

### 수정
- `src/main/resources/application.yml` — cache TTL 프로퍼티 추가
- `src/main/java/com/gongu/server/domain/order/service/OrderService.java` — 캐시 서비스 사용으로 교체
- `src/main/java/com/gongu/server/domain/product/service/ProductService.java` — update/close 시 `@CacheEvict`
- `src/main/java/com/gongu/server/domain/product/scheduler/ProductStatusTransactionHelper.java` — activate 시 `@CacheEvict`
- `src/main/java/com/gongu/server/domain/store/service/StoreService.java` — registerUserStore 시 `@CacheEvict`
- `src/test/java/com/gongu/server/domain/order/service/OrderServiceTest.java` — `ProductCacheService`/`UserStoreCacheService` Mock으로 교체

### 참조만 (수정 없음)
- `src/main/java/com/gongu/server/domain/product/entity/Product.java` — DTO 필드 기준 확인
- `src/main/java/com/gongu/server/domain/product/entity/ProductStatus.java` — enum 값 확인
- `src/main/java/com/gongu/server/domain/product/service/StockRedisService.java` — 패턴 참고
- `src/main/java/com/gongu/server/global/config/AppConfig.java` — 기존 Config 패턴 참고

---

## Task 1: CacheConfig + application.yml TTL 설정

**참고 문서/파일:**
- `src/main/java/com/gongu/server/global/config/AppConfig.java` — 기존 Config 클래스 패턴 참고
- `src/main/resources/application.yml` — 기존 redis 설정 위치 확인 (host/port는 이미 있음)

**수정 대상 파일:**
- Create: `src/main/java/com/gongu/server/global/config/CacheConfig.java`
- Modify: `src/main/resources/application.yml`

**금지 사항:**
- 기존 Redis 연결 설정 (`spring.data.redis.host/port`) 변경 금지
- `StockRedisService`의 `RedisTemplate` 빈과 충돌하지 않도록 `CacheManager` 빈만 추가

**구현 방향:**
- `CacheConfig`에 `@Configuration`, `@EnableCaching` 추가
- `RedisCacheManager` 빈을 생성하여 기본 직렬화를 `GenericJackson2JsonRedisSerializer`로 설정
- 캐시별 TTL을 `RedisCacheConfiguration`으로 개별 지정:
  - `"product"` 캐시: TTL 5분
  - `"user-store"` 캐시: TTL 10분
- `application.yml`에 TTL 값을 환경변수로 외부화할 필요 없음 — 코드에서 상수로 관리

**검증:**
```bash
./gradlew compileJava
```
Expected: BUILD SUCCESSFUL (컴파일 오류 없음)

**커밋:**
```bash
git add src/main/java/com/gongu/server/global/config/CacheConfig.java src/main/resources/application.yml
git commit -m "chore: Spring Cache RedisCacheManager 설정 추가 (#163)"
```

---

## Task 2: ProductCacheDto + ProductCacheService

**참고 문서/파일:**
- `src/main/java/com/gongu/server/domain/product/entity/Product.java` — `id`, `store.id`, `price`, `status` 필드 위치 확인
- `src/main/java/com/gongu/server/domain/product/entity/ProductStatus.java` — enum 직렬화 확인
- `src/main/java/com/gongu/server/domain/product/service/StockRedisService.java` — 서비스 패턴 참고
- `src/main/java/com/gongu/server/global/exception/errorcode/ProductErrorCode.java` — 예외 코드 참고

**수정 대상 파일:**
- Create: `src/main/java/com/gongu/server/domain/product/dto/ProductCacheDto.java`
- Create: `src/main/java/com/gongu/server/domain/product/service/ProductCacheService.java`

**금지 사항:**
- `Product` JPA 엔티티를 직접 캐싱 금지 (Hibernate 프록시 직렬화 문제)
- `remainingStock` 필드를 DTO에 포함 금지 (Redis가 소스 오브 트루스)

**구현 방향:**

`ProductCacheDto`:
- `record ProductCacheDto(Long id, Long storeId, Long price, ProductStatus status)`
- `static ProductCacheDto from(Product product)` — `product.getStore().getId()` 사용
- `@JsonDeserialize` 등 Jackson 어노테이션 없이 record만으로 충분

`ProductCacheService`:
- `getProduct(Long productId)`: `@Cacheable(value = "product", key = "#productId")`
  - 캐시 미스 시 `productRepository.findById(productId)` 후 `ProductCacheDto.from()` 반환
  - 없으면 `BusinessException(ProductErrorCode.PRODUCT_NOT_FOUND)` throw
- `evict(Long productId)`: `@CacheEvict(value = "product", key = "#productId")`
  - 반환값 없음 (void)

**검증:**
```bash
./gradlew compileJava
```
Expected: BUILD SUCCESSFUL

**커밋:**
```bash
git add src/main/java/com/gongu/server/domain/product/dto/ProductCacheDto.java src/main/java/com/gongu/server/domain/product/service/ProductCacheService.java
git commit -m "feat: ProductCacheDto 및 ProductCacheService 추가 (#163)"
```

---

## Task 3: UserStoreCacheService

**참고 문서/파일:**
- `src/main/java/com/gongu/server/domain/store/repository/UserStoreRepository.java` — `existsByUserAndStore(User, Store)` 시그니처 확인
- `src/main/java/com/gongu/server/domain/store/entity/UserStore.java` — 엔티티 구조 파악
- `src/main/java/com/gongu/server/global/exception/errorcode/UserErrorCode.java` — 예외 코드 확인

**수정 대상 파일:**
- Create: `src/main/java/com/gongu/server/domain/store/service/UserStoreCacheService.java`

**금지 사항:**
- `UserStore` JPA 엔티티를 캐시값으로 저장 금지
- `User`, `Store` 엔티티를 캐시 키로 직접 사용 금지 — `userId`, `storeId` (Long)를 키로 사용

**구현 방향:**
- `existsByUserAndStore(Long userId, Long storeId)`: `@Cacheable(value = "user-store", key = "#userId + ':' + #storeId")`
  - 캐시 미스 시: `userRepository.findByIdAndDeletedAtIsNull(userId)`로 `User` 조회 → `storeRepository.findById(storeId)`로 `Store` 조회 → `userStoreRepository.existsByUserAndStore(user, store)` 결과(boolean) 반환
- `evict(Long userId, Long storeId)`: `@CacheEvict(value = "user-store", key = "#userId + ':' + #storeId")`

**검증:**
```bash
./gradlew compileJava
```
Expected: BUILD SUCCESSFUL

**커밋:**
```bash
git add src/main/java/com/gongu/server/domain/store/service/UserStoreCacheService.java
git commit -m "feat: UserStoreCacheService 구독 여부 캐싱 추가 (#163)"
```

---

## Task 4: OrderService.createOrder 캐시 서비스로 교체

**참고 문서/파일:**
- `src/main/java/com/gongu/server/domain/order/service/OrderService.java` — 현재 createOrder 로직 전체 숙지
- `src/main/java/com/gongu/server/domain/product/dto/ProductCacheDto.java` — DTO 필드 확인
- `src/main/java/com/gongu/server/global/exception/errorcode/ProductErrorCode.java` — 예외 코드 확인

**수정 대상 파일:**
- Modify: `src/main/java/com/gongu/server/domain/order/service/OrderService.java`

**금지 사항:**
- `createOrder` 이외의 메서드 수정 금지
- `productRepository` 필드 삭제 금지 — `arriveOrder`, `getOrdersByProduct` 등에서 여전히 사용 중
- `userStoreRepository` 필드 삭제 금지 — 다른 메서드에서 사용 중

**구현 방향:**

`createOrder` 메서드 안에서 아래 세 줄을 교체:
```
// 기존
Product product = productRepository.findById(productId)...
Store store = product.getStore();
if (!userStoreRepository.existsByUserAndStore(user, store)) { ... }

// 변경 후
ProductCacheDto product = productCacheService.getProduct(productId);
if (!userStoreCacheService.existsByUserAndStore(userId, product.storeId())) { ... }
```

- `product.getStatus()` → `product.status()`
- `product.getPrice()` → `product.price()`
- `OrderItem.create(order, product, ...)` 에서 Product 엔티티가 필요하다면 DTO 사용 불가 → **`productRepository.getReferenceById(productId)`** 로 프록시만 가져와서 FK 연결에 사용 (DB 조회 없이 참조 생성)
- `OrderService` 생성자에 `ProductCacheService`, `UserStoreCacheService` 추가 (`@RequiredArgsConstructor` 사용 중이므로 필드 선언만)

**검증:**
```bash
./gradlew compileJava
```
Expected: BUILD SUCCESSFUL

**커밋:**
```bash
git add src/main/java/com/gongu/server/domain/order/service/OrderService.java
git commit -m "feat: createOrder Product/Store DB 조회를 캐시 서비스로 교체 (#163)"
```

---

## Task 5: Evict 연동 — ProductService, ProductStatusTransactionHelper, StoreService

**참고 문서/파일:**
- `src/main/java/com/gongu/server/domain/product/service/ProductService.java` — `updateProduct`, `closeProduct` 위치 확인
- `src/main/java/com/gongu/server/domain/product/scheduler/ProductStatusTransactionHelper.java` — `activateOne` 위치 확인
- `src/main/java/com/gongu/server/domain/store/service/StoreService.java` — `registerUserStore` 위치 확인

**수정 대상 파일:**
- Modify: `src/main/java/com/gongu/server/domain/product/service/ProductService.java`
- Modify: `src/main/java/com/gongu/server/domain/product/scheduler/ProductStatusTransactionHelper.java`
- Modify: `src/main/java/com/gongu/server/domain/store/service/StoreService.java`

**금지 사항:**
- 비즈니스 로직 변경 금지 — evict 호출 추가만
- `ProductService`의 다른 메서드 수정 금지

**구현 방향:**

`ProductService`:
- `updateProduct()` 메서드에 `@CacheEvict(value = "product", key = "#productId")` 추가
- `closeProduct()` 메서드에 `@CacheEvict(value = "product", key = "#productId")` 추가

`ProductStatusTransactionHelper`:
- `ProductCacheService` 필드 추가 (`@RequiredArgsConstructor`)
- `activateOne(Long productId)` 내부에서 `product.activate()` 호출 후 `productCacheService.evict(productId)` 호출
- 또는 `@CacheEvict(value = "product", key = "#productId")` 어노테이션 추가

`StoreService`:
- `UserStoreCacheService` 필드 추가
- `registerUserStore()` 내부에서 `userStoreRepository.save(userStore)` 후 `userStoreCacheService.evict(userId, store.getId())` 호출

**검증:**
```bash
./gradlew compileJava
```
Expected: BUILD SUCCESSFUL

**커밋:**
```bash
git add src/main/java/com/gongu/server/domain/product/service/ProductService.java \
        src/main/java/com/gongu/server/domain/product/scheduler/ProductStatusTransactionHelper.java \
        src/main/java/com/gongu/server/domain/store/service/StoreService.java
git commit -m "feat: 상품/구독 상태 변경 시 캐시 evict 연동 (#163)"
```

---

## Task 6: 테스트 작성 및 기존 OrderServiceTest 수정

**참고 문서/파일:**
- `src/test/java/com/gongu/server/domain/order/service/OrderServiceTest.java` — 기존 Mock 패턴 그대로 따름
- `src/test/java/com/gongu/server/domain/product/service/StockRedisServiceTest.java` — Mock 패턴 참고

**수정 대상 파일:**
- Create: `src/test/java/com/gongu/server/domain/product/service/ProductCacheServiceTest.java`
- Create: `src/test/java/com/gongu/server/domain/store/service/UserStoreCacheServiceTest.java`
- Modify: `src/test/java/com/gongu/server/domain/order/service/OrderServiceTest.java`

**금지 사항:**
- 통합 테스트(실제 Redis 연결) 작성 금지 — 단위 테스트(Mockito)만

**구현 방향:**

`ProductCacheServiceTest` (단위 테스트):
- `getProduct()`: 캐시 미스 시 `productRepository.findById()` 호출 검증
- `getProduct()`: 존재하지 않는 productId → `BusinessException(PRODUCT_NOT_FOUND)` 검증

`UserStoreCacheServiceTest` (단위 테스트):
- `existsByUserAndStore()`: 구독 중인 경우 `true` 반환 검증
- `existsByUserAndStore()`: 미구독인 경우 `false` 반환 검증

`OrderServiceTest` 수정:
- `productRepository.findById()` Mock을 `productCacheService.getProduct()` Mock으로 교체
- `userStoreRepository.existsByUserAndStore()` Mock을 `userStoreCacheService.existsByUserAndStore()` Mock으로 교체
- `ProductCacheDto` 반환 타입에 맞게 `given()` 수정

**검증:**
```bash
./gradlew test --tests "com.gongu.server.domain.product.service.ProductCacheServiceTest"
./gradlew test --tests "com.gongu.server.domain.store.service.UserStoreCacheServiceTest"
./gradlew test --tests "com.gongu.server.domain.order.service.OrderServiceTest"
```
Expected: 모든 테스트 PASSED

**커밋:**
```bash
git add src/test/java/com/gongu/server/domain/product/service/ProductCacheServiceTest.java \
        src/test/java/com/gongu/server/domain/store/service/UserStoreCacheServiceTest.java \
        src/test/java/com/gongu/server/domain/order/service/OrderServiceTest.java
git commit -m "test: ProductCacheService, UserStoreCacheService 단위 테스트 추가 (#163)"
```

---

## PR 생성 후

CLAUDE.md 9~11단계(Codex 리뷰 위임 → 판정 → 반영) 따름
