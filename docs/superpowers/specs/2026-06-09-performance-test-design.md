# 성능 테스트 설계

**작성일**: 2026-06-09  
**대상**: 주문/결제 도메인 성능 및 동시성 검증

---

## 1. 목표

- 비관적 락 기반 재고 관리의 동시성 안전성 검증
- 외부 PG 호출이 DB 커넥션 점유에 미치는 영향 측정
- 스케줄러(만료 처리) + Webhook 동시 도달 시 멱등성 검증
- Circuit Breaker 동작 확인
- 부하 증가 시 시스템 한계점(커넥션 풀 고갈) 탐색

---

## 2. 환경

### docker-compose 구성

| 서비스 | 포트 | 비고 |
|---|---|---|
| spring-app | 8080 | `--spring.profiles.active=perf` |
| mysql | 3306 | - |
| redis | 6379 | - |
| prometheus | 9090 | - |
| grafana | 3001 | - |
| **mock-pg** | **8090** | 신규 추가 |

k6는 로컬에서 실행하여 spring-app과 mock-pg를 직접 호출한다.

### Spring 프로파일 분리

`application-perf.yml` 신규 파일:

```yaml
portone:
  base-url: http://gongu-mock-pg:8090

order:
  reservation-ttl-minutes: 2  # 스케줄러 경쟁 시나리오 대기 시간 단축
```

기존 `PortOneClient`의 base-url만 교체되므로 코드 변경 없음.

---

## 3. Mock PG 서버

### 기술 스택

Node.js + Express. 인메모리 Map으로 payment 상태 관리. Docker 컨테이너로 docker-compose에 추가.

**위치**: `server/load-test/mock-pg/`

### API

#### 제어 API (k6 → Mock PG)

| Method | Path | 설명 |
|---|---|---|
| `POST` | `/control/payments/{id}/complete` | paymentId를 PAID로 등록. body: `{ amount, delayMs, webhookDelayMs }` |
| `POST` | `/control/payments/{id}/fail` | FAILED 상태로 등록 (사용자 결제 취소 등) |
| `POST` | `/control/server-error` | 이후 모든 PortOne API 조회에 5xx 반환 (Circuit Breaker 시나리오용) |
| `POST` | `/control/reset` | 전체 상태 초기화 |

#### PortOne API 에뮬레이션 (spring-app → Mock PG)

| Method | Path | 설명 |
|---|---|---|
| `GET` | `/payments/{id}` | 등록된 결제 정보 반환. `delayMs` 만큼 응답 지연 |
| `POST` | `/payments/{id}/cancel` | 결제 취소 처리 |

#### Webhook 발송 (Mock PG → spring-app, 비동기)

`/control/payments/{id}/complete` 호출 시 `webhookDelayMs` 후 spring-app의 `/payments/webhook`으로 자동 POST.

### 상태 머신

```
UNREGISTERED
    ↓ /control/complete
   PAID ──→ CANCELLED (/payments/{id}/cancel)

UNREGISTERED
    ↓ /control/fail
  FAILED
```

`UNREGISTERED` 상태에서 GET 조회 시 404 반환.

---

## 4. 인증 (테스트 사용자)

소셜 로그인(Kakao OAuth) 우회를 위해 `perf` 프로파일 전용 엔드포인트를 추가한다.

- **컨트롤러**: `TestAuthController` (`@Profile("perf")`)
- **엔드포인트**: `POST /auth/test-login` — body `{ "userId": N }` → accessToken, refreshToken 반환
- **내부 동작**: `jwtProvider.generateAccessToken(userId, Role.USER)` 직접 호출. Kakao API 호출 없음
- **prod/dev 프로파일에서는 이 컨트롤러가 빈으로 등록되지 않음**

테스트 사용자는 `load-test/seed.sql`로 DB에 사전 삽입. k6 `setup()` 단계에서 각 userId에 대해 `/auth/test-login`을 호출하여 토큰 풀을 구성하고, VU별로 배분한다.

---

