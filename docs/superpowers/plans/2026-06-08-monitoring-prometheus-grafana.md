# 모니터링 구축 (Prometheus + Grafana) 구현 계획

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Spring Actuator + Micrometer + Prometheus + Grafana로 애플리케이션 메트릭 수집·시각화 인프라를 구축하고, 비즈니스 커스텀 메트릭(주문/결제/락 대기 시간/스케줄러)을 계측한다.

**Spec:** GitHub Issue #143 — [DISCUSSION] 모니터링 구축 전략 — Prometheus / Grafana / Micrometer

**Tech Stack:** Spring Boot 3.5, Micrometer, `micrometer-registry-prometheus`, Prometheus 2.x, Grafana 10.x, Docker Compose

---

## 파일 맵

### Task 1 — 의존성 + Actuator 설정
| 파일 | 변경 |
|------|------|
| `build.gradle` | Modify — Micrometer Prometheus 의존성 추가 |
| `src/main/resources/application.yml` | Modify — Actuator 엔드포인트 노출 설정 추가 |
| `src/main/resources/application-local.yml` | Modify — 로컬 Actuator 전체 노출 오버라이드 |

### Task 2 — 인프라 (Docker Compose + 설정 파일)
| 파일 | 변경 |
|------|------|
| `docker-compose.yml` | Modify — Prometheus, Grafana 서비스 추가 |
| `monitoring/prometheus/prometheus.yml` | Create — Prometheus 스크레이프 설정 |
| `monitoring/grafana/provisioning/datasources/prometheus.yml` | Create — Grafana 데이터소스 자동 프로비저닝 |
| `monitoring/grafana/provisioning/dashboards/dashboard.yml` | Create — Grafana 대시보드 디렉토리 프로비저닝 설정 |

### Task 3 — 비즈니스 커스텀 메트릭
| 파일 | 변경 |
|------|------|
| `src/main/java/com/gongu/server/global/config/MetricsConfig.java` | Create — 커스텀 메트릭 Bean 정의 |
| `src/main/java/com/gongu/server/domain/order/scheduler/OrderExpiryScheduler.java` | Modify — 스케줄러 처리 건수/시간 계측 |
| `src/main/java/com/gongu/server/domain/payment/service/PaymentService.java` | Modify — 결제 완료/실패 Counter 추가 |
| `src/main/java/com/gongu/server/domain/order/service/OrderService.java` | Modify — 주문 생성 Counter 추가 |

### Task 4 — 비관적 락 대기 시간 계측
| 파일 | 변경 |
|------|------|
| `src/main/java/com/gongu/server/domain/order/service/OrderService.java` | Modify — `findByIdWithLock` 호출을 Timer로 감쌈 |
| `src/main/java/com/gongu/server/domain/order/service/OrderExpireService.java` | Modify — `findByIdWithLock` 호출을 Timer로 감쌈 |
| `src/main/java/com/gongu/server/domain/product/service/ProductService.java` | Modify — `findByIdWithLock` 호출을 Timer로 감쌈 (존재할 경우) |

### 참고 전용 (수정 금지)
- `src/main/java/com/gongu/server/domain/payment/scheduler/PaymentExpiryScheduler.java` — Task 3 이후 별도 이슈에서 처리 가능
- `src/main/java/com/gongu/server/domain/product/scheduler/ProductStatusScheduler.java` — 동상

---

## Task 1: Micrometer Prometheus 의존성 추가 및 Actuator 설정

**참고 문서/파일 (읽어야 할 것):**
- `build.gradle` — 현재 의존성 구조 파악 (actuator 이미 포함)
- `src/main/resources/application.yml` — 기존 설정 구조 파악
- `src/main/resources/application-local.yml` — 로컬 오버라이드 구조 파악

**수정 대상 파일:**
- Modify: `build.gradle`
- Modify: `src/main/resources/application.yml`
- Modify: `src/main/resources/application-local.yml`

**금지 사항:**
- `SecurityConfig.java` 수정 금지 — Actuator 보안 설정은 이 태스크 범위 밖 (Prometheus는 내부 네트워크에서만 스크레이핑하므로 SecurityConfig 변경 불필요)
- 기존 의존성 제거 금지

**구현 방향 (WHAT, not HOW):**

`build.gradle`에 다음 의존성을 추가한다:
```
implementation 'io.micrometer:micrometer-registry-prometheus'
```

