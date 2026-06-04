# ADR-007: Payment 만료 취소 설계

- **날짜**: 2026-06-03
- **상태**: 승인됨

---

## 배경

Issue #136에서 RESERVED Order TTL 만료 스케줄러를 구현하면서 두 가지 미해결 설계 문제가 도출되었다.

1. `preparePayment()` 호출 후 PENDING 상태인 Payment가 존재할 때, Order 만료 스케줄러가 Order만 CANCELLED 처리하면 Payment가 PENDING orphan으로 남는다.
2. PortOne 내부에서 결제가 완료된 직후, 스케줄러가 Order를 CANCELLED 처리하면 PG 상태(결제 완료)와 DB 상태(CANCELLED) 간 불일치가 발생한다.

---

## 결정 1: PaymentStatus에 REFUNDED 추가

**기준: 돈의 이동 여부**

| 상태 | 의미 |
|------|------|
| `CANCELLED` | 돈 이동 없이 만료된 경우 (스케줄러 만료 처리) |
| `REFUNDED` | 돈이 이동했다가 반환된 경우 (금액 불일치, race condition 보상) |

**선택하지 않은 대안**: CANCELLED로 모든 경우를 통합 처리  
**선택하지 않은 근거**: 취소와 환불은 회계·감사 이력 관점에서 명확히 구분해야 하며, 실제 돈의 흐름이 다른 두 케이스를 동일 상태로 관리하면 정산 오류 가능성이 생긴다.

---

## 결정 2: 스케줄러 분리 (Payment 취소 스케줄러 + Order 취소 스케줄러)

**Payment 취소 스케줄러**
- 대상: PENDING Payment가 있는 만료 Order
- 처리: Payment(CANCELLED) + Order(CANCELLED) 함께 처리
- 락 순서: Payment → Order (`completePayment()`와 동일 순서, 데드락 방지)

**Order 취소 스케줄러**
- 대상: PENDING Payment가 없는 만료 RESERVED Order
- 처리: Order(CANCELLED)만 처리
- 락 순서: Order만

**TTL 설정**

| 항목 | 값 | 설정 키 |
|------|-----|---------|
| PortOne 결제 세션 만료 | 15분 | — (PortOne 정책) |
| Order/Payment 만료 임계값 | 20분 | `order.reservation-ttl-minutes` |

두 스케줄러 모두 동일한 20분 임계값을 사용하며, PortOne TTL(15분)보다 길게 설정해 PG 세션이 먼저 만료된 뒤 스케줄러가 정리하는 순서를 보장한다.

**선택하지 않은 대안 1**: 단일 스케줄러에서 PENDING Payment 존재 시 skip  
**선택하지 않은 근거**: 사용자가 `preparePayment()`만 하고 이탈하면 Order가 영구 RESERVED 상태로 남아 재고가 묶인다.

**선택하지 않은 대안 2**: 단일 스케줄러에서 PENDING Payment 존재 시 조건 분기  
**선택하지 않은 근거**: 단일 스케줄러 내에서 두 락 경로(Payment → Order, Order만)가 혼재하면 락 순서 추론이 복잡해진다. 스케줄러를 분리하면 각 스케줄러의 락 순서가 단순 명확해진다.

**핵심 근거**: `completePayment()`는 Payment 락 → Order 락 순서로 동작한다. Payment 취소 스케줄러가 동일한 순서를 유지함으로써 데드락을 원천 차단한다.

---

## 결정 3: completePayment() race condition 보상 트랜잭션

**문제 시나리오**

```text
T=19:59  사용자가 PG에 결제 정보 제출 → PortOne 내부 결제 처리 완료 (돈 빠져나감)
T=20:00  Payment 취소 스케줄러 실행 → Payment: CANCELLED, Order: CANCELLED, 재고 복구
T=20:01  PortOne webhook / client verify 도착 → Order가 이미 CANCELLED
           → completePayment() 진입 → Order CANCELLED 감지 → 보상 트랜잭션 실행
```

**보상 트랜잭션 흐름**

```text
Order CANCELLED 감지
  → PortOne 취소 API 호출 (환불)
  → payment.refund()  (Payment: REFUNDED)
  → ORDER_EXPIRED_REFUNDED 예외 발생
  → 사용자에게 "주문 만료로 자동 환불" 알림
```

**선택하지 않은 대안**: TTL 마진만으로 race condition 방지 (PortOne TTL < 우리 TTL)  
**선택하지 않은 근거**: PortOne TTL이 우리 TTL보다 짧더라도 webhook 지연 케이스를 100% 차단할 수 없다. PortOne이 세션을 만료시킨 뒤에도 이미 처리된 결제의 webhook은 늦게 도착할 수 있다. TTL 설정은 1차 방어선이고, 보상 트랜잭션은 그 뒤를 막는 안전망이다.

---

## 최종 비교

| 항목 | 채택안 | 선택하지 않은 대안 |
|------|--------|-------------------|
| Payment 상태 구분 | CANCELLED / REFUNDED 분리 | CANCELLED 단일 상태로 통합 |
| PENDING Payment 만료 처리 | 스케줄러 분리 (Payment 취소 + Order 취소) | 단일 스케줄러 skip 또는 분기 |
| Race condition 처리 | 보상 트랜잭션 (PortOne 환불 + REFUNDED) | TTL 마진만으로 방지 |

---

## 결과

**긍정적 영향**
- PENDING Payment orphan 발생 원천 차단
- `completePayment()`와 동일한 락 순서 유지 → 데드락 위험 없음
- PG 결제 완료 후 Order 만료 케이스에서 사용자 환불 자동 처리
- CANCELLED/REFUNDED 구분으로 정산·감사 이력 명확

**부정적 영향 및 제약**
- 스케줄러 2개 유지 필요 (운영 관리 포인트 증가)
- PortOne 환불 API 연동 필요
- `completePayment()` 내에 보상 트랜잭션 분기 추가로 복잡도 소폭 증가

**향후 고려 사항**
- PortOne webhook 재시도 정책과 우리 시스템의 멱등성 처리 연계 강화
- Payment 상태 전이를 State Machine 패턴으로 명시화하는 방향 검토 (현재 규모에서는 오버엔지니어링)
