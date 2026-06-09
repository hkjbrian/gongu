# OrderExpireService TOCTOU Race Condition Fix — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** `OrderExpireService.cancelExpiredOrder()` 에서 Order 락 획득 후 취소 직전 PENDING Payment 존재 여부를 재확인하여, 쿼리 시점과 락 획득 사이에 preparePayment()가 끼어드는 TOCTOU race condition을 제거한다.

**Spec:** GitHub Issue #141

**Tech Stack:** Spring Boot 3.5, Java 25, JPA/JPQL, Mockito

---

## 문제 요약

`findExpiredReservedOrderIds()`의 `NOT EXISTS(PENDING Payment)` 조건은 쿼리 시점에만 보장된다. 이후 `cancelExpiredOrder()`가 Order 락을 잡기 전에 `preparePayment()`가 실행되면 PENDING Payment가 생성된다. 그 상태에서 Order가 CANCELLED 처리되면 해당 Payment는 고아로 영구 잔존한다.

## 수정 범위 (File Map)

| 파일 | 변경 유형 |
|------|---------|
| `src/main/java/com/gongu/server/domain/order/service/OrderExpireService.java` | Modify |
| `src/test/java/com/gongu/server/domain/order/service/OrderExpireServiceTest.java` | Modify |

**읽기 전용 참조 파일 (수정 금지):**
- `src/main/java/com/gongu/server/domain/payment/repository/PaymentRepository.java` — `existsByOrderIdAndStatusIn(Long orderId, List<PaymentStatus> statuses)` 시그니처 확인
- `src/main/java/com/gongu/server/domain/payment/domain/PaymentStatus.java` — `PENDING` enum 값 확인

---

### Task 1: OrderExpireService에 PENDING Payment 재확인 로직 추가

- [ ] Task 1 완료

**참고 문서/파일 (읽어야 할 것):**
- `src/main/java/com/gongu/server/domain/order/service/OrderExpireService.java` — 현재 구조 파악
- `src/main/java/com/gongu/server/domain/payment/repository/PaymentRepository.java` — `existsByOrderIdAndStatusIn` 메서드 시그니처
- `src/main/java/com/gongu/server/domain/payment/domain/PaymentStatus.java` — `PENDING` 값

**수정 대상 파일:**
- Modify: `src/main/java/com/gongu/server/domain/order/service/OrderExpireService.java`

**금지 사항:**
- `OrderRepository`, `OrderItemRepository`, `ProductRepository` 기존 로직 변경 없음
- `PaymentRepository`에 새 쿼리 메서드 추가 금지 — 이미 존재하는 `existsByOrderIdAndStatusIn` 사용

**구현 방향 (WHAT, not HOW):**
- `OrderExpireService` 생성자에 `PaymentRepository` 의존성 추가 (`@RequiredArgsConstructor` 패턴 유지)
- `cancelExpiredOrder()` 내 **Order 락 획득 및 상태·threshold 검증 통과 직후, `orderItemRepository.findAllByOrder()` 호출 직전**에 아래 조건 삽입:
  - `paymentRepository.existsByOrderIdAndStatusIn(orderId, List.of(PaymentStatus.PENDING))` 가 `true`이면 즉시 `return`
- 이 체크는 Order 락을 보유한 상태에서 수행되어야 한다 (락 획득 이후 라인에 위치)

**검증:**
```bash
cd /Users/hankyungjun/projects/gongu/server
./gradlew compileJava
```
Expected: BUILD SUCCESSFUL, 컴파일 오류 없음

**커밋:** Task 2 완료 후 함께 커밋

---

### Task 2: OrderExpireServiceTest에 TOCTOU 방어 테스트 추가

- [ ] Task 2 완료

**참고 문서/파일 (읽어야 할 것):**
- `src/test/java/com/gongu/server/domain/order/service/OrderExpireServiceTest.java` — 기존 픽스처 헬퍼, Mock 설정 패턴 참고
- `src/main/java/com/gongu/server/domain/payment/repository/PaymentRepository.java` — mock 대상 시그니처 확인

**수정 대상 파일:**
- Modify: `src/test/java/com/gongu/server/domain/order/service/OrderExpireServiceTest.java`

**금지 사항:**
- 기존 테스트 4개 변경 없음
- 새 픽스처 헬퍼 추가는 필요할 때만

**구현 방향 (WHAT, not HOW):**
- `@Mock PaymentRepository paymentRepository` 필드 추가
- 신규 테스트 케이스 1개 추가:
  - 이름: `cancelExpiredOrder_락_후_PENDING_Payment_존재_시_skip`
  - 설정: RESERVED 상태 + threshold 이전 생성된 Order를 `findByIdWithLock`이 반환하도록 설정; `paymentRepository.existsByOrderIdAndStatusIn(1L, List.of(PaymentStatus.PENDING))`이 `true`를 반환하도록 설정
  - 검증: `order.getStatus()`가 여전히 `OrderStatus.RESERVED`; `orderItemRepository.findAllByOrder(order)`가 **never** 호출됨

**검증:**
```bash
cd /Users/hankyungjun/projects/gongu/server
./gradlew test --tests "com.gongu.server.domain.order.service.OrderExpireServiceTest"
```
Expected: 기존 테스트 4개 + 신규 1개, 총 5개 통과

**커밋:**
```bash
git add src/main/java/com/gongu/server/domain/order/service/OrderExpireService.java \
        src/test/java/com/gongu/server/domain/order/service/OrderExpireServiceTest.java
git commit -m "fix: OrderExpireService 락 후 PENDING Payment 재확인으로 TOCTOU 수정 (#141)"
```

---

## PR 생성 후

`CLAUDE.md` 9~11단계(Codex 리뷰 위임 → 판정 → 반영) 따름