`application.yml`의 최하단에 다음 블록을 추가한다:
```yaml
management:
  endpoints:
    web:
      exposure:
        include: health,info,prometheus,metrics
  endpoint:
    health:
      show-details: when-authorized
    prometheus:
      enabled: true
  metrics:
    tags:
      application: gongu-server
```

`application-local.yml`의 최하단에 로컬 오버라이드를 추가한다 (개발 편의상 전체 노출):
```yaml
management:
  endpoints:
    web:
      exposure:
        include: "*"
  endpoint:
    health:
      show-details: always
```

**검증:**
```bash
./gradlew build -x test && curl -s http://localhost:8080/actuator/prometheus | head -20
```
Expected: `# HELP jvm_memory_used_bytes ...` 형태의 Prometheus 텍스트 포맷 응답

**커밋:**
```bash
git add build.gradle src/main/resources/application.yml src/main/resources/application-local.yml
git commit -m "chore: Micrometer Prometheus 레지스트리 추가 및 Actuator 엔드포인트 설정 (#143)"
```

---

## Task 2: Docker Compose에 Prometheus + Grafana 추가

**참고 문서/파일 (읽어야 할 것):**
- `docker-compose.yml` — 기존 서비스 구조, 네트워크명(`gongu-net`), 볼륨 패턴 파악

**수정 대상 파일:**
- Modify: `docker-compose.yml`
- Create: `monitoring/prometheus/prometheus.yml`
- Create: `monitoring/grafana/provisioning/datasources/prometheus.yml`
- Create: `monitoring/grafana/provisioning/dashboards/dashboard.yml`

**금지 사항:**
- 기존 `mysql`, `redis` 서비스 설정 변경 금지
- `mysql-data` 볼륨 설정 변경 금지

**구현 방향 (WHAT, not HOW):**

`docker-compose.yml`에 다음 두 서비스와 볼륨을 추가한다:

```yaml
  prometheus:
    image: prom/prometheus:latest
    container_name: gongu-prometheus
    restart: unless-stopped
    ports:
      - "9090:9090"
    volumes:
      - ./monitoring/prometheus/prometheus.yml:/etc/prometheus/prometheus.yml:ro
      - prometheus-data:/prometheus
    command:
      - '--config.file=/etc/prometheus/prometheus.yml'
      - '--storage.tsdb.retention.time=30d'
    networks:
      - gongu-net

  grafana:
    image: grafana/grafana:latest
    container_name: gongu-grafana
    restart: unless-stopped
    ports:
      - "3001:3000"
    environment:
      - GF_SECURITY_ADMIN_USER=${GRAFANA_USER:-admin}
      - GF_SECURITY_ADMIN_PASSWORD=${GRAFANA_PASSWORD:-admin}
    volumes:
      - ./monitoring/grafana/provisioning:/etc/grafana/provisioning:ro
      - grafana-data:/var/lib/grafana
    depends_on:
      - prometheus
    networks:
      - gongu-net
```

volumes 섹션에 `prometheus-data:`, `grafana-data:` 추가.

`monitoring/prometheus/prometheus.yml` 내용:
```yaml
global:
  scrape_interval: 15s
  evaluation_interval: 15s

scrape_configs:
  - job_name: 'gongu-server'
    metrics_path: '/actuator/prometheus'
    static_configs:
      - targets: ['host.docker.internal:8080']
    relabel_configs:
      - target_label: application
        replacement: gongu-server
```

> 주의: `host.docker.internal`은 Mac/Windows에서 호스트 접근 시 사용. Linux에서는 `172.17.0.1` 또는 `--add-host=host.docker.internal:host-gateway` 옵션 필요.

`monitoring/grafana/provisioning/datasources/prometheus.yml` 내용:
```yaml
apiVersion: 1
datasources:
  - name: Prometheus
    type: prometheus
    url: http://prometheus:9090
    isDefault: true
    editable: true
```

`monitoring/grafana/provisioning/dashboards/dashboard.yml` 내용:
```yaml
apiVersion: 1
providers:
  - name: 'default'
    orgId: 1
    folder: 'Gongu'
    type: file
    disableDeletion: false
    updateIntervalSeconds: 10
    options:
      path: /var/lib/grafana/dashboards
```

