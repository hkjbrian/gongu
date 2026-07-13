# 매장 관리자 어드민 프론트엔드 설계 — admin-web

## 목적

매장 관리자가 상품·주문·회원을 웹 UI에서 관리할 수 있는 어드민 페이지를 구축한다.
기존 백엔드 API(AdminProductController, AdminOrderController, StoreAdminController, AuthController)를
그대로 소비하며, 신규 API는 추가하지 않는다.

백엔드 개발자가 프론트엔드 코드를 직접 작성/리뷰하지 않고 AI 코딩 도구로 전량 구현("바이브 코딩")할
것을 전제로, 정적 타입과 표준 패턴으로 실수를 컴파일 타임에 잡는 것을 최우선 설계 기준으로 삼는다.

## 범위

- 대상: 매장 관리자(STORE_ADMIN) 전용. 회원용 고객 앱은 별도 스펙에서 다룬다 (Non-goal).
- 신규 백엔드 API 추가 없음. 기존 엔드포인트만 연동한다.
- 자동화된 프론트엔드 테스트는 이번 스펙 범위에 포함하지 않는다 (Non-goal — 아래 "테스트 전략" 참고).

---

## 레포 구조

기존 레포(`hkjbrian/gongu`, git toplevel = 현재 서버 루트)에 서브디렉터리로 추가하는 모노레포 방식을 채택한다.

```text
server/                (레포 루트, 기존 Gradle 프로젝트)
├── src/                ← 기존 백엔드
├── build.gradle
├── docker-compose.yml  ← nginx 서비스 추가 예정
├── docs/
│   └── api/openapi.yaml
└── admin-web/          ← 신규, 완전히 독립된 Node 프로젝트
    ├── package.json
    ├── vite.config.ts
    ├── src/
    └── ...
```

`admin-web/`은 자체 `package.json`을 가지며 Gradle 빌드와 상호 간섭하지 않는다.

---

## 기술 스택

| 영역 | 선택 | 비고 |
|------|------|------|
| 빌드 도구 / 프레임워크 | Vite + React 18 + TypeScript | SSR 없는 SPA. 리소스 제약(2vCPU/2GB) 환경에 Node 서버 불필요 |
| 스타일링 | Tailwind CSS | |
| 컴포넌트 | shadcn/ui | 설치형 라이브러리가 아닌 코드 복사 방식 — AI가 컴포넌트 내부를 직접 수정 가능 |
| 데이터 그리드 | TanStack Table | 상품/주문/회원 목록, Spring `Page<T>` 페이지네이션과 매칭 |
| 서버 상태 관리 | TanStack Query | 로딩/에러/캐시/재요청 처리 |
| 라우팅 | React Router v6 | |
| API 클라이언트 | `openapi-typescript`로 타입 생성 + `openapi-fetch`로 호출 | `docs/api/openapi.yaml`을 소스로 사용. 백엔드 DTO 변경 시 스펙 재생성만으로 FE 타입 불일치를 컴파일 타임에 검출 |

---

## 페이지 / 라우트 구성

| 라우트 | 화면 | 연동 API |
|--------|------|----------|
| `/login` | 이메일/비밀번호 로그인 | `POST /auth/store-admin/login` |
| `/products` | 상품 목록 (TanStack Table) | `GET /admin/products` |
| `/products/new` | 상품 등록 폼 | `POST /admin/products` |
| `/products/:id` | 상품 상세/수정/삭제 (입고 처리 버튼 포함) | `GET/PUT/DELETE /admin/products/{id}`, `PUT /admin/products/{id}/arrive` |
| `/products/:id/orders` | 상품별 주문 목록 | `GET /admin/products/{productId}/orders` |
| `/users` | 회원 목록 (이름 검색) | `GET /admin/users` |
| `/users/:id/orders` | 회원별 주문 이력 | `GET /admin/users/{userId}/orders` |

