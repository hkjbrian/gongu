# Order 도메인 구현 플랜

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 고객 주문 생성·조회·취소 및 관리자 주문 조회 API 구현 (Milestone #6)

**Spec:** `server/docs/superpowers/specs/2026-05-18-order-domain-design.md`

**Tech Stack:** Spring Boot 3.5, Java 25, Spring Data JPA, MySQL 8.0

---

## 주의사항 (구현 전 필독)

- **엔티티 폴더**: `domain/order/domain/.gitkeep` 이 잘못 명명된 스켈레톤임. 엔티티 파일은 `domain/order/entity/` 에 생성하고 `.gitkeep`은 삭제할 것
- **User vs Member**: DDL의 `user_id` FK는 `com.gongu.server.domain.user.entity.User` 엔티티를 참조 (`Member` 아님)
- **에러 코드**: 취소 불가 에러는 `OrderErrorCode.CANCEL_NOT_ALLOWED` 사용 (이미 선언됨)
- **restoreStock**: `Product.restoreStock(int quantity)` 이미 구현되어 있음 — 재사용
- **PR 생성 후**: `CLAUDE.md` 9~11단계 (Codex 리뷰 위임 → 판정 → 반영) 따름

---

## Task 1: Order + OrderItem 엔티티 (Issue #72)

**참고 문서/파일:**
- Spec `server/docs/superpowers/specs/2026-05-18-order-domain-design.md` — 3절 엔티티 설계
- `server/docs/schema/ddl.sql` — `orders`, `order_items` 테이블 컬럼·타입 기준
- `server/src/main/java/com/gongu/server/domain/product/entity/Product.java` — 엔티티 패턴 참고 (정적 팩토리, NoArgsConstructor, BaseEntity 상속)
- `server/src/main/java/com/gongu/server/domain/user/entity/User.java` — user FK 참조 대상
- `server/src/main/java/com/gongu/server/global/common/BaseEntity.java` — 상속 대상
- `server/src/main/java/com/gongu/server/global/exception/errorcode/OrderErrorCode.java` — CANCEL_NOT_ALLOWED, RECEIVE_NOT_ALLOWED 참조

**수정 대상 파일:**
- Delete: `server/src/main/java/com/gongu/server/domain/order/domain/.gitkeep`
- Create: `server/src/main/java/com/gongu/server/domain/order/entity/OrderStatus.java`
- Create: `server/src/main/java/com/gongu/server/domain/order/entity/Order.java`
- Create: `server/src/main/java/com/gongu/server/domain/order/entity/OrderItem.java`

**금지 사항:**
- `domain/order/domain/` 하위에 파일 생성 금지 — `entity/` 에만 생성
- `OrderErrorCode.java` 수정 금지 — 이미 완성됨
- 다른 도메인 파일 수정 금지

**구현 방향:**

`OrderStatus` enum:
- 값: `RESERVED, PAID, ARRIVED, RECEIVED, CANCELLED`

`Order` 엔티티 (`@Table(name = "orders")`):
- `id` BIGINT PK — `@GeneratedValue(strategy = IDENTITY)`
- `user` — `@ManyToOne(fetch = LAZY)`, `@JoinColumn(name = "user_id", nullable = false)`
- `status` — `@Enumerated(EnumType.STRING)`, `@Column(nullable = false, length = 20)`
- `totalPrice` — `@Column(name = "total_price", nullable = false)`
- `cancelledAt` — `@Column(name = "cancelled_at")`, nullable (NULL 허용)
- `cancelReason` — `@Column(name = "cancel_reason", length = 255)`, nullable
- `orderItems` — `@OneToMany(mappedBy = "order", cascade = CascadeType.PERSIST)`, `List<OrderItem>` 초기화
- `BaseEntity` 상속 (createdAt, updatedAt)
- 정적 팩토리: `Order.create(User user, long totalPrice)` → status = RESERVED
- 도메인 메서드:
  - `cancel(String reason)` — status != RESERVED이면 `OrderErrorCode.CANCEL_NOT_ALLOWED` throw, cancelledAt = now()
  - `pay()` — status != RESERVED이면 throw (향후 결제 마일스톤용, 이번엔 선언만)
  - `arrive()` — status != PAID이면 throw (향후용, 선언만)
  - `receive()` — status != ARRIVED이면 `OrderErrorCode.RECEIVE_NOT_ALLOWED` throw (향후용, 선언만)

`OrderItem` 엔티티 (`@Table(name = "order_items")`):
- `id` BIGINT PK — `@GeneratedValue(strategy = IDENTITY)`
- `order` — `@ManyToOne(fetch = LAZY)`, `@JoinColumn(name = "order_id", nullable = false)`
- `product` — `@ManyToOne(fetch = LAZY)`, `@JoinColumn(name = "product_id", nullable = false)`
- `quantity` — `@Column(nullable = false)` (DDL: bigint → Java int 또는 long)
- `unitPrice` — `@Column(name = "unit_price", nullable = false)`
- `createdAt` — DDL에 `updated_at` 없음 → BaseEntity 상속 대신 `@Column(name = "created_at")` 직접 선언 또는 별도 처리
- 정적 팩토리: `OrderItem.create(Order order, Product product, int quantity)` — `unitPrice = product.getPrice()`

**검증:**
```bash
cd server && ./gradlew compileJava
```
Expected: BUILD SUCCESSFUL

**커밋:**
```bash
git add server/src/main/java/com/gongu/server/domain/order/entity/
git commit -m "feat: Order, OrderItem 엔티티 및 OrderStatus enum (#72)"
```

---

## Task 2: OrderRepository + OrderItemRepository (Issue #73)

**참고 문서/파일:**
- `server/src/main/java/com/gongu/server/domain/product/repository/ProductRepository.java` — `@Lock`, `@Query` 패턴 참고
- Task 1에서 생성한 `Order.java`, `OrderItem.java`

**수정 대상 파일:**
- Delete: `server/src/main/java/com/gongu/server/domain/order/repository/.gitkeep`
- Create: `server/src/main/java/com/gongu/server/domain/order/repository/OrderRepository.java`
- Create: `server/src/main/java/com/gongu/server/domain/order/repository/OrderItemRepository.java`

**금지 사항:**
- 엔티티 파일 수정 금지

**구현 방향:**

`OrderRepository extends JpaRepository<Order, Long>`:
- `findByIdAndUser(Long orderId, User user): Optional<Order>` — 권한 검증 포함 단건 조회
- `findAllByUserOrderByCreatedAtDesc(User user, Pageable pageable): Page<Order>`
- `findAllByUserAndStatusOrderByCreatedAtDesc(User user, OrderStatus status, Pageable pageable): Page<Order>`
- `findAllByProductOrderByCreatedAtDesc(Product product, Pageable pageable): Page<Order>` — OrderItem join 필요, `@Query` 작성
  ```jpql
  SELECT o FROM Order o JOIN o.orderItems oi WHERE oi.product = :product ORDER BY o.createdAt DESC
  ```

`OrderItemRepository extends JpaRepository<OrderItem, Long>`:
- `findAllByOrder(Order order): List<OrderItem>`

**검증:**
```bash
cd server && ./gradlew compileJava
```
Expected: BUILD SUCCESSFUL

**커밋:**
```bash
git add server/src/main/java/com/gongu/server/domain/order/repository/
git commit -m "feat: OrderRepository, OrderItemRepository (#73)"
```

---

## Task 3: OrderService (Issue #74)

**참고 문서/파일:**
- Spec `server/docs/superpowers/specs/2026-05-18-order-domain-design.md` — 4절 트랜잭션 경계, 5절 비즈니스 규칙
- `server/docs/adr/재고_동시성_제어_전략.md` — 비관적 락 패턴
- `server/src/main/java/com/gongu/server/domain/product/service/ProductService.java` — Service 패턴 참고
- `server/src/main/java/com/gongu/server/domain/product/repository/ProductRepository.java` — `findByIdWithLock` 사용
- `server/src/main/java/com/gongu/server/domain/store/repository/StoreAdminRepository.java` — 관리자 권한 검증 패턴 참고

**수정 대상 파일:**
- Delete: `server/src/main/java/com/gongu/server/domain/order/service/.gitkeep`
- Create: `server/src/main/java/com/gongu/server/domain/order/service/OrderService.java`

**금지 사항:**
- `ProductService.java` 수정 금지 (`decreaseStock` 메서드 직접 사용하지 않고 Repository 통해 직접 처리)
- DTO 파일 생성 금지 (Task 4, 5에서 처리)

**구현 방향:**

클래스 선언: `@Service @RequiredArgsConstructor @Transactional(readOnly = true)`

주입: `OrderRepository`, `OrderItemRepository`, `ProductRepository`, `UserRepository`, `StoreAdminRepository`

**`createOrder(Long userId, Long productId, int quantity)`** — `@Transactional`:
1. `userRepository`로 User 조회 (없으면 `UserErrorCode.USER_NOT_FOUND`)
2. `productRepository.findById(productId)` — 존재·상태(ACTIVE) 확인 (없으면 `ProductErrorCode.PRODUCT_NOT_FOUND`)
3. `productRepository.findByIdWithLock(productId)` — SELECT FOR UPDATE
4. `product.decreaseStock(quantity)` — 재고 검증 + 차감
5. `long totalPrice = (long) product.getPrice() * quantity`
6. `Order order = Order.create(user, totalPrice)`
7. `OrderItem item = OrderItem.create(order, product, quantity)`
8. `order.getOrderItems().add(item)`
9. `orderRepository.save(order)` — cascade로 OrderItem 함께 저장
10. return `order` (DTO 변환은 Controller에서 또는 from() 메서드로)

**`getMyOrders(Long userId, OrderStatus status, Pageable pageable)`**:
- User 조회
- status null이면 `findAllByUserOrderByCreatedAtDesc`, 아니면 `findAllByUserAndStatusOrderByCreatedAtDesc`

**`getOrder(Long userId, Long orderId)`**:
- User 조회
- `orderRepository.findByIdAndUser(orderId, user)` — 없으면 `OrderErrorCode.ORDER_NOT_FOUND`

**`cancelOrder(Long userId, Long orderId, String reason)`** — `@Transactional`:
1. User 조회
2. `orderRepository.findByIdAndUser(orderId, user)` — 없으면 `ORDER_NOT_FOUND`
3. `order.cancel(reason)` — 상태 검증 + 취소 처리 (CANCEL_NOT_ALLOWED throw 포함)
4. `OrderItem item = order.getOrderItems().get(0)`
5. `productRepository.findByIdWithLock(item.getProduct().getId())` — SELECT FOR UPDATE
6. `product.restoreStock(item.getQuantity())`

**`getOrdersByProduct(Long storeAdminId, Long productId, Pageable pageable)`**:
- StoreAdmin 조회 및 권한 검증 (매장 소속 상품인지 `productRepository.findByIdAndStore` 활용)
- `orderRepository.findAllByProductOrderByCreatedAtDesc(product, pageable)`

**`getOrdersByMember(Long storeAdminId, Long memberId, Pageable pageable)`**:
- StoreAdmin 조회
- User 조회
- `orderRepository.findAllByUserOrderByCreatedAtDesc(user, pageable)`

**검증:**
```bash
cd server && ./gradlew compileJava
```
Expected: BUILD SUCCESSFUL

**커밋:**
```bash
git add server/src/main/java/com/gongu/server/domain/order/service/
git commit -m "feat: OrderService 주문 생성·취소 로직 (#74)"
# 조회 로직 추가 후
git commit -m "feat: OrderService 조회 로직 및 관리자 조회 (#74)"
```

---

## Task 4: 고객용 OrderController + DTO (Issue #75)

**참고 문서/파일:**
- `server/src/main/java/com/gongu/server/domain/product/controller/UserProductController.java` — Controller 패턴 참고
- `server/src/main/java/com/gongu/server/domain/product/dto/ProductDetailResponse.java` — Response DTO 패턴 참고
- `server/src/main/java/com/gongu/server/global/common/ApiResponse.java` — 응답 래핑 클래스
- Task 3에서 생성한 `OrderService.java`

**수정 대상 파일:**
- Delete: `server/src/main/java/com/gongu/server/domain/order/dto/.gitkeep`
- Delete: `server/src/main/java/com/gongu/server/domain/order/controller/.gitkeep`
- Create: `server/src/main/java/com/gongu/server/domain/order/dto/CreateOrderRequest.java`
- Create: `server/src/main/java/com/gongu/server/domain/order/dto/CancelOrderRequest.java`
- Create: `server/src/main/java/com/gongu/server/domain/order/dto/OrderItemResponse.java`
- Create: `server/src/main/java/com/gongu/server/domain/order/dto/OrderSummaryResponse.java`
- Create: `server/src/main/java/com/gongu/server/domain/order/dto/OrderDetailResponse.java`
- Create: `server/src/main/java/com/gongu/server/domain/order/controller/OrderController.java`

**금지 사항:**
- `AdminOrderController.java` 생성 금지 (Task 5에서 처리)
- `OrderService.java` 수정 금지

**구현 방향:**

`CreateOrderRequest` (record):
- `Long productId` — `@NotNull`
- `int quantity` — `@Min(1)`

`CancelOrderRequest` (record):
- `String reason`

`OrderItemResponse` (record + static `from(OrderItem)`):
- `Long productId`, `String productName`, `int quantity`, `Long unitPrice`

`OrderSummaryResponse` (record + static `from(Order)`):
- `Long orderId`, `OrderStatus status`, `Long totalPrice`, `String productName` (첫 번째 OrderItem의 상품명), `LocalDateTime createdAt`

`OrderDetailResponse` (record + static `from(Order)`):
- `Long orderId`, `OrderStatus status`, `Long totalPrice`, `List<OrderItemResponse> orderItems`, `LocalDateTime createdAt`, `LocalDateTime cancelledAt`, `String cancelReason`

`OrderController`:
- `@RestController @RequestMapping("/orders") @PreAuthorize("hasRole('MEMBER')")`
- `POST /orders` → `orderService.createOrder(userId, request.productId(), request.quantity())` → `OrderDetailResponse`
- `GET /orders/me` → `orderService.getMyOrders(userId, status, pageable)` → `Page<OrderSummaryResponse>`
  - `@RequestParam(required = false) OrderStatus status`, `@PageableDefault(size = 20) Pageable pageable`
- `GET /orders/{orderId}` → `orderService.getOrder(userId, orderId)` → `OrderDetailResponse`
- `POST /orders/{orderId}/cancel` → `orderService.cancelOrder(userId, orderId, request.reason())` → `void`
- `@AuthenticationPrincipal UserPrincipal`로 userId 추출 (기존 컨트롤러 패턴 참고)
- 모든 응답은 `ApiResponse.success(data)` 래핑

**검증:**
```bash
cd server && ./gradlew compileJava
```
Expected: BUILD SUCCESSFUL

**커밋:**
```bash
git add server/src/main/java/com/gongu/server/domain/order/dto/ \
        server/src/main/java/com/gongu/server/domain/order/controller/OrderController.java
git commit -m "feat: 고객용 OrderController 및 DTO (#75)"
```

---

## Task 5: 관리자용 AdminOrderController + DTO (Issue #76)

**참고 문서/파일:**
- `server/src/main/java/com/gongu/server/domain/product/controller/AdminProductController.java` — 관리자 Controller 패턴 참고
- Task 4에서 생성한 `OrderSummaryResponse.java` — 재사용 여부 판단 (회원명 추가 필요 없으면 재사용)
- Task 3에서 생성한 `OrderService.java`

**수정 대상 파일:**
- Create: `server/src/main/java/com/gongu/server/domain/order/controller/AdminOrderController.java`
- (필요 시) Create: `server/src/main/java/com/gongu/server/domain/order/dto/AdminOrderSummaryResponse.java`

**금지 사항:**
- `OrderController.java` 수정 금지
- `OrderSummaryResponse.java` 에 관리자 전용 필드 추가 금지 — 별도 DTO 생성할 것

**구현 방향:**

`AdminOrderController`:
- `@RestController @RequestMapping("/admin") @PreAuthorize("hasRole('STORE_ADMIN')")`
- `GET /admin/products/{productId}/orders` → `orderService.getOrdersByProduct(storeAdminId, productId, pageable)` → `Page<OrderSummaryResponse>`
- `GET /admin/members/{memberId}/orders` → `orderService.getOrdersByMember(storeAdminId, memberId, pageable)` → `Page<OrderSummaryResponse>`
- `@PageableDefault(size = 20) Pageable pageable`
- `@AuthenticationPrincipal UserPrincipal`로 storeAdminId 추출 (기존 AdminProductController 패턴 참고)

**검증:**
```bash
cd server && ./gradlew compileJava
```
Expected: BUILD SUCCESSFUL

**커밋:**
```bash
git add server/src/main/java/com/gongu/server/domain/order/controller/AdminOrderController.java
git commit -m "feat: 관리자용 AdminOrderController 및 DTO (#76)"
```

---

## 브랜치 및 PR 전략

이슈 순서: `#72 → #73 → #74 → #75 → #76` (선행 이슈 완료 후 다음 이슈 시작)

각 이슈별로:
```bash
# 브랜치 생성 (CONTRIBUTING.md 컨벤션)
git checkout -b feat/#{이슈번호}-{짧은-설명}

# 구현 → 커밋 → push → PR 생성
gh pr create \
  --title "[FEAT] {작업 내용} (#{이슈번호})" \
  --body "close #{이슈번호}" \
  --milestone "주문 도메인"
```

PR 생성 후: `CLAUDE.md` 9~11단계 (Codex 리뷰 위임 → 판정 → 반영) 따름