**검증:**
```bash
docker compose up -d prometheus grafana
curl -s http://localhost:9090/-/ready
curl -s http://localhost:3001/api/health
```
Expected: Prometheus `200 OK "Prometheus Server is Ready."`, Grafana `{"database":"ok",...}`

Grafana UI(http://localhost:3001) 접속 → admin/admin → Connections → Data Sources → Prometheus 확인.

**커밋:**
```bash
git add docker-compose.yml monitoring/
git commit -m "chore: Prometheus + Grafana Docker Compose 설정 추가 (#143)"
```

---

## Task 3: 비즈니스 커스텀 메트릭 계측

**참고 문서/파일 (읽어야 할 것):**
- `src/main/java/com/gongu/server/domain/order/scheduler/OrderExpiryScheduler.java` — `expireReservedOrders()` 메서드 구조 파악 (count 변수, for 루프 완료 후 log.info)
- `src/main/java/com/gongu/server/domain/payment/service/PaymentService.java` — `completePayment()` 메서드에서 성공/실패 분기 파악
- `src/main/java/com/gongu/server/domain/order/service/OrderService.java` — 주문 생성 메서드(`createOrder` 또는 동등한 메서드) 파악
- `src/main/java/com/gongu/server/global/config/AppConfig.java` — Config 클래스 작성 패턴 참고

**수정 대상 파일:**
- Create: `src/main/java/com/gongu/server/global/config/MetricsConfig.java`
- Modify: `src/main/java/com/gongu/server/domain/order/scheduler/OrderExpiryScheduler.java`
- Modify: `src/main/java/com/gongu/server/domain/payment/service/PaymentService.java`
- Modify: `src/main/java/com/gongu/server/domain/order/service/OrderService.java`

**금지 사항:**
- 기존 비즈니스 로직(트랜잭션, 예외 처리, 상태 전이) 변경 금지 — 메트릭 코드를 로직 흐름에 끼워넣지 말고 로직 완료 후 계측
- `PaymentExpireService`, `PaymentExpiryScheduler`, `ProductStatusScheduler` 수정 금지 — 이번 태스크 범위 밖

**구현 방향 (WHAT, not HOW):**

**MetricsConfig.java 생성** (`com.gongu.server.global.config` 패키지):
- `@Configuration` 클래스
- `MeterRegistry`를 주입받아 아래 Counter Bean을 명시적으로 등록한다:
  - `Counter` — name: `gongu.order.created`, description: "생성된 주문 수"
  - `Counter` — name: `gongu.payment.completed`, description: "완료된 결제 수"
  - `Counter` — name: `gongu.payment.failed`, tag `reason`을 포함, description: "실패한 결제 수"
  - `Counter` — name: `gongu.order.expired`, description: "만료 처리된 주문 수"
  - `Timer` — name: `gongu.order.expire.duration`, description: "만료 스케줄러 1회 실행 시간"

**OrderExpiryScheduler.java 수정:**
- 생성자에 위의 Counter/Timer Bean 주입 (이름: `orderExpiredCounter`, `orderExpireDuration`)
- `expireReservedOrders()` 메서드 전체를 `orderExpireDuration.record(() -> { ... })` 로 감싼다
- 기존 `count++` 직후에 `orderExpiredCounter.increment()` 추가
- 기존 `log.info("만료 주문 처리 완료: {}건", count)` 유지

**PaymentService.java 수정:**
- 생성자에 `paymentCompletedCounter`, `paymentFailedCounter` 주입
- `completePayment()` 메서드에서 결제 상태가 `PAID`로 전이되는 성공 분기 직후: `paymentCompletedCounter.increment()`
- `BusinessException` 또는 `InfraException`이 throw되는 실패 분기: `paymentFailedCounter` increment (reason 태그 포함)

**OrderService.java 수정:**
- 생성자에 `orderCreatedCounter` 주입
- 주문이 성공적으로 저장된 직후(`orderRepository.save(...)` 이후): `orderCreatedCounter.increment()`

**검증:**
```bash
./gradlew test
curl -s http://localhost:8080/actuator/prometheus | grep "gongu_"
```
Expected: `gongu_order_created_total`, `gongu_payment_completed_total`, `gongu_order_expired_total`, `gongu_order_expire_duration_seconds_*` 메트릭 존재

**커밋:**
```bash
git add src/main/java/com/gongu/server/global/config/MetricsConfig.java \
        src/main/java/com/gongu/server/domain/order/scheduler/OrderExpiryScheduler.java \
        src/main/java/com/gongu/server/domain/payment/service/PaymentService.java \
        src/main/java/com/gongu/server/domain/order/service/OrderService.java
git commit -m "feat: 비즈니스 커스텀 메트릭 계측 추가 (주문/결제/만료 스케줄러) (#143)"
```

---

## Task 4: 비관적 락 대기 시간 Timer 계측

**참고 문서/파일 (읽어야 할 것):**
- `src/main/java/com/gongu/server/domain/order/service/OrderService.java` — `findByIdWithLock` 호출 위치 파악 (67, 87, 99, 131번 줄 근처)
- `src/main/java/com/gongu/server/domain/order/service/OrderExpireService.java` — `findByIdWithLock` 호출 위치 파악 (35, 58번 줄)
- `src/main/java/com/gongu/server/global/config/MetricsConfig.java` (Task 3에서 생성) — 기존 Timer Bean 등록 패턴 참고

**수정 대상 파일:**
- Modify: `src/main/java/com/gongu/server/global/config/MetricsConfig.java` — 락 Timer Bean 추가
- Modify: `src/main/java/com/gongu/server/domain/order/service/OrderService.java`
- Modify: `src/main/java/com/gongu/server/domain/order/service/OrderExpireService.java`

**금지 사항:**
- Repository 인터페이스(`findByIdWithLock` 메서드) 변경 금지
- 트랜잭션 경계(`@Transactional`) 변경 금지
- `ProductService.java` 수정 금지 — 파일 확인 후 `findByIdWithLock` 호출이 없으면 스킵

**구현 방향 (WHAT, not HOW):**

**MetricsConfig.java에 Timer 추가:**
- `Timer` — name: `gongu.product.lock.wait`, tag `entity`(값: `"order"` 또는 `"product"`), description: "비관적 락 획득까지 소요 시간"

**OrderService.java 수정:**
- `MeterRegistry`(또는 Timer Bean) 생성자 주입 추가
- `orderRepository.findByIdWithLock(orderId)` 호출을 `Timer.record(() -> ...)` 로 감싼다. tag: `entity=order`
- `productRepository.findByIdWithLock(productId)` 호출도 동일하게. tag: `entity=product`
- `Optional` 반환값은 record 람다 반환값으로 처리

**OrderExpireService.java 수정:**
- `MeterRegistry`(또는 Timer Bean) 생성자 주입 추가
- 35번 줄의 `orderRepository.findByIdWithLock(orderId)` → Timer로 감쌈. tag: `entity=order`
- 58번 줄의 `productRepository.findByIdWithLock(...)` → Timer로 감쌈. tag: `entity=product`

> 주의: Timer 측정값은 "락을 획득해 DB row를 반환하는 전체 쿼리 시간"이다. MySQL은 `innodb_lock_wait_timeout`이 발생하면 예외를 던지므로, Timer는 정상 획득 경로만 측정한다.

**검증:**
```bash
./gradlew test
curl -s http://localhost:8080/actuator/prometheus | grep "lock_wait"
```
Expected: `gongu_product_lock_wait_seconds_count`, `gongu_product_lock_wait_seconds_sum` 메트릭 존재

**커밋:**
```bash
git add src/main/java/com/gongu/server/global/config/MetricsConfig.java \
        src/main/java/com/gongu/server/domain/order/service/OrderService.java \
        src/main/java/com/gongu/server/domain/order/service/OrderExpireService.java
git commit -m "feat: 비관적 락 대기 시간 Timer 계측 추가 (#143)"
```

---

## 완료 후 처리

모든 태스크 완료 후:
1. 전체 빌드 확인: `./gradlew test`
2. `git push` 및 PR 생성
3. PR 생성 후 CLAUDE.md 9~11단계(Codex 리뷰 위임 → 판정 → 반영) 따름
4. 이슈 #143의 "결론" 섹션에 결정 사항 기록: A안 선택, 향후 OpenTelemetry + Grafana Tempo 추가 예정

---

## ADR 필요 여부

이번 결정(Prometheus + Grafana 채택, B안/ELK 미채택)은 ADR 작성 대상임.  
구현 완료 후 `docs/adr/모니터링_전략_Prometheus_Grafana.md` 작성 예정.