인증된 라우트는 공통 레이아웃(사이드바 + 콘텐츠 영역)으로 감싼다. `/products`, `/products/new`, `/products/:id`는 모두 STORE_ADMIN 전용 `/admin/products` 엔드포인트를 사용하며, 회원용 `/products`(`UserProductController`, `hasRole('USER')`)와는 다른 API다.

---

## 인증 흐름

- 로그인 성공 시 access token / refresh token을 응답받아 저장한다 (access token은 메모리 변수, refresh token은 `localStorage`). 내부 어드민 도구이므로 XSS 대비 httpOnly 쿠키 대신 구현이 단순한 `localStorage`를 채택한다. 백엔드는 RTR(Refresh Token Rotation)을 적용하지 않고 refresh token 만료 기간이 7일이라, XSS 발생 시 최대 7일간 세션이 탈취될 수 있는 잔여 위험을 감수한다 (완화책: 아래 CSP 헤더, 및 Task 8의 `nginx.conf`).
- API 요청 시 access token을 `Authorization` 헤더에 부착하는 공통 fetch 래퍼를 사용한다.
- 401 응답 시 `POST /auth/token/refresh`로 access token을 재발급받고 원 요청을 재시도한다. **refresh 호출 자체는 이 401-재시도 로직이 걸리지 않은 별도의 raw 클라이언트로 보낸다** (같은 클라이언트를 쓰면 refresh token도 만료된 경우 refresh 호출의 401이 다시 refresh를 트리거해 무한 루프에 빠진다). 여러 요청이 동시에 401을 받으면 각자 refresh를 호출하지 않고 진행 중인 refresh Promise 하나를 공유한다(dedup). refresh 실패 시 저장된 토큰을 지우고 `/login`으로 리다이렉트한다.
- 권한 검증(STORE_ADMIN role)은 서버 `@PreAuthorize`가 담당하므로, 프론트는 토큰 존재 여부만으로 라우트를 가드한다.
- 앱 새로고침 직후에는 메모리의 access token이 비어 있으므로, refresh 시도가 끝나기 전까지는 인증 판정을 보류하는 `initializing` 상태를 둔다 (미보류 시 refresh가 완료되기 전에 `/login`으로 잘못 리다이렉트될 수 있음).

---

## API 스펙 보강

리뷰 과정에서 `docs/api/openapi.yaml`의 관리자 엔드포인트 문서화가 실제 컨트롤러 구현과 어긋나 있음을 확인했다:

- `/admin/products`: `POST`(등록)만 문서화되어 있고, `AdminProductController`에 실제로 존재하는 `GET`(목록), `GET /{id}`(상세), `PUT /{id}`(수정), `DELETE /{id}`(삭제)는 스펙에 없다.
- `/admin/products/{id}/orders`, `/admin/users/{id}/orders`, `/admin/users`: 200 응답이 `example`만 정의되어 있고 `schema`가 없어, `openapi-typescript`로 생성 시 응답 타입이 `unknown`으로 나온다.
- **더 중요한 문제**: 기존 스펙의 `example`/`schema` 필드명이 실제 서버 응답과 다르다. 프로젝트에 Jackson 네이밍 전략 설정이 없어 실제 JSON은 Java 필드명 그대로(camelCase, 예: `orderId`, `totalElements`)로 나가는데, `docs/api/openapi.yaml`의 기존 예시들은 snake_case(예: `order_id`, `total_elements`)로 작성되어 있고 `PageInfo`/`ProductItem` 같은 기존 스키마 컴포넌트도 동일하게 실제 응답과 다르다. **따라서 관리자 엔드포인트 스펙을 보강할 때 기존 example이나 다른 스키마 컴포넌트를 참고하지 말고, 반드시 실제 Java DTO 소스(컨트롤러 + 응답 DTO 클래스)를 직접 읽고 그 필드명 그대로 스펙을 작성한다.**

이 항목들은 **기존에 이미 존재하는 컨트롤러 구현**을 스펙에 뒤늦게 반영하는 것이므로 "신규 백엔드 API 추가 없음" 원칙과 충돌하지 않는다. Task 5(상품 관리)·Task 6(입고/주문 목록)·Task 7(회원/주문 이력) 착수 전에 각각 필요한 범위만큼 `docs/api/openapi.yaml`에 `schema`를 보강하고 `npm run generate:api`를 재실행한다.

