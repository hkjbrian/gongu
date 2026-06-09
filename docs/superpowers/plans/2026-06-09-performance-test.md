# 성능 테스트 인프라 구축 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Mock PG 서버, k6 시나리오, perf 프로파일을 구축하여 주문/결제 도메인의 동시성·성능을 검증할 수 있는 테스트 인프라를 완성한다.

**Spec:** `docs/superpowers/specs/2026-06-09-performance-test-design.md`

**Tech Stack:** Node.js + Express (Mock PG), k6 (부하 테스트), Spring Boot @Profile("perf"), Docker Compose

---

## 사전 작업 (Claude가 직접 수행)

- [ ] `gh issue create --template chore-template.md` 로 이슈 생성
- [ ] `git checkout -b chore/#{이슈번호}-performance-test-infra`

---

## File Map

### 신규 생성

```
server/
├── load-test/
│   ├── mock-pg/
│   │   ├── server.js               # Task 1
│   │   ├── package.json            # Task 1
│   │   └── Dockerfile              # Task 1
│   ├── scenarios/
│   │   ├── 01-inventory-concurrency.js   # Task 5
│   │   ├── 02-pg-latency.js              # Task 5
│   │   ├── 03-scheduler-webhook-race.js  # Task 5
│   │   ├── 04-circuit-breaker.js         # Task 6
│   │   ├── 05-cancel-deadlock.js         # Task 6
│   │   └── 06-stress.js                  # Task 6
│   ├── lib/
│   │   ├── client.js               # Task 4
│   │   └── setup.js                # Task 4
│   └── seed.sql                    # Task 3
└── src/main/java/com/gongu/server/domain/auth/controller/
    └── TestAuthController.java     # Task 3
```

### 수정

```
server/
├── docker-compose.yml              # Task 2 — mock-pg 서비스 추가
└── src/main/resources/
    └── application-perf.yml        # Task 2 — 신규 파일
```

### 읽기 전용 (수정 금지)

- `src/main/java/com/gongu/server/global/security/jwt/JwtProvider.java` — generateAccessToken 시그니처 참고
- `src/main/java/com/gongu/server/global/common/ApiResponse.java` — 응답 래핑 패턴 참고
- `src/main/java/com/gongu/server/domain/auth/controller/AuthController.java` — 컨트롤러 패턴 참고
- `src/main/java/com/gongu/server/domain/auth/dto/response/TokenResponse.java` — 반환 타입 참고
- `src/main/java/com/gongu/server/domain/order/service/OrderService.java` — 주문 생성 흐름 파악
- `src/main/java/com/gongu/server/domain/payment/service/PaymentService.java` — 결제 흐름 파악
- `docs/schema/ddl.sql` — seed.sql 컬럼명 기준
- `src/main/resources/application.yml` — portone.base-url, resilience4j 설정 확인

---

## Task 1: Mock PG 서버 구현

**참고 문서/파일 (읽어야 할 것):**
- Spec: `docs/superpowers/specs/2026-06-09-performance-test-design.md` — Section 3 (Mock PG API)
- `src/main/resources/application.yml` — `portone.base-url`, `resilience4j` 설정 (Mock이 에뮬레이션해야 할 API 범위 파악)

**수정 대상 파일:**
- Create: `load-test/mock-pg/package.json`
- Create: `load-test/mock-pg/server.js`
- Create: `load-test/mock-pg/Dockerfile`

**구현 방향:**

`package.json`:
- `express` 의존성만 포함. `"start": "node server.js"` 스크립트.

`server.js` — 인메모리 `Map<paymentId, { status, amount, delayMs, webhookDelayMs }>` 관리:

제어 API:
- `POST /control/payments/:id/complete` — body `{ amount, delayMs?, webhookDelayMs? }`. Map에 `{ status: 'PAID', amount, delayMs: delayMs || 0 }` 저장. `webhookDelayMs > 0`이면 `setTimeout`으로 `SERVER_WEBHOOK_URL`에 PortOne webhook 형식 POST 발송.
- `POST /control/payments/:id/fail` — Map에 `{ status: 'FAILED' }` 저장.
- `POST /control/server-error` — 전역 플래그 `serverErrorMode = true` 설정.
- `POST /control/reset` — Map 전체 clear, `serverErrorMode = false`.