## 5. k6 시나리오

**파일 구조**:

```
server/load-test/
├── scenarios/
│   ├── 01-inventory-concurrency.js
│   ├── 02-pg-latency.js
│   ├── 03-scheduler-webhook-race.js
│   ├── 04-circuit-breaker.js
│   ├── 05-cancel-deadlock.js
│   └── 06-stress.js
├── lib/
│   ├── client.js     # API 호출 헬퍼 (공통 헤더, BASE_URL 등)
│   └── setup.js      # 공통 사전 데이터 생성 (토큰 발급, 상품 생성 등)
├── seed.sql
└── mock-pg/
    ├── server.js
    ├── package.json
    └── Dockerfile
```

### Scenario 1: 재고 동시성 (비관적 락)

- **목표**: 재고 10개 상품에 50 VU 동시 주문 → 정확히 10개만 성공, 초과 판매 없음
- **흐름**: `POST /orders` (50 VU 동시)
- **검증**: 성공 200 응답 = 10개, 나머지 = 재고 부족 에러, DB 최종 재고 = 0
- **핵심 메트릭**: `lockWaitProductTimer`, `hikaricp_connections_active`

### Scenario 2: PG 응답 지연 → DB 커넥션 홀딩

- **목표**: `completePayment` 내 트랜잭션이 PG 응답 대기 중 커넥션 점유 시간 측정
- **흐름**: order → prepare → `/control/complete(delayMs=3000)` → `POST /payments/complete`
- **VU**: 15명 동시 (HikariCP 기본 pool=10 초과)
- **검증**: 커넥션 풀 포화 시점, 대기 타임아웃 에러 발생 여부
- **핵심 메트릭**: `hikaricp_connections_active`, `hikaricp_connections_pending`

### Scenario 3: 스케줄러 + Webhook 경쟁

- **목표**: 주문 만료 처리 중 결제 완료 webhook 동시 도달 시 멱등성 검증
- **흐름**:
  1. order 생성 후 TTL(2분) 초과까지 대기
  2. k6 VU-A: `POST /payments/complete` (서버 verify)
  3. k6 VU-B: `webhookDelayMs=0`으로 즉시 webhook 발송 (동시에 스케줄러 만료 처리 중)
- **검증**: 재고 정확히 복구, PENDING 결제 잔존 없음, 중복 처리 없음

### Scenario 4: Circuit Breaker

- **목표**: PG 장애 시 Circuit Breaker 동작 확인
- **흐름**:
  1. `POST /control/server-error` 주입
  2. 다수 VU가 `completePayment` 호출
  3. Circuit OPEN 후 즉시 에러 응답 확인
  4. `POST /control/reset` → 회복 확인
- **핵심 메트릭**: `resilience4j_circuitbreaker_state`

### Scenario 5: 동시 취소 데드락 방지

- **목표**: 동일 상품 포함 주문 동시 취소 시 데드락 없음 증명
- **흐름**: 동일 상품 포함 주문 N개 사전 생성 → 동시에 전부 `cancelOrder` 호출
- **검증**: 모든 요청이 성공 또는 정상 에러 반환, DB deadlock 에러 없음

### Scenario 6: 부하 증가 (Stress Test)

- **목표**: 커넥션 풀 고갈 임계점 탐색
- **흐름**: 전체 결제 흐름 (order → prepare → control/complete → verify)
- **VU 램프업**: 10 → 50 → 100 → 200 (단계별 3분)
- **검증**: p99 응답시간, 에러율, HikariCP exhaustion 시점

---

## 6. 주요 측정 지표

| 지표 | 기대값 |
|---|---|
| HTTP p99 응답시간 | < 500ms (PG 지연 시나리오 제외) |
| 재고 초과 판매 | 0건 |
| PENDING 결제 잔존 | 0건 (만료 후) |
| HikariCP 고갈 시작 VU | 측정값으로 기록 |
| Circuit Breaker OPEN 전환 | 정상 동작 확인 |
| Deadlock 에러 | 0건 |