- **(이미 수정 완료)** `docs/api/openapi.yaml`의 `servers[0].url`이 `http://localhost:8080/api/v1`로 되어 있었으나, 실제 서버에는 `context-path` 설정이 없고 컨트롤러 테스트도 `/admin/users`처럼 prefix 없이 호출한다 — 실제 서버 URL은 `http://localhost:8080`이다. `openapi-fetch`는 `servers` 값을 자동으로 baseUrl에 붙이지 않으므로 admin-web의 `VITE_API_BASE_URL`에 직접 영향은 없지만, 스펙과 실제 배포 설정이 어긋나는 걸 방지하기 위해 이 PR에서 바로잡았다.

> **확인된 사실**: 이 불일치는 관리자 엔드포인트에만 국한되지 않는다. `/auth/store-admin/login`, `/auth/token/refresh`(`StoreAdminLoginResponse`, `TokenRefreshResponse`), 상품 등록/수정 요청(`CreateProductRequest`, `UpdateProductRequest`), 입고 처리 응답(`ArriveProductResponse`)까지 admin-web이 호출하는 거의 모든 기존 엔드포인트에서 동일하게 발견됐다. **따라서 이 플랜의 모든 태스크(Task 3, 5, 6, 7)는 자신이 다루는 엔드포인트의 요청/응답 스키마를 구현 착수 전 반드시 실제 Java DTO 소스와 대조해서 확인·수정한다.** openapi.yaml에 이미 있는 example이나 schema 내용을 그대로 신뢰하지 않는다. (스펙 전체를 한 번에 재검증하는 것은 이 플랜의 범위 밖이며, admin-web이 실제로 소비하는 엔드포인트만 태스크별로 바로잡는다.)

---

## 배포

- `admin-web/`에서 `npm run build` → 정적 산출물(`dist/`) 생성.
- `docker-compose.yml`에 `nginx:alpine` 기반 서비스를 추가하여 `dist/`를 서빙하고 `gongu-net` 네트워크에 연결한다. Node 런타임을 상시 구동하지 않으므로 리소스 부담이 거의 없다.
- 기존 `server` 서비스의 `CORS_ALLOWED_ORIGINS` 환경변수(콤마로 구분된 다중 origin)에 신규 프론트 origin을 **추가**한다. 기존 고객용 프론트 origin(`http://localhost:3000`)을 대체하지 않는다 — 대체 시 고객 앱의 CORS가 깨진다.
- nginx.conf에 CSP(Content-Security-Policy) 헤더를 추가해 XSS로 인한 `localStorage` 토큰 탈취 위험을 완화한다 ("인증 흐름" 섹션 참고).

---

## 테스트 전략

내부 어드민 도구이고 바이브 코딩 특성상, 초기 단계에서는 자동화된 프론트엔드 테스트를 작성하지 않고
브라우저 수동 QA로 골든 패스(로그인 → 상품 등록 → 입고 처리 → 주문 조회)를 검증한다.
필요성이 커지면 이후 Vitest + React Testing Library를 별도로 도입한다.

---

## 변경/신규 파일

| 파일 | 변경 내용 |
|------|----------|
| `admin-web/` | 신규 디렉터리, Vite 프로젝트 전체 |
| `docker-compose.yml` | 정적 파일 서빙용 nginx 서비스 추가, `CORS_ALLOWED_ORIGINS`에 신규 origin 추가(기존 값 유지) |
| `docs/api/openapi.yaml` | 관리자 엔드포인트 응답 스키마 보강 (아래 "API 스펙 보강" 참고) |
| `admin-web/.env`, `admin-web/.env.example` | 프론트 환경변수 (예: `VITE_API_BASE_URL`) — Vite는 `admin-web/`을 프로젝트 루트로 실행되므로 레포 루트의 `.env`는 로드되지 않는다 |