PortOne 에뮬레이션 API:
- `GET /payments/:id` — `serverErrorMode`이면 500 반환. Map에 없으면 404. `delayMs` 만큼 `setTimeout` 후 아래 형식으로 응답:
  ```json
  {
    "status": "PAID",
    "amount": { "total": 10000 },
    "paidAt": "2026-06-09T00:00:00+09:00"
  }
  ```
  status가 FAILED이면 `"status": "FAILED"` 반환.
- `POST /payments/:id/cancel` — Map의 해당 paymentId status를 `CANCELLED`로 업데이트. 200 반환. 없으면 404.

Webhook 발송 형식 (`SERVER_WEBHOOK_URL`에 POST):
```json
{ "paymentId": "<id>", "status": "PAID" }
```

환경변수:
- `SERVER_WEBHOOK_URL` — webhook 발송 대상 (default: `http://gongu-app:8080/payments/webhook`)
- `PORT` — 리스닝 포트 (default: `8090`)

`Dockerfile`:
- `node:20-alpine` 베이스. `COPY package.json . && npm install --production && COPY server.js .`. `CMD ["node", "server.js"]`.

**검증:**
```bash
cd load-test/mock-pg && npm install
node server.js &
curl -s -X POST http://localhost:8090/control/payments/test-001/complete \
  -H "Content-Type: application/json" -d '{"amount":10000,"delayMs":0}'
curl -s http://localhost:8090/payments/test-001
# {"status":"PAID","amount":{"total":10000},...} 반환 확인
curl -s -X POST http://localhost:8090/control/reset
kill %1
```

**커밋:**
```bash
git add load-test/mock-pg/
git commit -m "chore: Mock PG 서버 구현 (#152)"
```

---

## Task 2: docker-compose + application-perf.yml

**참고 문서/파일 (읽어야 할 것):**
- `docker-compose.yml` — 기존 서비스 구조, `gongu-net` 네트워크명 확인
- `src/main/resources/application.yml` — `portone.base-url`, `order.reservation-ttl-minutes` 키명 확인

**수정 대상 파일:**
- Modify: `docker-compose.yml`
- Create: `src/main/resources/application-perf.yml`

**구현 방향:**

`docker-compose.yml`에 `services` 블록 마지막에 추가:
```yaml
  mock-pg:
    build: ./load-test/mock-pg
    container_name: gongu-mock-pg
    ports:
      - "8090:8090"
    environment:
      - SERVER_WEBHOOK_URL=http://gongu-app:8080/payments/webhook
      - PORT=8090
    networks:
      - gongu-net
```
- `gongu-app`은 spring-app의 `container_name`과 일치해야 함. 현재 docker-compose에서 spring-app의 `container_name` 값을 확인 후 맞춤.
- `volumes:`, `networks:` 블록은 수정하지 않는다.

`application-perf.yml` 신규 생성:
```yaml
portone:
  base-url: http://gongu-mock-pg:8090

order:
  reservation-ttl-minutes: 2
```

**검증:**
```bash
./gradlew compileJava
docker compose build mock-pg
docker compose config | grep -A5 "mock-pg"
# mock-pg 서비스가 gongu-net 네트워크에 포함되어 있음을 확인
```

**커밋:**
```bash
git add docker-compose.yml src/main/resources/application-perf.yml
git commit -m "chore: docker-compose mock-pg 서비스 추가 및 perf 프로파일 설정 (#152)"
```

---

## Task 3: TestAuthController + seed.sql

**참고 문서/파일 (읽어야 할 것):**
- `src/main/java/com/gongu/server/domain/auth/controller/AuthController.java` — 컨트롤러 구조, `@RequestMapping` 패턴
- `src/main/java/com/gongu/server/domain/auth/dto/response/TokenResponse.java` — 반환 타입
- `src/main/java/com/gongu/server/global/security/jwt/JwtProvider.java` — `generateAccessToken(Long userId, Role role)`, `generateRefreshToken` 시그니처
- `src/main/java/com/gongu/server/global/security/jwt/RefreshTokenStore.java` — `save(userId, role, token)` 호출 방식
- `src/main/java/com/gongu/server/global/common/ApiResponse.java` — `ApiResponse.success()` 래핑 패턴
- `src/main/java/com/gongu/server/global/security/Role.java` — `Role.USER` 열거값 확인
- `docs/schema/ddl.sql` — `users` 테이블 컬럼명 (`name`, `phone`, `email`, `is_active`, `created_at`, `updated_at`, `deleted_at`)

