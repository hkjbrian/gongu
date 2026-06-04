# Payment / Order 만료 취소 연동 및 결제 완료 후 보상 처리 설계

**이슈**: #139  
**작성일**: 2026-06-03

---

## 배경

Issue #136에서 구현한 Order 만료 스케줄러에서 두 가지 미해결 설계 문제가 도출되었다.

1. PENDING Payment가 있는 Order가 만료될 때 Payment가 orphan으로 남음
2. PortOne 결제 완료 직후 스케줄러가 Order를 취소하는 순차적 race condition

---

## Payment 상태 머신 변경

### 현재

```
PENDING → PAID
        → CANCELLED  (금액 불일치 cancelByMismatch, 미래 사용자 취소)
        → FAILED     (PG 오류)
```

### 변경 후

```
PENDING → PAID
        → CANCELLED  (스케줄러 만료 처리 — 돈 이동 없음)
        → REFUNDED   (금액 불일치, race condition 보상 — 돈 이동 후 반환)
        → FAILED     (PG 오류)

PAID    → REFUNDED   (사용자 취소 등)
```

**구분 기준: 돈의 이동 여부**

| 상태 | 의미 | 돈 이동 |
|------|------|---------|
| CANCELLED | PENDING 단계에서 정리됨 | 없음 |
| FAILED | PG 처리 실패 | 없음 |
| REFUNDED | 결제 완료 후 역방향 이동 (이유 무관) | 수취 후 반환 |

### Payment 메서드 변경

| 기존 | 변경 | 전이 | 비고 |
|------|------|------|------|
| `cancelByMismatch()` | `refund()` | PENDING → REFUNDED | 금액 불일치 시 PortOne 환불 후 호출 |
| `cancel()` | `refund()` | PAID → REFUNDED | 사용자 취소 등 미래 사용 |
| (없음) | `expire()` | PENDING → CANCELLED | 스케줄러 만료 전용 |

`refund()`는 PENDING, PAID 두 상태를 모두 허용하도록 내부에서 분기한다.

---

## 문제 1: PENDING Payment 만료 처리 — 스케줄러 분리

### 설계

기존 단일 스케줄러를 두 개로 분리한다.

#### Payment 취소 스케줄러 (신규: PaymentExpireService)

- **조건**: PENDING payment가 존재하는 RESERVED order, `createdAt < threshold`
- **락 순서**: Payment 먼저 → Order (`completePayment()`와 동일 → 데드락 없음)
- **처리**: `payment.expire()` + `order.cancel("결제 시간 초과")`

```java
// 락 획득 후 double-check 필수
Payment payment = paymentRepository.findByIdWithLock(paymentId);
if (payment.getStatus() != PaymentStatus.PENDING) return;

Order order = orderRepository.findByIdWithLock(payment.getOrder().getId());
if (order.getStatus() != OrderStatus.RESERVED) return;
if (!order.getCreatedAt().isBefore(threshold)) return;

payment.expire();
order.cancel("결제 시간 초과");
```

#### Order 취소 스케줄러 (기존: OrderExpireService)

- **조건**: RESERVED order, `createdAt < threshold`, PENDING payment 없음 (NOT EXISTS)
- **락 순서**: Order만 (Payment 없으므로 충돌 불가)
- **처리**: `order.cancel("결제 시간 초과")`

```sql
SELECT o FROM orders o
WHERE o.status = 'RESERVED'
  AND o.created_at < :threshold
  AND NOT EXISTS (
    SELECT 1 FROM payments p
    WHERE p.order_id = o.id AND p.status = 'PENDING'
  )
```

### TTL 설정

두 스케줄러 모두 동일한 임계값 사용: **PortOne TTL(15분)보다 긴 20분**

| 설정 | 값 |
|------|-----|
| PortOne 결제 세션 만료 | 15분 |
| Order / Payment 만료 임계값 | 20분 |

Order TTL과 Payment TTL을 다르게 설정하면 Payment는 취소됐는데 Order가 RESERVED인 limbo 상태가 발생하므로 동일하게 유지한다.

### 락 순서 정합성

| 경로 | 락 순서 |
|------|---------|
| `completePayment()` | Payment → Order |
| Payment 취소 스케줄러 | Payment → Order |
| Order 취소 스케줄러 | Order만 |

Payment 취소 스케줄러와 `completePayment()`의 락 순서가 동일하므로 데드락이 발생하지 않는다. 락 획득 이후 반드시 double-check를 수행하여 이미 처리된 케이스를 안전하게 skip한다.

---

## 문제 2: completePayment() race condition 보상 처리

### 시나리오

```
T=19:59  사용자가 PortOne에 결제 정보 제출 → PortOne 처리 완료 (돈 빠져나감)
T=20:00  스케줄러 실행 → Payment: CANCELLED, Order: CANCELLED
T=20:05  PortOne webhook / client verify 도착
          → completePayment(): Order가 이미 CANCELLED
          → 돈은 빠져나갔지만 아무 처리도 안 된 상태
```

### 보상 트랜잭션

`completePayment()`에서 Order 락 획득 후 CANCELLED 상태를 감지하면 즉시 PortOne 환불을 호출한다.

```java
Order order = orderRepository.findByIdWithLock(payment.getOrder().getId());

if (order.getStatus() == OrderStatus.CANCELLED) {
    portOneClient.cancelPayment(paymentId, "주문 만료로 인한 자동 환불");
    payment.refund();  // PENDING → REFUNDED
    throw new BusinessException(PaymentErrorCode.ORDER_EXPIRED_REFUNDED);
    // noRollbackFor → REFUNDED 상태 커밋 보장
}
```

기존 `noRollbackFor = {BusinessException.class, InfraException.class}` 설정이 그대로 적용되어 REFUNDED 상태가 커밋된다.

### 두 보상 경로 비교

| 경로 | 트리거 | Payment 전이 |
|------|--------|-------------|
| 금액 불일치 | `completePayment()` 내 금액 검증 실패 | PENDING → REFUNDED |
| Race condition | Order CANCELLED 감지 | PENDING → REFUNDED |
| 스케줄러 만료 | 스케줄러 정상 실행 | PENDING → CANCELLED |

---

## 에러 코드 추가

| 코드 | 설명 |
|------|------|
| `ORDER_EXPIRED_REFUNDED` | 주문 만료로 인해 결제가 자동 환불됨 |

---

## 테스트 시나리오

### Payment 취소 스케줄러
- PENDING payment가 있는 만료 Order → Payment CANCELLED, Order CANCELLED, 재고 복구
- completePayment()가 먼저 실행 후 스케줄러 실행 → double-check로 skip, 상태 유지
- PENDING payment 없는 만료 Order → Order 취소 스케줄러가 처리, Payment 스케줄러는 무시

### Order 취소 스케줄러
- PENDING payment 없는 만료 Order → Order CANCELLED, 재고 복구
- PENDING payment 있는 만료 Order → NOT EXISTS 조건으로 skip

### completePayment() 보상
- Order가 CANCELLED인 상태에서 PortOne PAID 응답 → PortOne 환불 호출, Payment REFUNDED
- 정상 흐름 (Order RESERVED) → 기존과 동일하게 PAID 처리
