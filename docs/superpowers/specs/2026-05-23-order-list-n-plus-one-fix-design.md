# 주문 목록 조회 N+1 해결 설계

- **날짜**: 2026-05-23
- **이슈**: [#83](https://github.com/hkjbrian/gongu/issues/83)
- **상태**: 승인됨

---

## 문제

`getMyOrders`, `getOrdersByProduct`, `getOrdersByUser` 세 메서드에서 페이지당 N건의 주문을 조회할 때:

```
1 query  — Page<Order> 조회
N query  — orderItemRepository.findAllByOrder(order) (각 Order마다)
N query  — item.getProduct().getName() 에서 Product lazy 로딩
```

페이지 20건 기준 **41 queries** 발생.

---

## 결정: C안 — IN절 벌크 쿼리

### 선택 이유

| 선택지 | 판단 |
|--------|------|
| A안 (fetch join + DTO projection) | 페이지네이션과 JOIN FETCH 병용 시 Hibernate가 전체 데이터를 메모리에 올린 뒤 자름. ADR-006 하에서 Order에 orderItems 컬렉션 자체가 없어 fetch join 대상 없음. |
| B안 (@BatchSize) | 현재 코드는 명시적 루프 호출이므로 @BatchSize 미적용. 적용하려면 Order에 @OneToMany 추가 필요 → ADR-006 위반. |
| **C안 (IN절 벌크 쿼리)** | **ADR-006 완전 준수. 2 queries 고정. 코드 이해 명확.** |

---

## 변경 범위

총 2개 파일만 수정. 엔티티, DTO, Controller 무변경.

| 레이어 | 파일 |
|--------|------|
| Repository | `OrderItemRepository` |
| Service | `OrderService` |

---

## 설계 상세

### OrderItemRepository — 쿼리 메서드 추가

```java
@Query("SELECT oi FROM OrderItem oi JOIN FETCH oi.product WHERE oi.order IN :orders")
List<OrderItem> findAllByOrderInWithProduct(@Param("orders") List<Order> orders);
```

- `JOIN FETCH oi.product` → Product lazy 로딩 방지
- 반환 타입 `List` → 페이지네이션 간섭 없음

### OrderService — 공통 헬퍼 추출

3개 목록 조회 메서드의 반복 로직을 private 헬퍼로 통합:

```java
private Page<OrderSummaryResponse> toSummaryPage(Page<Order> orders) {
    if (orders.isEmpty()) return orders.map(o -> null);

    List<OrderItem> items = orderItemRepository
        .findAllByOrderInWithProduct(orders.getContent());

    Map<Long, OrderItem> itemByOrderId = items.stream()
        .collect(Collectors.toMap(item -> item.getOrder().getId(), item -> item));

    return orders.map(order -> {
        OrderItem item = itemByOrderId.get(order.getId());
        if (item == null) throw new BusinessException(OrderErrorCode.ORDER_ITEM_NOT_FOUND);
        return OrderSummaryResponse.of(order, item);
    });
}
```

**빈 페이지 처리**: `orders.getContent()`가 비어있을 때 IN절에 빈 리스트를 넘기면 일부 DB/JPA 구현체에서 오류 발생 가능 → early return.

### 호출부 (3곳 동일)

```java
// before
return orders.map(order -> {
    List<OrderItem> items = orderItemRepository.findAllByOrder(order);
    return OrderSummaryResponse.of(order, items.stream().findFirst()
            .orElseThrow(() -> new BusinessException(OrderErrorCode.ORDER_ITEM_NOT_FOUND)));
});

// after
return toSummaryPage(orders);
```

---

## 쿼리 수 비교

| 메서드 | 기존 (페이지 20건) | 변경 후 |
|--------|-------------------|---------|
| `getMyOrders` | 41 | **2** |
| `getOrdersByProduct` | 41 | **2** |
| `getOrdersByUser` | 41 | **2** |

---

## 테스트 전략

`OrderServiceTest`에 추가할 케이스:

1. **정상 케이스** — N건 orders 조회 시 `findAllByOrderInWithProduct` 1회 호출, 응답 N건 검증
2. **빈 페이지** — 결과 0건일 때 IN 쿼리 미호출 검증
3. **OrderItem 누락** — itemByOrderId 매핑 실패 시 `ORDER_ITEM_NOT_FOUND` 예외 검증

---

## ADR 준수 확인

- ADR-006 (단방향 @ManyToOne만 사용): ✅ Order에 @OneToMany 추가 없음
- ADR-002 (3-Layered + Rich Domain Model): ✅ 서비스 레이어에서 컬렉션 조회 후 도메인 메서드 인자 전달 패턴 유지