**수정 대상 파일:**
- Create: `src/main/java/com/gongu/server/domain/auth/controller/TestAuthController.java`
- Create: `load-test/seed.sql`

**구현 방향:**

`TestAuthController.java`:
- `@Profile("perf")` + `@RestController` + `@RequestMapping("/auth")`
- `POST /auth/test-login` 엔드포인트:
  - Request body: `{ "userId": Long }`
  - `jwtProvider.generateAccessToken(userId, Role.USER)` 호출
  - `jwtProvider.generateRefreshToken(userId, Role.USER)` 호출
  - `refreshTokenStore.save(userId, Role.USER, refreshToken)` 호출
  - `TokenResponse(accessToken, refreshToken)` 를 `ApiResponse.success()` 로 래핑 후 `ResponseEntity.ok()` 반환
- 요청 DTO는 `record TestLoginRequest(Long userId) {}` 로 컨트롤러 파일 내부에 `private` record로 선언

`seed.sql`:
- `INSERT IGNORE INTO users (id, name, phone, is_active, created_at, updated_at)` 로 id 1~200을 명시 삽입
  - `name`: `'test_user_1'` ~ `'test_user_200'`, `phone`: `'010-0000-0001'` ~ `'010-0000-0200'`, `email`: NULL, `is_active`: 1
  - `created_at`, `updated_at`: `NOW()`
- `INSERT IGNORE` 사용으로 멱등하게 실행 가능 (재실행 시 중복 무시)
- k6 `setup.js`에서 `POST /auth/test-login` 호출 시 userId 1~200 고정값 사용

**검증:**
```bash
./gradlew compileJava
# perf 프로파일로 앱 구동 후:
curl -s -X POST http://localhost:8080/auth/test-login \
  -H "Content-Type: application/json" -d '{"userId":1}'
# accessToken, refreshToken 포함 응답 확인
```

**커밋:**
```bash
git add src/main/java/com/gongu/server/domain/auth/controller/TestAuthController.java \
        load-test/seed.sql
git commit -m "chore: perf 프로파일 테스트 로그인 컨트롤러 및 seed SQL 추가 (#152)"
```

---

## Task 4: k6 공통 라이브러리

**참고 문서/파일 (읽어야 할 것):**
- Spec: `docs/superpowers/specs/2026-06-09-performance-test-design.md` — Section 5 (시나리오 구조)
- `src/main/java/com/gongu/server/domain/order/controller/OrderController.java` — 주문 엔드포인트 경로/요청 형식
- `src/main/java/com/gongu/server/domain/payment/controller/PaymentController.java` — 결제 엔드포인트 경로
- `src/main/java/com/gongu/server/domain/user/controller/UserController.java` — `POST /users/me/stores` 엔드포인트 (스토어 구독)
- `src/main/java/com/gongu/server/domain/product/controller/AdminProductController.java` — 상품 생성 엔드포인트

**수정 대상 파일:**
- Create: `load-test/lib/client.js`
- Create: `load-test/lib/setup.js`

**구현 방향:**

`client.js` — API 호출 헬퍼 함수 모음:
- 상수: `BASE_URL`, `MOCK_PG_URL` (환경변수로 주입, default: `http://localhost:8080`, `http://localhost:8090`)
- `createOrder(token, productId, quantity)` → `POST /orders`
- `preparePayment(token, orderId)` → `POST /payments/prepare`
- `verifyPayment(token, orderId, paymentId)` → `POST /payments/verify` (body: `{ order_id, payment_id }`)
- `cancelOrder(token, orderId, reason)` → `POST /orders/{orderId}/cancel`
- `mockCompletePayment(paymentId, amount, delayMs, webhookDelayMs)` → `POST {MOCK_PG_URL}/control/payments/{paymentId}/complete`
- `mockServerError()` → `POST {MOCK_PG_URL}/control/server-error`
- `mockReset()` → `POST {MOCK_PG_URL}/control/reset`
- 모든 함수는 k6 `http` 모듈 사용. `Authorization: Bearer {token}` 헤더 공통 적용.

