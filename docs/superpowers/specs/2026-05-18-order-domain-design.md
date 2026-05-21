# Order 도메인 설계 스펙

- **날짜**: 2026-05-18
- **마일스톤**: #6 주문 도메인
- **상태**: 승인됨

---

## 1. 범위

### 이번 마일스톤 포함

| API | 역할 | 상태 전이 |
|-----|------|-----------|
| `POST /orders` | 고객 — 상품 예약 | → RESERVED |
| `GET /orders/me` | 고객 — 내 주문 목록 | 조회 전용 |
| `GET /orders/{orderId}` | 고객 — 주문 상세 | 조회 전용 |
| `POST /orders/{orderId}/cancel` | 고객 — 주문 취소 | RESERVED → CANCELLED |
| `GET /admin/products/{productId}/orders` | 매장 관리자 — 상품별 주문 | 조회 전용 |
| `GET /admin/members/{memberId}/orders` | 매장 관리자 — 회원 주문 이력 | 조회 전용 |

### 이번 마일스톤 제외 (미래 마일스톤)

| 항목 | 이유 |
|------|------|
| `POST /orders/{id}/receive` (ARRIVED → RECEIVED) | 결제 마일스톤 이후 |
| `PUT /admin/products/{id}/arrive` (입고 처리) | Product 상태 변경 마일스톤 |
| `PAID → CANCELLED` 취소 | 결제 마일스톤에서 보상 트랜잭션과 함께 |
| Notification (입고 알림, 예약 확정) | 알림 마일스톤 (#8) |
| Product 수동 마감 (ACTIVE → CLOSED) | Product 상태 변경 마일스톤 |
| 재고 소진 자동 SOLD_OUT 전환 | Product 상태 변경 마일스톤 |

---

## 2. 상태 전이 다이어그램

```
         주문 생성
            │
            ▼
        RESERVED ──────── 취소 가능 (이번 마일스톤)
            │
            │ 결제 완료 (결제 마일스톤)
            ▼
          PAID ─────────── 취소 가능 (결제 마일스톤)
            │
            │ 입고 처리 (Product 상태 변경 마일스톤)
            ▼
         ARRIVED
            │
            │ 수령 완료 (결제 마일스톤 이후)
            ▼
        RECEIVED

       (취소 시 → CANCELLED)
```

---

## 3. 엔티티 설계

### Order (`orders` 테이블)

| 필드 | 타입 | DDL 컬럼 | 비고 |
|------|------|----------|------|
| id | Long | id BIGINT PK | |
| member | Member | user_id BIGINT FK | |
| status | OrderStatus | status VARCHAR(20) | enum |
| totalPrice | Long | total_price BIGINT | |
| cancelledAt | LocalDateTime | cancelled_at DATETIME NULL | 취소 시 기록 |
| cancelReason | String | cancel_reason VARCHAR(255) NULL | 취소 시 기록 (취소 전 NULL, 취소 시 @NotBlank 강제) |
| createdAt | LocalDateTime | created_at DATETIME | BaseEntity |
| updatedAt | LocalDateTime | updated_at DATETIME | BaseEntity |

**도메인 메서드:**
- `Order.create(member, totalPrice)` — 정적 팩토리, status = RESERVED
- `cancel(reason)` — RESERVED 아니면 `ORDER_NOT_CANCELLABLE` throw
- `pay()` — RESERVED → PAID (결제 마일스톤)
- `arrive()` — PAID → ARRIVED (Product 상태 변경 마일스톤)
- `receive()` — ARRIVED → RECEIVED (결제 마일스톤 이후)

**연관관계:**
단방향 `@ManyToOne`만 사용 (ADR-006). `Order`는 `OrderItem` 컬렉션을 보유하지 않는다.
`orderItemRepository.save(item)`으로 별도 저장.

### OrderItem (`order_items` 테이블)

| 필드 | 타입 | DDL 컬럼 | 비고 |
|------|------|----------|------|
| id | Long | id BIGINT PK | |
| order | Order | order_id BIGINT FK | |
| product | Product | product_id BIGINT FK | |
| quantity | int | quantity BIGINT | |
| unitPrice | Long | unit_price BIGINT | 주문 시점 가격 스냅샷 |
| createdAt | LocalDateTime | created_at DATETIME | |

**도메인 메서드:**
- `OrderItem.create(order, product, quantity)` — `product.getPrice()`를 unitPrice로 스냅샷

### OrderStatus

```java
RESERVED, PAID, ARRIVED, RECEIVED, CANCELLED
```

---

## 4. 트랜잭션 경계

### 주문 생성 (`createOrder`)

```
@Transactional
createOrder(memberId, productId, quantity):

1. userRepository.findById(memberId)            ← User 조회
2. productRepository.findById(productId)        ← 상태/존재 확인 (락 없이)
3. productRepository.findByIdWithLock(productId) ← SELECT FOR UPDATE (락 획득)
4. product.decreaseStock(quantity)              ← 재고 검증 + 차감
5. Order order = Order.create(user, unitPrice * quantity)
6. orderRepository.save(order)                 ← INSERT order (ID 확보)
7. OrderItem item = OrderItem.create(order, product, quantity)
   └─ unitPrice = product.getPrice() (락 보유 중이므로 일관성 보장)
8. orderItemRepository.save(item)              ← INSERT order_item (ADR-006: 단방향, 별도 저장)
9. Commit (락 해제)
```

> **주의**: 단계 3 이전에 Product를 락 없이 한 번 조회하는 이유는 상태 검증(ACTIVE 여부 등)과 존재 확인을 위해서다. 실제 재고 차감은 반드시 락을 잡은 단계 3 이후에만 수행한다.

> **가격 스냅샷**: unitPrice는 락 획득 이후 읽은 `product.getPrice()`를 사용한다. 락 획득 전 가격을 사용하면 가격 변경과의 TOCTOU 문제가 발생할 수 있다.

### 주문 취소 (`cancelOrder`)

```
@Transactional
cancelOrder(memberId, orderId, reason):

1. orderRepository.findByIdAndUser(orderId, user)     ← 권한 검증 + 조회
2. order.cancel(reason)                              ← RESERVED 검증 + 상태 CANCELLED + cancelledAt 기록
3. OrderItem item = orderItemRepository.findByOrder(order) ← 아이템 조회 (ADR-006: 단방향)
4. productRepository.findByIdWithLock(item.getProduct().getId()) ← SELECT FOR UPDATE
5. product.restoreStock(item.getQuantity())          ← 재고 복구
6. Commit (락 해제)
```

> **재고 복구에도 비관적 락 사용**: 취소 + 동시 주문이 교차하는 경우 재고 일관성 보장. ADR-005 원칙 연장.

---

## 5. 비즈니스 규칙

| 규칙 | 내용 |
|------|------|
| 중복 주문 | 제한 없음 — 동일 회원이 동일 상품을 여러 번 예약 가능 |
| 최소 수량 | 1 이상 (컨트롤러 `@Min(1)` 검증) |
| 취소 가능 상태 | RESERVED만 (이번 마일스톤) |
| 재고 부족 | `INSUFFICIENT_STOCK` 에러 즉시 반환 |
| 권한 검증 | `findByIdAndMember`로 타인 주문 접근 차단 |
| 가격 스냅샷 | unitPrice는 주문 시점 고정, 이후 상품 가격 변경과 무관 |

---

## 6. 에러 코드 (OrderErrorCode)

| 코드 | 상황 |
|------|------|
| ORDER_NOT_FOUND | 주문 없음 또는 접근 권한 없음 |
| ORDER_NOT_CANCELLABLE | RESERVED 상태가 아닌 주문 취소 시도 |

기존 `ProductErrorCode.INSUFFICIENT_STOCK`, `ProductErrorCode.PRODUCT_NOT_FOUND`도 활용.

---

## 7. Request DTO

### CancelOrderRequest

| 필드 | 타입 | 검증 | 비고 |
|------|------|------|------|
| reason | String | `@NotBlank` | 필수 취소 사유 |

---

## 8. 이슈 구성

| 이슈 | 내용 |
|------|------|
| 이슈 1 | Order + OrderItem 엔티티, OrderStatus enum |
| 이슈 2 | OrderRepository + OrderItemRepository  |
| 이슈 3 | OrderService (생성/취소/조회 비즈니스 로직) |
| 이슈 4 | 고객용 OrderController + DTO |
| 이슈 5 | 관리자용 AdminOrderController + DTO |
