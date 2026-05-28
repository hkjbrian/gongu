# Domain Rules

도메인 엔티티가 반드시 강제해야 하는 불변식과 상태 전이 규칙을 정의한다.
이 규칙은 서비스 레이어가 아닌 **엔티티 도메인 메서드 안에서** 강제된다 (ADR-002).

---

## Product

- `price`는 0보다 커야 한다.
- `totalStock`은 0보다 커야 한다.
- `startAt`은 `endAt`보다 이전이어야 한다.
- `remainingStock`은 0 미만이 될 수 없다.
- `remainingStock`은 `totalStock`을 초과할 수 없다.

### 상태 전이

```
UPCOMING → ACTIVE    (판매 시작)
ACTIVE   → SOLD_OUT  (잔여 재고 0)
ACTIVE   → CLOSED    (판매 종료)
SOLD_OUT → ACTIVE    (재고 복원 시 — 주문 취소로 restoreStock() 호출)
SOLD_OUT → CLOSED    (판매 종료)
```

- UPCOMING, ACTIVE 이외 상태의 상품에는 주문할 수 없다.
- 상태 전이는 Product 도메인 메서드를 통해서만 이루어진다.

---

## Order

### 상태 전이

```
RESERVED → PAID       (결제 완료)
RESERVED → CANCELLED  (결제 전 취소)
PAID     → ARRIVED    (입고 완료, 관리자)
PAID     → CANCELLED  (결제 후 취소)
ARRIVED  → RECEIVED   (수령 완료, 회원)
```

- ARRIVED, RECEIVED 상태의 주문은 취소할 수 없다.
- CANCELLED 상태에서 다른 상태로 전이할 수 없다.
- 허용되지 않은 상태 전이 시도는 예외를 발생시킨다.

### 불변식

- 주문 생성 시 반드시 하나 이상의 OrderItem이 포함되어야 한다.
- `totalPrice`는 `OrderItem.unitPrice × quantity`의 합과 같아야 한다.
- `OrderItem.unitPrice`는 주문 시점의 Product 가격 스냅샷이며 이후 변경되지 않는다.

---

## Stock (재고)

- 주문 생성 시 `Product.remainingStock`을 즉시 차감한다 (ADR-005: 비관적 락).
- 주문 취소 시 차감됐던 재고를 즉시 복구한다.
- 재고 차감·복구는 **Order 상태 변경과 반드시 같은 트랜잭션** 안에서 처리한다.
- 재고 차감과 복구는 `Product.decreaseStock()` / `Product.restoreStock()` 도메인 메서드를 통해서만 이루어진다.

---

## Payment

- 결제는 반드시 RESERVED 상태의 주문에 대해서만 준비(prepare)할 수 있다.
- 결제 준비(`preparePayment`) 시 서버가 paymentId(UUID)를 생성하여 Payment PENDING 레코드를 선(先) 저장한다.
- 결제 완료(`completePayment`) 시 PortOne에서 반환된 결제 금액이 주문 `totalPrice`와 일치해야 결제가 확정된다.
- 금액 불일치 시 PortOne에 취소 요청을 보내고 Payment는 CANCELLED, 주문은 CANCELLED 처리한다.
- Payment가 이미 PAID 상태이면 `completePayment` 재호출은 멱등 처리(즉시 리턴)한다.
- 결제 확정 시 Order 상태를 PAID로 전이한다 (같은 트랜잭션 안에서).

---

## User / Store 접근 제어

- 회원은 자신이 가입한 매장의 상품만 조회·주문할 수 있다.
- 매장 관리자는 자신의 매장 상품과 회원 정보만 관리할 수 있다.
- 미가입 매장 자원 접근 시 존재하지 않는 것처럼 응답한다 (정보 노출 방지).
