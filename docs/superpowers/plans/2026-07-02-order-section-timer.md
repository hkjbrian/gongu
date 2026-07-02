# createOrder 구간별 소요시간 측정 (AOP Custom Timer) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** `OrderService#createOrder` 핫패스의 구간별(유저 조회, 상품 조회, 매장 멤버십 확인, Redis 재고 예약, Order/OrderItem 저장, 전체) 소요시간을 AOP로 측정해 `gongu.order.section{section=...}` Micrometer Timer로 발행하고, Grafana에서 병목 구간을 데이터로 확인한다.

**Spec:** GitHub Issue #168 (https://github.com/hkjbrian/gongu/issues/168)

**Tech Stack:** Spring Boot 3.5 AOP (`spring-boot-starter-aop`, 이미 `build.gradle:47`에 존재), Micrometer `Timer`, Prometheus, Grafana

---

## 배경 / 현재 상태

`feat/#168-order-section-timer` 브랜치는 `main`에서 분기했으며 캐싱(#163) 변경사항은 포함하지 않는다 — 캐싱 없는 baseline에서 병목을 먼저 측정하기 위함. `feat/#163-product-store-cache` 브랜치와는 독립적으로 유지한다.

측정 대상 (`src/main/java/com/gongu/server/domain/order/service/OrderService.java:59-96`):

```
1. userRepository.findByIdAndDeletedAtIsNull(userId)     — user_lookup
2. productRepository.findById(productId)                  — product_lookup
3. product.getStore() (LAZY)                               — 별도 측정 불가 (아래 "알려진 한계" 참고)
4. userStoreRepository.existsByUserAndStore(user, store)   — store_membership_check
5. stockRedisService.reserveStock(productId, quantity)      — stock_reserve
6. orderRepository.save(order)                             — order_save
7. orderItemRepository.save(item)                          — order_item_save
전체 메서드                                                  — order_total
```

**알려진 한계**: `product.getStore()`는 Hibernate 프록시의 LAZY 초기화이며 Spring 빈 메서드 호출이 아니므로 Spring AOP로 직접 측정할 수 없다. 이 구간의 소요시간은 `order_total`에서 나머지 6개 구간 합을 뺀 잔차(residual)로 추정한다. 이 계획에서는 별도 아키텍처 변경(fetch join 등) 없이 측정만 한다.

## 파일 맵

**Create:**
- `src/main/java/com/gongu/server/global/aop/Traced.java` — `order_total` 측정용 커스텀 애노테이션
- `src/main/java/com/gongu/server/global/aop/TracedAspect.java` — `@annotation(Traced)` 어드바이스 1개 + 리포지토리/서비스 메서드 대상 `execution()` 어드바이스 5개
- `src/test/java/com/gongu/server/global/aop/TracedAspectTest.java` — Spring AOP 프록시가 실제로 타이머를 기록하는지 검증하는 통합 테스트

**Modify:**
- `src/main/java/com/gongu/server/domain/order/service/OrderService.java` — `createOrder` 메서드 시그니처에 `@Traced("order_total")` 애노테이션 1줄 추가 (로직 변경 없음)
- `monitoring/grafana/dashboards/gongu-dashboard.json` — `gongu.order.section` 태그별 p50/p95/p99 패널 추가

**금지 (건드리지 않음):**
- `OrderService.createOrder` 내부 로직 — 애노테이션 1줄 추가 외 어떤 코드도 수정하지 않는다
- `UserRepository`, `ProductRepository`, `UserStoreRepository`, `OrderRepository`, `OrderItemRepository`, `StockRedisService` — 인터페이스/클래스에 애노테이션을 추가하지 않는다. 측정은 전부 `TracedAspect`의 `execution()` 포인트컷으로 처리한다 (비즈니스/리포지토리 코드 무변경 원칙)
- `feat/#163-product-store-cache` 브랜치 — 별도 브랜치, 이 작업과 병합/리베이스하지 않는다
- `application-perf.yml`의 HikariCP 설정 — 이번 이슈 범위 아님

---

### Task 1: `@Traced` 애노테이션 + `TracedAspect` 구현

**참고 문서/파일 (읽어야 할 것):**
- `src/main/java/com/gongu/server/global/config/MetricsConfig.java` — 기존 Micrometer `Timer`/`Counter` 네이밍 컨벤션 (`gongu.db.lock.query_duration`, `.tag("entity", ...)` 패턴) 참고
- `src/main/java/com/gongu/server/domain/order/service/OrderService.java:59-96` — `createOrder` 전체 흐름, 측정 대상 6개 호출 지점 확인
- `src/main/java/com/gongu/server/domain/user/repository/UserRepository.java` — `findByIdAndDeletedAtIsNull` 시그니처 확인 (execution 포인트컷 작성용)
- `src/main/java/com/gongu/server/domain/product/repository/ProductRepository.java:24` — `findById`는 `JpaRepository` 상속 메서드이므로 `Optional<Product> findById(Long id)` 시그니처를 `org.springframework.data.jpa.repository.JpaRepository` 기준으로 포인트컷 작성 시 주의 (여러 리포지토리가 같은 `findById`를 상속하므로, 타입을 `ProductRepository`로 명시해 범위를 좁힐 것)
- `src/main/java/com/gongu/server/domain/store/repository/UserStoreRepository.java` — `existsByUserAndStore` 시그니처 확인
- `src/main/java/com/gongu/server/domain/product/service/StockRedisService.java:22` — `reserveStock` 시그니처 확인
- `src/main/java/com/gongu/server/domain/order/repository/OrderRepository.java`, `src/main/java/com/gongu/server/domain/order/repository/OrderItemRepository.java` — `save`는 `JpaRepository` 상속 메서드. 포인트컷에서 타입을 명시적으로 좁혀 다른 리포지토리의 `save` 호출과 섞이지 않도록 할 것

**수정 대상 파일:**
- Create: `src/main/java/com/gongu/server/global/aop/Traced.java`
- Create: `src/main/java/com/gongu/server/global/aop/TracedAspect.java`

**금지 사항 (건드리면 안 되는 것):**
- 리포지토리 인터페이스, `StockRedisService` — 어떤 애노테이션도 추가하지 않는다
- `MetricsConfig.java` — 이 태스크에서는 수정하지 않는다 (Timer는 `TracedAspect`가 `MeterRegistry`로 직접 생성)

**구현 방향 (WHAT, not HOW):**
- `Traced` 애노테이션: `@Target(ElementType.METHOD)`, `@Retention(RetentionPolicy.RUNTIME)`, `String value()` — section 태그 값
- `TracedAspect`: `@Aspect @Component`, 생성자로 `MeterRegistry` 주입 (Lombok `@RequiredArgsConstructor` 사용, 기존 `MetricsConfig` 스타일과 일관되게)
- 공통 측정 헬퍼 메서드 하나로 모든 어드바이스가 재사용: `Timer.Sample` 시작 → `pjp.proceed()` → `finally`에서 `Timer.builder("gongu.order.section").tag("section", 값).publishPercentileHistogram().register(meterRegistry)`로 stop
- 어드바이스 1 (`@Around("@annotation(traced)")`): `order_total` — `OrderService.createOrder`에 붙는 `@Traced` 애노테이션을 감지
- 어드바이스 2~6 (각각 `@Around("execution(...)")`, section 값은 어드바이스 코드에 하드코딩):
  - `execution(* com.gongu.server.domain.user.repository.UserRepository.findByIdAndDeletedAtIsNull(..))` → `user_lookup`
  - `execution(* com.gongu.server.domain.product.repository.ProductRepository.findById(..))` → `product_lookup`
  - `execution(* com.gongu.server.domain.store.repository.UserStoreRepository.existsByUserAndStore(..))` → `store_membership_check`
  - `execution(* com.gongu.server.domain.product.service.StockRedisService.reserveStock(..))` → `stock_reserve`
  - `execution(* com.gongu.server.domain.order.repository.OrderRepository.save(..))` → `order_save`
  - `execution(* com.gongu.server.domain.order.repository.OrderItemRepository.save(..))` → `order_item_save`
- 6개의 `execution()` 어드바이스를 각각 별도 `@Around` 메서드로 작성해도 되고, 하나의 `@Around("execution(pointcut1) || execution(pointcut2) || ...")`로 묶고 `JoinPoint`의 시그니처로 section을 분기해도 된다 — 가독성 우선으로 판단해 구현자가 선택
- `product_lookup` 관련: `ProductRepository`는 `JpaRepository<Product, Long>`를 상속하므로 `findById`가 인터페이스에 직접 선언되어 있지 않다. `execution()` 포인트컷에 선언 타입을 `ProductRepository`로 명시하면 프록시 호출 시 대상 인터페이스 기준으로 매칭되는지 반드시 Task 2 검증 단계에서 실측 확인할 것 (매칭 안 되면 `execution(* org.springframework.data.repository.CrudRepository+.findById(..)) && target(com.gongu.server.domain.product.repository.ProductRepository)` 형태의 `target()` 조합 포인트컷으로 대체)

**검증:**
```bash
./gradlew compileJava
```
Expected: 컴파일 성공, 새 파일 2개 생성 확인

**커밋 (프로젝트 컨벤션 따름):**
```bash
git add src/main/java/com/gongu/server/global/aop/Traced.java src/main/java/com/gongu/server/global/aop/TracedAspect.java
git commit -m "feat: createOrder 구간별 측정용 @Traced 애노테이션 및 AOP Aspect 추가 (#168)"
```

---

### Task 2: `OrderService.createOrder`에 `@Traced("order_total")` 적용 + 동작 검증 테스트

**참고 문서/파일 (읽어야 할 것):**
- Task 1에서 만든 `src/main/java/com/gongu/server/global/aop/Traced.java`, `TracedAspect.java`
- `src/main/java/com/gongu/server/domain/order/service/OrderService.java:59-60` — `createOrder` 메서드 시그니처 위치
- `src/test/java/com/gongu/server/ServerApplicationTests.java`, `src/test/java/com/gongu/server/domain/product/service/ProductStockConcurrencyTest.java` — 기존 `@SpringBootTest` 통합 테스트 작성 패턴 참고 (프로필, 테스트 컨테이너/H2 설정 방식)
- `src/test/java/com/gongu/server/domain/order/service/OrderServiceTest.java` — 기존 `OrderService` 단위 테스트 스타일 (단, 이 테스트는 Mockito 기반이라 AOP 프록시가 적용되지 않으므로 이번 태스크의 신규 테스트와는 별개로 유지)

**수정 대상 파일:**
- Modify: `src/main/java/com/gongu/server/domain/order/service/OrderService.java` (import 1줄 + 애노테이션 1줄만 추가)
- Create: `src/test/java/com/gongu/server/global/aop/TracedAspectTest.java`

**금지 사항 (건드리면 안 되는 것):**
- `createOrder` 메서드 본문 — 애노테이션 추가 외 어떤 라인도 수정하지 않는다
- `OrderServiceTest.java` (기존 단위 테스트) — 이번 작업으로 인해 실패해서는 안 되며, 수정이 필요하다면 이유를 명확히 밝힐 것 (원칙적으로 수정 불필요 — Mockito 기반 단위 테스트는 AOP 프록시를 거치지 않으므로 `@Traced` 추가와 무관)

**구현 방향 (WHAT, not HOW):**
- `OrderService.java`에 `import com.gongu.server.global.aop.Traced;` 추가
- `createOrder` 메서드 선언부 바로 위(또는 `@Transactional` 애노테이션과 나란히)에 `@Traced("order_total")` 추가
- `TracedAspectTest`는 `@SpringBootTest`로 실제 Spring 컨텍스트를 띄우고, `MeterRegistry`(`SimpleMeterRegistry` 또는 테스트 프로필의 실제 레지스트리)를 주입받아 `createOrder` 호출 후 `meterRegistry.get("gongu.order.section").tag("section", "order_total").timer()`가 존재하고 `count() >= 1`인지 검증
- 리포지토리 레벨 포인트컷(`user_lookup` 등) 중 최소 1개 이상도 같은 테스트에서 함께 검증 (예: `store_membership_check` 타이머 존재 확인) — Task 1의 `execution()` 포인트컷이 실제로 매칭되는지 확인하는 목적

**검증:**
```bash
./gradlew test --tests "com.gongu.server.global.aop.TracedAspectTest"
```
Expected: 테스트 통과, 로그에서 `gongu.order.section` 메트릭이 `order_total`, `user_lookup`(또는 검증 대상 구간) 태그로 기록되었음을 확인

```bash
./gradlew test --tests "com.gongu.server.domain.order.service.OrderServiceTest"
```
Expected: 기존 테스트 전부 통과 (회귀 없음)

**커밋 (프로젝트 컨벤션 따름):**
```bash
git add src/main/java/com/gongu/server/domain/order/service/OrderService.java src/test/java/com/gongu/server/global/aop/TracedAspectTest.java
git commit -m "feat: createOrder에 @Traced(order_total) 적용 및 AOP 타이머 검증 테스트 추가 (#168)"
```

---

### Task 3: Grafana 대시보드에 구간별 p50/p95/p99 패널 추가

**참고 문서/파일 (읽어야 할 것):**
- `monitoring/grafana/dashboards/gongu-dashboard.json` — 기존 패널 구조. 특히 `feat/#163-product-store-cache` 브랜치에서 이미 추가된 "Redis 캐시 성능" row(`id: 18`, `gridPos.y: 32` 근방)의 JSON 구조를 스타일 참고용으로만 사용 — 이 브랜치에는 해당 패널이 없으므로 새로 작성해야 함
- 기존 대시보드 내 `gongu.db.lock.query_duration` 관련 패널이 있다면 해당 PromQL 쿼리 형태(`histogram_quantile` 사용법) 참고

**수정 대상 파일:**
- Modify: `monitoring/grafana/dashboards/gongu-dashboard.json`

**금지 사항 (건드리면 안 되는 것):**
- 기존 패널의 `id` 값과 충돌하지 않도록 새 `id`는 현재 파일 내 최대 `id` + 1부터 순차 부여
- "Redis 캐시 성능" row는 이 브랜치에 존재하지 않으므로 재생성하지 않는다 (범위 밖, #163 소관)

**구현 방향 (WHAT, not HOW):**
- 새 row: "Order 생성 구간별 소요시간"
- 패널: `gongu.order.section` 메트릭을 `section` 태그로 그룹핑한 p50/p95/p99 타임시리즈 그래프 1개 (Prometheus `histogram_quantile(0.5/0.95/0.99, sum(rate(gongu_order_section_seconds_bucket[1m])) by (le, section))` 형태 — 실제 메트릭명은 Micrometer가 `gongu.order.section` → Prometheus 노출 시 `gongu_order_section_seconds_bucket`으로 변환되는지 Task 2 완료 후 실제 `/actuator/prometheus` 응답으로 확인 후 정확한 이름 사용)
- 6개 section(user_lookup, product_lookup, store_membership_check, stock_reserve, order_save, order_item_save) + order_total을 한 그래프에 legend로 구분, 혹은 section별 stat 패널을 별도로 나열 — 가독성 우선으로 구현자가 선택

**검증:**
```bash
python3 -c "import json; json.load(open('monitoring/grafana/dashboards/gongu-dashboard.json'))"
```
Expected: JSON 파싱 에러 없음 (유효한 JSON)

수동 검증 (선택, 로컬 docker-compose 환경 있을 시):
```bash
docker compose up -d grafana prometheus
# Grafana에서 대시보드 임포트 후 패널 렌더링 확인
```

**커밋 (프로젝트 컨벤션 따름):**
```bash
git add monitoring/grafana/dashboards/gongu-dashboard.json
git commit -m "feat: Order 생성 구간별 소요시간 Grafana 패널 추가 (#168)"
```

---

## 이후 절차

Task 1~3 완료 후 CLAUDE.md/workflow.md 6~11단계(빌드 검증 → push → PR 생성 → `/codex:review` 위임 → 판정 → 반영) 따름. PR 본문에 `close #168` 포함, Milestone 연결 필수.

부하테스트(07번 시나리오) 실행 후 구간별 병목 데이터가 확보되면, 그 결과를 바탕으로 다음 최적화(Order/OrderItem 비동기 저장 등) 여부를 별도 이슈로 논의한다 — 이 계획의 범위 밖.
