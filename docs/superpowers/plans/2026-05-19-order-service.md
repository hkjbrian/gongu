# OrderService Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 주문 생성·취소·조회·관리자 조회 비즈니스 로직을 담은 OrderService와 Response DTO를 구현한다

**Spec:** `docs/superpowers/specs/2026-05-18-order-domain-design.md`

**Tech Stack:** Spring Boot 3.5, Java 25, Spring Data JPA, Lombok

---

## 파일 맵

### 생성 대상
- `src/main/java/com/gongu/server/domain/order/dto/response/OrderSummaryResponse.java`
- `src/main/java/com/gongu/server/domain/order/dto/response/OrderItemResponse.java`
- `src/main/java/com/gongu/server/domain/order/dto/response/OrderDetailResponse.java`
- `src/main/java/com/gongu/server/domain/order/service/OrderService.java`

### 참고만 (수정 금지)
- `src/main/java/com/gongu/server/domain/order/entity/Order.java`
- `src/main/java/com/gongu/server/domain/order/entity/OrderItem.java`
- `src/main/java/com/gongu/server/domain/order/entity/OrderStatus.java`
- `src/main/java/com/gongu/server/domain/order/repository/OrderRepository.java`
- `src/main/java/com/gongu/server/domain/order/repository/OrderItemRepository.java`
- `src/main/java/com/gongu/server/domain/product/entity/Product.java`
- `src/main/java/com/gongu/server/domain/product/repository/ProductRepository.java`
- `src/main/java/com/gongu/server/domain/store/repository/StoreAdminRepository.java`
- `src/main/java/com/gongu/server/domain/user/entity/User.java`
- `src/main/java/com/gongu/server/domain/user/repository/UserRepository.java`
- `src/main/java/com/gongu/server/domain/product/service/ProductService.java` — @Service 패턴 참고
- `src/main/java/com/gongu/server/global/exception/errorcode/OrderErrorCode.java`
- `src/main/java/com/gongu/server/global/exception/errorcode/ProductErrorCode.java`
- `src/main/java/com/gongu/server/global/exception/errorcode/StoreErrorCode.java`
- `src/main/java/com/gongu/server/global/exception/errorcode/UserErrorCode.java`
- `src/main/java/com/gongu/server/global/exception/BusinessException.java`

---

## Task 1: Response DTO + 주문 생성·취소

- [ ] Response DTO 3개 생성
- [ ] OrderService 생성 (createOrder, cancelOrder)

### 참고 문서/파일:
- Spec `docs/superpowers/specs/2026-05-18-order-domain-design.md` — 3절(엔티티), 4절(트랜잭션 경계)
- `domain/order/entity/Order.java`, `OrderItem.java` — 필드명, 정적 팩토리 시그니처
- `domain/product/service/ProductService.java` — @Service, @Transactional, orElseThrow 패턴

### 수정 대상 파일:
- Create: `domain/order/dto/response/OrderSummaryResponse.java`
- Create: `domain/order/dto/response/OrderItemResponse.java`
- Create: `domain/order/dto/response/OrderDetailResponse.java`
- Create: `domain/order/service/OrderService.java`

### 금지 사항:
- 엔티티 파일 수정 금지
- Repository 파일 수정 금지
- Controller, 조회 메서드는 Task 2에서 처리

### 구현 방향:

**OrderSummaryResponse** (주문 목록용):
- 필드: `orderId`, `productName`, `quantity`, `totalPrice`, `status(OrderStatus)`, `createdAt`
- 정적 팩토리: `of(Order order, OrderItem firstItem, String productName)` 형태로 작성
- Lombok `@Getter`, `@AllArgsConstructor(access = PRIVATE)`, `@Builder`

**OrderItemResponse** (주문 상세 내 아이템):
- 필드: `productId`, `productName`, `quantity`, `unitPrice`
- 정적 팩토리: `of(OrderItem item)`
- Lombok `@Getter`, `@AllArgsConstructor(access = PRIVATE)`, `@Builder`

**OrderDetailResponse** (주문 상세):
- 필드: `orderId`, `status(OrderStatus)`, `totalPrice`, `cancelledAt`, `cancelReason`, `createdAt`, `List<OrderItemResponse> items`
- 정적 팩토리: `of(Order order, List<OrderItem> items)`
- Lombok `@Getter`, `@AllArgsConstructor(access = PRIVATE)`, `@Builder`

**OrderService 클래스:**
- `@Service`, `@RequiredArgsConstructor`, `@Transactional(readOnly = true)` 클래스 레벨
- 생성자 주입: `UserRepository`, `ProductRepository`, `StoreAdminRepository`, `OrderRepository`, `OrderItemRepository`

