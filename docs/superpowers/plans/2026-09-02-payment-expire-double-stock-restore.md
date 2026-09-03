# #204 결제 만료 경로 재고 이중 복원 제거

## 배경

`PaymentExpireService.cancelExpiredPayment()`는 만료 처리 시점(주문 `RESERVED`·결제 `PENDING`)에서
MySQL `remainingStock`을 `restoreStock()`으로 되돌린다. 그러나 이 시점에는 MySQL 재고가 차감된 적이
없으므로(차감은 `completePayment → Product.confirmStock()`에서만 발생), 줄지 않은 재고를 되돌리는 버그다.

- `restoreStock()`이 `remainingStock + quantity > totalStock` 시 예외 → `REQUIRES_NEW` 롤백 → 만료 영구 실패
- 예외가 안 나는 경우 MySQL 원본 재고가 조용히 부풀어 재고 복구 3층 전체가 오염 → 초과 판매

#144 Redis 예약 계층 전환 시 `OrderExpireService`는 MySQL 복원을 제거(`a7e75f6`)했으나
`PaymentExpireService`는 Redis 복원만 추가(`92703f2`)하고 MySQL 복원을 남겨둔 불일치.

## 변경 범위

### `PaymentExpireService.cancelExpiredPayment()`
- `orderItemRepository.findAllByOrder` 결과를 `restoreStock()` 하는 `items.stream().sorted().forEach(...)` 블록 제거
- `payment.expire()` / `order.cancel(...)` / `stockRedisService.releaseStock(...)` 만 남겨 `OrderExpireService`와 동일 형태로 정렬
- 불필요해진 필드/임포트 정리: `ProductRepository`, `Product`, `BusinessException`, `ProductErrorCode`, `Comparator`

### 범위 밖 (건드리지 않음)
- `Product.restoreStock()` 자체 (ProductTest 등에서 계속 사용) — #195에서 별도 처리
- `OrderExpireService`

## 테스트 (`PaymentExpireServiceTest`)
- 기존 `cancelExpiredPayment_만료된_PENDING_Payment_취소_및_Order_취소_재고_복구`
  테스트는 `remainingStock` 10 → 12 를 검증하고 있어 새 동작과 모순 → **수정 필요**
  (`remainingStock` 불변 검증 + `productRepository` mock 제거)
- 신규: `확정_판매_없는_상품_결제_만료_정상_완료` — `remainingStock == totalStock` 상태에서 예외 없이 만료 완료,
  MySQL 재고 불변, Redis `releaseStock` 호출 검증
- `@Mock ProductRepository` 제거 (더 이상 주입 대상 아님)

## 완료 기준
- [ ] `./gradlew test` 통과
- [ ] 만료 후 MySQL `remainingStock` 불변, Redis만 복원
- [ ] `OrderExpireService`와 복원 방식 일치
