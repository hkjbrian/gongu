# Redis 재고 복원을 트랜잭션 커밋 이후로 이연

- **날짜**: 2026-09-12
- **이슈**: [#188](https://github.com/hkjbrian/gongu/issues/188)
- **상태**: 승인됨

---

## 문제

주문 취소·결제 실패·주문 만료 흐름에서 `stockRedisService.releaseStock()`(Redis increment)이 `@Transactional` 메서드 내부에서 즉시 실행된다. Redis는 트랜잭션 참여자가 아니므로, DB 트랜잭션 커밋이 실패하면 주문 상태는 원복되는데 **Redis 재고만 복원된 상태**가 남는다. Redis 재고가 실제(DB 기준)보다 많아지는 방향이라 초과 판매로 이어질 수 있다.

재조정 스케줄러(`StockReconciliationHelper`)는 `currentStock > correctStock`(Redis 과다) 케이스를 로그만 남기고 복구하지 않으므로(#189), 이 방향의 불일치는 자동 복구되지 않는다.

### 현재 호출 지점 (조사 결과)

| 위치 | 트랜잭션 경계 | releaseStock 호출 시점 |
|------|--------------|------------------------|
| `OrderService.cancelOrder` (`domain/order/service/OrderService.java:100-118`) | `@Transactional` | `order.cancel(reason)` 저장 직후, 메서드 끝 |
| `PaymentService.completePayment` 금액 불일치 분기 (`domain/payment/service/PaymentService.java:100-189`) | `@Transactional(noRollbackFor = {BusinessException.class, InfraException.class})` | `payment.refund()` → `order.cancel()` 이후, 예외 throw 직전 (noRollbackFor로 인해 커밋은 정상 진행됨) |
| `PaymentExpireService.cancelExpiredPayment` (`domain/payment/service/PaymentExpireService.java:30-68`) | `@Transactional(propagation = REQUIRES_NEW)` | `payment.expire()`/`order.cancel()`보다 **먼저** 호출됨 |

`StockRedisService.releaseStock(Long productId, int quantity)`(`domain/product/service/StockRedisService.java:38-41`)는 `stringRedisTemplate.opsForValue().increment(...)`만 수행하는 단순 메서드로, 별도 보상 로직이 없다.

코드베이스 전체(src/main, src/test)에 `TransactionSynchronization`/`TransactionSynchronizationManager` 사용 전례가 없다 — 새로 도입한다.

---

## 결정: `StockRedisService`에 캡슐화

### 검토한 선택지

| 선택지 | 판단 |
|--------|------|
| **A안 (채택) — `StockRedisService.releaseStockAfterCommit()` 신설** | 트랜잭션 동기화 지식이 `StockRedisService` 내부에만 존재. 호출부 3곳은 메서드 이름만 교체하면 됨. |
| B안 — 호출부마다 `TransactionSynchronizationManager` 직접 사용 | 보일러플레이트가 3곳에 중복됨. `StockRedisService`는 이 관심사를 전혀 알지 못해 재고 도메인 지식이 흩어짐. |
| C안 — `@TransactionalEventListener(phase = AFTER_COMMIT)` 이벤트 기반 | 이벤트 클래스 + 별도 리스너 빈이 추가로 필요. 내부적으로 A안과 동일한 메커니즘을 우회해서 쓰는 것이라 간접성만 늘어남. 재사용처가 없어 YAGNI 위반. |

### A안 이유

- 재고 관련 지식(Redis 키 구조, 증감 연산)은 이미 `StockRedisService`가 소유. "언제 반영할지"도 같은 클래스가 책임지는 것이 응집도에 맞다.
- 호출부(`OrderService`, `PaymentService`, `PaymentExpireService`)는 `TransactionSynchronizationManager` API를 몰라도 된다.

---

## 변경 범위

| 레이어 | 파일 | 변경 내용 |
|--------|------|-----------|
| Service | `StockRedisService.java` | `releaseStockAfterCommit(Long productId, int quantity)` 메서드 추가 |
| Service | `OrderService.java` | `cancelOrder`에서 `releaseStock` → `releaseStockAfterCommit` 교체 |
| Service | `PaymentService.java` | `completePayment` 금액 불일치 분기에서 동일 교체 |
| Service | `PaymentExpireService.java` | `cancelExpiredPayment`에서 동일 교체 + 호출 순서를 `payment.expire()`/`order.cancel()` 이후로 이동 |
| Test | `StockRedisServiceTest.java` (신규 또는 기존 파일에 추가) | `releaseStockAfterCommit` 커밋/롤백 동작 검증 |
| Test | `OrderServiceTest.java`, `PaymentServiceTest.java`, `PaymentExpireServiceTest.java` | `verify(...).releaseStock(...)` → `verify(...).releaseStockAfterCommit(...)`로 갱신 |

엔티티·리포지토리·API 스펙 변경 없음.

---

## 설계 상세

### `StockRedisService.releaseStockAfterCommit`

```java
public void releaseStockAfterCommit(Long productId, int quantity) {
    if (!TransactionSynchronizationManager.isSynchronizationActive()) {
        // 방어적 fallback: 활성 트랜잭션이 없으면 즉시 반영 (현재 재고 유실 방지)
        releaseStock(productId, quantity);
        return;
    }
    TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
        @Override
        public void afterCommit() {
            try {
                releaseStock(productId, quantity);
            } catch (Exception e) {
                // Redis 부족 방향 불일치 → StockReconciliationHelper가 자동 복구 대상
                log.error("커밋 후 Redis 재고 복원 실패: productId={}, quantity={}", productId, quantity, e);
            }
        }
    });
}
```

- `afterCommit()`은 DB 트랜잭션이 성공적으로 커밋된 **직후**, 같은 스레드에서 동기적으로 실행된다. 커밋이 실패(예외로 인한 롤백)하면 `afterCommit()`은 호출되지 않는다 — 이것이 이번 수정의 핵심.
- 콜백 내부에서 예외를 직접 catch하여 로그만 남긴다. Spring의 `afterCommit` 예외 전파 방식에 의존하지 않는다.
- 활성 트랜잭션이 없는 상태에서 호출되는 경우(현재 3개 호출부는 모두 `@Transactional` 내부라 발생하지 않지만, 향후 오용 방지 차원의 방어 코드)는 즉시 실행하여 재고 복원 누락을 막는다.

### 호출부 변경 (예시: `OrderService.cancelOrder`)

```java
// before
items.forEach(item -> stockRedisService.releaseStock(item.getProductId(), item.getQuantity()));

// after
items.forEach(item -> stockRedisService.releaseStockAfterCommit(item.getProductId(), item.getQuantity()));
```

`PaymentService.completePayment`도 동일하게 메서드명만 교체한다. `noRollbackFor`로 인해 예외가 throw되어도 트랜잭션은 커밋되므로, 등록된 동기화의 `afterCommit()`은 정상적으로 실행된다.

`PaymentExpireService.cancelExpiredPayment`는 메서드명 교체와 함께, 현재 `releaseStock` 호출이 `payment.expire()`/`order.cancel()`보다 먼저 실행되는 순서를 뒤로 옮긴다. 기능적으로는 어느 순서든 커밋 전이므로 동일하게 동작하지만, 다른 두 호출부와 "엔티티 상태 변경 → 재고 복원 예약" 순서를 맞춰 가독성을 높인다.

---

## 에러 처리

- afterCommit 콜백 내 Redis 호출 실패는 로그로만 남기고 예외를 전파하지 않는다.
- 이 방향(Redis 재고 < DB 기준)은 기존 `StockReconciliationHelper`가 이미 자동 복구 대상으로 처리한다(`currentStock < correctStock` 케이스). 재조정 스케줄러 로직 자체는 이번 스코프에서 변경하지 않는다.

---

## 테스트 전략

### `StockRedisServiceTest` — `releaseStockAfterCommit` 자체 검증 (신규)

Spring 컨텍스트 없이 순수 단위테스트로 작성한다. `TransactionSynchronizationManager.initSynchronization()`으로 스레드-로컬 동기화 상태를 수동 초기화한 뒤:

1. **커밋 시 반영**: `releaseStockAfterCommit()` 호출 → `TransactionSynchronizationUtils.triggerAfterCommit()` 호출 → `stringRedisTemplate...increment(...)`가 호출됨을 검증.
2. **롤백 시 미반영 (이슈의 완료 기준)**: `releaseStockAfterCommit()` 호출 → 실제 `releaseStock()`을 호출하지 않고 `TransactionSynchronizationUtils.triggerAfterCompletion(STATUS_ROLLED_BACK)` 호출(afterCommit이 실행되지 않음을 시뮬레이션) → increment가 호출되지 **않음**을 검증.
3. **활성 트랜잭션 없음**: `initSynchronization()` 없이 호출 → 즉시 increment가 호출됨을 검증.
4. 각 테스트 종료 시 `TransactionSynchronizationManager.clearSynchronization()`으로 정리.

### 기존 테스트 갱신

`OrderServiceTest`, `PaymentServiceTest`, `PaymentExpireServiceTest`의 관련 케이스에서 `verify(stockRedisService).releaseStock(...)` → `verify(stockRedisService).releaseStockAfterCommit(...)`로 변경한다. 이 테스트들은 호출부가 올바른 메서드로 위임하는지만 검증하며, 실제 afterCommit 타이밍 검증은 `StockRedisServiceTest`가 책임진다.

---

## 범위 제외

- `StockReconciliationHelper`의 `currentStock > correctStock`(Redis 과다) 자동 복구는 별도 이슈(#189)로, 이번 스코프에 포함하지 않는다.

---

## 완료 기준 (이슈 원문)

- [ ] 버그 재현 불가 확인 (커밋 실패 시 Redis 재고 미복원 검증 테스트)
- [ ] 회귀 없음 확인 (정상 취소/만료/금액 불일치 흐름에서 재고 복원 동작 유지)
- [ ] 단일 PR로 머지 가능