**createOrder(Long userId, Long productId, int quantity) → OrderDetailResponse:**
1. `userRepository.findByIdAndDeletedAtIsNull(userId).orElseThrow(UserErrorCode.USER_NOT_FOUND)`
2. `productRepository.findById(productId).orElseThrow(ProductErrorCode.PRODUCT_NOT_FOUND)` — 상태 확인용 (락 없이)
3. `productRepository.findByIdWithLock(productId).orElseThrow(...)` — SELECT FOR UPDATE
4. `product.decreaseStock(quantity)` — 재고 검증 + 차감 (Product 도메인 메서드)
5. `long totalPrice = product.getPrice() * quantity`
6. `Order order = Order.create(user, totalPrice)`
7. `orderRepository.save(order)`
8. `OrderItem item = OrderItem.create(order, product, (long) quantity)`
9. `orderItemRepository.save(item)`
10. `return OrderDetailResponse.of(order, List.of(item))`
- 메서드 레벨 `@Transactional` 명시

**cancelOrder(Long userId, Long orderId, String reason) — void:**
1. `userRepository.findByIdAndDeletedAtIsNull(userId).orElseThrow(...)`
2. `orderRepository.findByIdAndUser(orderId, user).orElseThrow(OrderErrorCode.ORDER_NOT_FOUND)`
3. `order.cancel(reason)` — RESERVED 검증 + 상태 CANCELLED + cancelledAt 기록 (Order 도메인 메서드)
4. `List<OrderItem> items = orderItemRepository.findAllByOrder(order)`
5. items를 순회하며 각 item에 대해:
   - `productRepository.findByIdWithLock(item.getProduct().getId()).orElseThrow(...)`
   - `product.restoreStock((int) item.getQuantity().intValue())`
- 메서드 레벨 `@Transactional` 명시

### 검증:
```bash
./gradlew compileJava
```
Expected: BUILD SUCCESSFUL

### 커밋:
```bash
git add src/main/java/com/gongu/server/domain/order/dto/
git add src/main/java/com/gongu/server/domain/order/service/OrderService.java
git commit -m "feat: OrderService 주문 생성·취소 로직 (#74)"
```

---

## Task 2: 조회 로직 + 관리자 조회

- [ ] OrderService 조회 메서드 추가 (getMyOrders, getOrder)
- [ ] OrderService 관리자 조회 메서드 추가 (getOrdersByProduct, getOrdersByMember)

### 참고 문서/파일:
- Spec `docs/superpowers/specs/2026-05-18-order-domain-design.md` — 1절(API 범위), 5절(비즈니스 규칙)
- `domain/order/repository/OrderRepository.java` — 사용할 메서드 시그니처 확인
- `domain/product/service/ProductService.java` — 관리자 권한 검증 패턴 (`storeAdminRepository.findByIdAndDeletedAtIsNull`, store 소유권 검증)

### 수정 대상 파일:
- Modify: `domain/order/service/OrderService.java` — 조회·관리자 메서드 추가

### 금지 사항:
- DTO 파일 수정 금지 (Task 1에서 확정)
- Repository 파일 수정 금지

### 구현 방향:

**getMyOrders(Long userId, OrderStatus status, Pageable pageable) → Page\<OrderSummaryResponse\>:**
- User 조회 (orElseThrow USER_NOT_FOUND)
- `status == null` 이면 `orderRepository.findAllByUserOrderByCreatedAtDesc(user, pageable)`
- `status != null` 이면 `orderRepository.findAllByUserAndStatusOrderByCreatedAtDesc(user, status, pageable)`
- 각 Order에 대해 `orderItemRepository.findAllByOrder(order)`의 첫 번째 item과 productName 조합해 `OrderSummaryResponse.of(...)` 변환

**getOrder(Long userId, Long orderId) → OrderDetailResponse:**
- User 조회
- `orderRepository.findByIdAndUser(orderId, user).orElseThrow(ORDER_NOT_FOUND)`
- `orderItemRepository.findAllByOrder(order)`
- `return OrderDetailResponse.of(order, items)`

**getOrdersByProduct(Long storeAdminId, Long productId, Pageable pageable) → Page\<OrderSummaryResponse\>:**
- `storeAdminRepository.findByIdAndDeletedAtIsNull(storeAdminId).orElseThrow(STORE_ADMIN_NOT_FOUND)`
- `productRepository.findByIdAndStore(productId, storeAdmin.getStore()).orElseThrow(PRODUCT_NOT_FOUND)` — 권한 검증 포함
- `orderRepository.findAllByProduct(product, pageable)`
- OrderSummaryResponse로 변환하여 반환

**getOrdersByMember(Long storeAdminId, Long memberId, Pageable pageable) → Page\<OrderSummaryResponse\>:**
- `storeAdminRepository.findByIdAndDeletedAtIsNull(storeAdminId).orElseThrow(STORE_ADMIN_NOT_FOUND)` — StoreAdmin 존재 검증
- `userRepository.findByIdAndDeletedAtIsNull(memberId).orElseThrow(USER_NOT_FOUND)`
- `orderRepository.findAllByUserOrderByCreatedAtDesc(user, pageable)`
- OrderSummaryResponse로 변환하여 반환

### 검증:
```bash
./gradlew compileJava
```
Expected: BUILD SUCCESSFUL

### 커밋:
```bash
git add src/main/java/com/gongu/server/domain/order/service/OrderService.java
git commit -m "feat: OrderService 조회 로직 및 관리자 조회 (#74)"
```

---

## PR 생성 후
CLAUDE.md 9~11단계 (Codex /review → 판정 → 반영) 따름