`setup.js` — `export function setup()` 함수:
- StoreAdmin 계정으로 `POST /auth/store-admin/login` 호출 → adminToken 획득
  - credentials: 환경변수 `ADMIN_EMAIL`, `ADMIN_PASSWORD`
- 테스트 상품 생성: `POST /admin/products` with adminToken → productId 반환
  - `stock: 10` (Scenario 1 기준), `status: ACTIVE`, `price: 10000`
- 유저 토큰 발급: userId 1~200에 대해 `POST /auth/test-login` 호출 → tokens 배열
- 각 유저가 테스트 스토어 구독: `POST /users/me/stores` with each userToken → `{ storeId }`
- return `{ tokens, productId, adminToken }`

**검증:**
```bash
# k6 단독 실행 검증 (setup만 동작 확인)
k6 run --env ADMIN_EMAIL=test@test.com --env ADMIN_PASSWORD=pass \
  -e 'export function default_fn() {}' load-test/lib/setup.js 2>&1 | head -30
```

**커밋:**
```bash
git add load-test/lib/
git commit -m "chore: k6 공통 라이브러리 추가 (#152)"
```

---

## Task 5: k6 시나리오 1~3

**참고 문서/파일 (읽어야 할 것):**
- Spec: `docs/superpowers/specs/2026-06-09-performance-test-design.md` — Section 5, Scenario 1~3
- `load-test/lib/client.js` — 헬퍼 함수 목록
- `load-test/lib/setup.js` — setup() 반환값 구조 (`tokens`, `productId`)
- `src/main/java/com/gongu/server/domain/order/service/OrderService.java` — createOrder 흐름 (비관적 락 타이밍 이해)
- `src/main/java/com/gongu/server/domain/payment/service/PaymentService.java` — completePayment 흐름 (PG 호출 위치 확인)

**수정 대상 파일:**
- Create: `load-test/scenarios/01-inventory-concurrency.js`
- Create: `load-test/scenarios/02-pg-latency.js`
- Create: `load-test/scenarios/03-scheduler-webhook-race.js`

**구현 방향:**

`01-inventory-concurrency.js`:
- VU: 50, duration: `1 iteration each` (모두 동시 시작하도록 `startTime: 0`)
- k6 options: `scenarios.concurrency_test.executor = 'shared-iterations'`, iterations: 50, vus: 50
- 각 VU: `createOrder(tokens[__VU-1], productId, 1)` 호출
- thresholds: `http_req_failed` 비율이 80% 미만 (50개 중 40개는 에러가 맞음), `checks` — "재고 부족 에러는 정확히 40개" 검증

`02-pg-latency.js`:
- VU: 15, duration: 60s
- 각 VU: order 생성 → prepare → `mockCompletePayment(paymentId, amount, delayMs=3000, webhookDelayMs=0)` → `verifyPayment(paymentId)` 순서
- thresholds: `http_req_duration{name:verify}` p95 측정값 기록 (pass/fail 기준 없음 — 관찰용)
- k6 실행 전 `mockReset()` 호출

`03-scheduler-webhook-race.js`:
- setup: order 생성 후 `sleep(130)` (TTL 2분 + 여유 10초) — 스케줄러가 만료 처리하도록 대기
- VU-A: `verifyPayment(paymentId)` 호출
- VU-B: `mockCompletePayment(paymentId, amount, 0, webhookDelayMs=0)` 후 즉시 webhook 트리거
- `scenarios` 옵션으로 VU-A, VU-B를 동시에 startTime: 0으로 실행
- checks: 응답이 성공 또는 멱등 처리 에러(ORDER_EXPIRED_REFUNDED 등)이고, 5xx 서버 에러가 없음을 검증

**검증:**
```bash
# docker compose up -d 후 perf 프로파일로 앱 기동 상태에서
k6 run load-test/scenarios/01-inventory-concurrency.js
# 성공 10건, 재고 부족 40건 출력 확인
```

**커밋:**
```bash
git add load-test/scenarios/01-inventory-concurrency.js \
        load-test/scenarios/02-pg-latency.js \
        load-test/scenarios/03-scheduler-webhook-race.js
git commit -m "chore: k6 시나리오 1~3 추가 (#152)"
```

---

## Task 6: k6 시나리오 4~6

**참고 문서/파일 (읽어야 할 것):**
- Spec: `docs/superpowers/specs/2026-06-09-performance-test-design.md` — Section 5, Scenario 4~6
- `load-test/lib/client.js`
- `src/main/resources/application.yml` — `resilience4j.circuitbreaker.instances.portone` 설정값 (sliding-window-size: 10, failure-rate-threshold: 50%)
- `src/main/java/com/gongu/server/domain/order/service/OrderService.java` — `cancelOrder` 락 순서 확인

**수정 대상 파일:**
- Create: `load-test/scenarios/04-circuit-breaker.js`
- Create: `load-test/scenarios/05-cancel-deadlock.js`
- Create: `load-test/scenarios/06-stress.js`

**구현 방향:**

`04-circuit-breaker.js`:
- Phase 1: `mockServerError()` 호출 → 20 VU가 `verifyPayment` 반복 → Circuit OPEN 확인
  - thresholds: 일정 횟수 이후 응답시간 급감 (OPEN 상태에서 즉시 실패) 확인
- Phase 2: `mockReset()` 호출 → Circuit 회복 → 정상 결제 성공 확인
- checks: Phase 2에서 성공 응답 존재 여부

`05-cancel-deadlock.js`:
- setup: 동일 상품을 포함하는 주문 30개 사전 생성 (paid 상태 불필요, RESERVED 상태로 충분)
- 30 VU가 동시에 각자의 orderId로 `cancelOrder` 호출
- checks: 모든 응답이 200 또는 비즈니스 에러(이미 취소됨 등), 5xx 없음
- thresholds: `http_req_failed` 0% (네트워크/5xx 에러 없음)

`06-stress.js`:
- executor: `ramping-vus`
- stages: `{ duration: '3m', target: 10 }`, `{ duration: '3m', target: 50 }`, `{ duration: '3m', target: 100 }`, `{ duration: '3m', target: 200 }`, `{ duration: '1m', target: 0 }`
- 각 VU: 전체 결제 흐름 (order → prepare → mockComplete → verify)
- thresholds: 기록 전용 (p99, error rate 관찰)
- Grafana에서 `hikaricp_connections_active` 그래프를 병행 모니터링

**검증:**
```bash
k6 run load-test/scenarios/05-cancel-deadlock.js
# http_req_failed=0%, deadlock 관련 에러 없음 확인

k6 run load-test/scenarios/04-circuit-breaker.js
# Phase 2에서 circuit recovery 성공 확인
```

**커밋:**
```bash
git add load-test/scenarios/04-circuit-breaker.js \
        load-test/scenarios/05-cancel-deadlock.js \
        load-test/scenarios/06-stress.js
git commit -m "chore: k6 시나리오 4~6 추가 (#152)"
```

---

## Task 7: 통합 검증 및 PR

**참고 문서/파일 (읽어야 할 것):**
- `.claude/github-rules.md` — PR 제목 형식, Milestone 연결 규칙

**수행 내용:**
- `docker compose up -d` (모든 서비스 기동 확인)
- spring-app을 `--spring.profiles.active=perf`로 기동
- seed.sql 실행: `docker exec -i gongu-mysql mysql -u root -p{PW} gongu_db < load-test/seed.sql`
- 전체 시나리오 순차 실행하여 각 검증 기준 통과 확인
- `./gradlew compileJava` 최종 확인

**커밋 후 PR 생성:**
```bash
gh pr create --title "[CHORE] 성능 테스트 인프라 구축 (#152)" \
  --body "..."
```
PR 생성 후 `CLAUDE.md` workflow 9~11단계(Codex 리뷰 위임 → 판정 → 반영) 따름.
