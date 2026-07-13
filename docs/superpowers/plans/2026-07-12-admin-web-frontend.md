# 매장 관리자 어드민 프론트엔드 (admin-web) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 매장 관리자가 상품·주문·회원을 관리할 수 있는 어드민 웹 프론트엔드(`admin-web/`)를 구축하고, 기존 docker-compose 인프라에 정적 파일로 배포한다.

**Spec:** `docs/superpowers/specs/2026-07-12-admin-web-frontend-design.md`

**Milestone:** [관리자 어드민 프론트엔드](https://github.com/hkjbrian/gongu/milestone/10)

**Tech Stack:** Vite, React 18, TypeScript, Tailwind CSS, shadcn/ui, TanStack Query, TanStack Table, React Router v6, openapi-typescript + openapi-fetch

---

## 변경 파일 맵

| 파일/디렉터리 | 작업 | 관련 이슈 |
|---|---|---|
| `admin-web/` (신규 프로젝트 전체) | Create | #176 |
| `admin-web/src/lib/api/schema.d.ts`, `admin-web/src/lib/api/client.ts` | Create | #177 |
| `admin-web/src/lib/auth/*`, `admin-web/src/routes/login/*` | Create | #178 |
| `admin-web/src/routes/layout/*`, `admin-web/src/router.tsx` | Create | #179 |
| `admin-web/src/routes/products/ProductListPage.tsx`, `ProductFormPage.tsx`, `ProductDetailPage.tsx` | Create | #180 |
| `docs/api/openapi.yaml` (`/admin/products` GET/PUT/DELETE 보강) | Modify | #180 |
| `admin-web/src/routes/products/ProductOrdersPage.tsx` | Create | #181 |
| `docs/api/openapi.yaml` (`/admin/products/{id}/orders` 응답 schema 보강) | Modify | #181 |
| `admin-web/src/routes/users/UserListPage.tsx`, `UserOrdersPage.tsx` | Create | #182 |
| `docs/api/openapi.yaml` (`/admin/users`, `/admin/users/{id}/orders` 응답 schema 보강) | Modify | #182 |
| `docker-compose.yml` | Modify | #183 |

**읽기 전용 참조 파일 (모든 태스크 공통):**
- `docs/superpowers/specs/2026-07-12-admin-web-frontend-design.md` — 전체 설계 근거
- `docs/api/openapi.yaml` — API 계약
- `docs/02-domain-rules.md` — 상태 전이 규칙 (UI에서 잘못된 액션 노출 방지용)

---

### Task 1: admin-web 프로젝트 스캐폴딩

**참고 문서/파일 (읽어야 할 것):**
- Spec: `docs/superpowers/specs/2026-07-12-admin-web-frontend-design.md` — "기술 스택", "레포 구조" 섹션

**수정 대상 파일:**
- Create: `admin-web/package.json`, `admin-web/vite.config.ts`, `admin-web/tsconfig.json`, `admin-web/tailwind.config.ts`, `admin-web/postcss.config.js`, `admin-web/index.html`
- Create: `admin-web/src/main.tsx`, `admin-web/src/App.tsx`, `admin-web/src/index.css`
- Create: `admin-web/components.json` (shadcn/ui 설정), `admin-web/src/lib/utils.ts` (shadcn 기본 유틸)

**금지 사항:**
- 레포 루트의 `build.gradle`, `settings.gradle`, `src/` (Java) — 건드리지 않음
- 실제 페이지 컴포넌트 구현 — 이후 태스크에서 진행 (App.tsx는 빈 placeholder만)

**구현 방향:**
- `npm create vite@latest admin-web -- --template react-ts` 기반으로 초기화
- Tailwind CSS v3 설치 및 `tailwind.config.ts`에 `content` 경로 설정
- shadcn/ui 초기화 (`npx shadcn@latest init`) — 기본 스타일은 "New York" 또는 기본값 사용
- `react-router-dom`, `@tanstack/react-query`, `@tanstack/react-table` 의존성 추가
- `package.json`에 `dev`, `build`, `preview` 스크립트 확인

**검증:**
```bash
cd admin-web && npm install && npm run dev
```
Expected: 로컬 dev 서버 정상 구동 (기본 Vite+React 화면 표시)

```bash
cd admin-web && npm run build
```
Expected: `dist/` 디렉터리 생성, 빌드 에러 없음

**커밋:**
```bash
git add admin-web
git commit -m "chore: admin-web 프로젝트 스캐폴딩 (#176)"
```

---

### Task 2: OpenAPI 타입/클라이언트 코드 생성 파이프라인

**참고 문서/파일 (읽어야 할 것):**
- `docs/api/openapi.yaml` — 전체 API 스펙
- `admin-web/package.json` — Task 1에서 만든 프로젝트 구조

**수정 대상 파일:**
- Create: `admin-web/src/lib/api/schema.d.ts` (생성 산출물)
- Create: `admin-web/src/lib/api/client.ts`
- Modify: `admin-web/package.json` — `generate:api` 스크립트 추가

**금지 사항:**
- `docs/api/openapi.yaml` 자체는 수정하지 않음 (이 태스크는 코드 생성 파이프라인 구축만 담당). 단, `/admin/products` 계열 관리자 엔드포인트 스펙이 불완전한 상태(POST만 문서화됨)라 해당 응답 타입은 `unknown`으로 생성될 수 있음 — 이 스펙 보강은 Task 5/6/7에서 각 태스크가 실제로 필요로 하는 범위만큼 처리한다.
- 인증 토큰 부착 로직 — Task 3에서 처리

**구현 방향:**
- `openapi-typescript`, `openapi-fetch` 의존성 추가
- `package.json`에 `"generate:api": "openapi-typescript ../docs/api/openapi.yaml -o src/lib/api/schema.d.ts"` 스크립트 추가
- `src/lib/api/client.ts`에서 `openapi-fetch`의 `createClient<paths>()`로 기본 클라이언트 생성, `baseUrl`은 환경변수(`VITE_API_BASE_URL`)로 주입
- `.env.example` 파일에 `VITE_API_BASE_URL=http://localhost:8080` 추가

**검증:**
```bash
cd admin-web && npm run generate:api
```
Expected: `src/lib/api/schema.d.ts`가 `docs/api/openapi.yaml` 기준으로 생성됨, 타입 에러 없이 `npm run build` 통과

**커밋:**
```bash
git add admin-web/src/lib/api admin-web/package.json admin-web/.env.example
git commit -m "chore: OpenAPI 타입/클라이언트 코드 생성 파이프라인 구성 (#177)"
```

---

### Task 3: 어드민 로그인 및 인증 흐름

**참고 문서/파일 (읽어야 할 것):**
- Spec: "인증 흐름" 섹션
- `docs/api/openapi.yaml` — `/auth/store-admin/login`, `/auth/token/refresh` 스키마
- `admin-web/src/lib/api/client.ts` — Task 2에서 만든 API 클라이언트

**수정 대상 파일:**
- Create: `admin-web/src/routes/login/LoginPage.tsx`
- Create: `admin-web/src/lib/auth/token-storage.ts` (access token 메모리 저장, refresh token localStorage 저장)
- Create: `admin-web/src/lib/auth/auth-fetch.ts` (401 시 refresh 후 재시도하는 공통 fetch 미들웨어)
- Create: `admin-web/src/lib/auth/RequireAuth.tsx` (라우트 가드 컴포넌트)

**금지 사항:**
- 사이드바/레이아웃 구현 — Task 4에서 진행 (로그인 페이지는 레이아웃 없이 단독 페이지)
- STORE_ADMIN 권한 검증 로직을 프론트에서 재구현하지 않음 (서버 `@PreAuthorize`가 담당, 프론트는 토큰 유무만 확인)

**구현 방향:**
- `LoginPage`: 이메일/비밀번호 폼 → `POST /auth/store-admin/login` 호출 → 성공 시 access token은 메모리 변수(모듈 스코프 또는 React Context)에, refresh token은 `localStorage`에 저장 후 `/products`로 리다이렉트
- refresh 호출 전용 raw 클라이언트를 별도로 만든다 (`auth-fetch.ts`의 401 재시도 미들웨어가 붙지 않은 순수 `openapi-fetch` 인스턴스). `POST /auth/token/refresh`는 반드시 이 raw 클라이언트로만 호출한다 — 401 재시도 미들웨어가 걸린 클라이언트로 refresh를 호출하면, refresh token도 만료된 경우 refresh 응답의 401이 다시 refresh를 트리거해 무한 루프에 빠진다.
- `auth-fetch.ts`: `openapi-fetch` 클라이언트에 미들웨어로 등록. 요청 시 `Authorization: Bearer {accessToken}` 부착. 401 응답 수신 시 위 raw 클라이언트로 `POST /auth/token/refresh` 호출로 access token 재발급 후 원 요청 1회 재시도. 여러 요청이 동시에 401을 받으면 각자 refresh를 호출하지 않고 진행 중인 refresh Promise 하나를 공유(dedup)한다. refresh도 실패하면 저장된 토큰을 지우고 `/login`으로 리다이렉트
- 인증 상태에 `initializing` 값을 둔다: 앱 최초 로드 시 메모리 access token은 비어 있으므로, refresh token으로 최초 1회 재발급을 시도하는 동안은 `initializing=true`로 유지하고 `RequireAuth`가 리다이렉트 판정을 보류한다. 시도가 끝난 후에만 `/login` 리다이렉트 여부를 결정한다 (미보류 시 새로고침 직후 정상 로그인 상태인데도 `/login`으로 잘못 튕길 수 있음).
- `RequireAuth`: `initializing`이 끝난 뒤 메모리에 access token이 없으면 `/login`으로 `<Navigate>` 처리하는 래퍼 컴포넌트

**검증:**
```bash
cd admin-web && npm run build
```
Expected: 타입 에러 없이 빌드 성공

수동 검증: 실제 서버(`./gradlew bootRun`) 기동 후 로그인 폼으로 `POST /auth/store-admin/login` 호출 → 성공/실패 응답 처리 확인 (브라우저 devtools network 탭)

**커밋:**
```bash
git add admin-web/src/routes/login admin-web/src/lib/auth
git commit -m "feat: 어드민 로그인 및 인증 흐름 구현 (#178)"
```

---

### Task 4: 공통 레이아웃 및 라우팅 뼈대

**참고 문서/파일 (읽어야 할 것):**
- Spec: "페이지/라우트 구성" 섹션
- `admin-web/src/lib/auth/RequireAuth.tsx` — Task 3에서 만든 라우트 가드

**수정 대상 파일:**
- Create: `admin-web/src/routes/layout/AdminLayout.tsx` (사이드바 + 콘텐츠 영역)
- Create: `admin-web/src/router.tsx` (전체 라우트 트리)
- Modify: `admin-web/src/App.tsx` — `RouterProvider` 연결

**금지 사항:**
- 상품/회원 페이지의 실제 데이터 연동 — 이후 태스크에서 진행 (이 태스크에서는 빈 placeholder 컴포넌트로 라우트만 연결)

**구현 방향:**
- `AdminLayout`: shadcn/ui 컴포넌트로 사이드바 구성, 메뉴 항목은 "상품 관리"(`/products`), "회원 관리"(`/users`) 2개
- `router.tsx`: `/login`은 `AdminLayout` 밖, 나머지 라우트(`/products`, `/products/new`, `/products/:id`, `/products/:id/orders`, `/users`, `/users/:id/orders`)는 `RequireAuth`로 감싼 `AdminLayout` 하위 라우트로 구성. 각 페이지는 이 태스크에서는 빈 placeholder(`<div>TODO</div>`)로 생성해두고 이후 태스크가 채움

**검증:**
```bash
cd admin-web && npm run dev
```
Expected: 로그인 후 사이드바 렌더링, 메뉴 클릭 시 URL과 placeholder 화면 전환 확인 (브라우저 수동 확인)

**커밋:**
```bash
git add admin-web/src/routes/layout admin-web/src/router.tsx admin-web/src/App.tsx
git commit -m "feat: 공통 레이아웃 및 라우팅 뼈대 구현 (#179)"
```

---

### Task 5: 상품 관리 페이지 (목록/등록/수정/삭제)

**참고 문서/파일 (읽어야 할 것):**
- `docs/api/openapi.yaml` — `/admin/products` 스펙. **주의**: 현재 `POST`만 문서화되어 있고 `GET`(목록)·`GET /{id}`(상세)·`PUT /{id}`(수정)·`DELETE /{id}`(삭제)는 없음 — 아래 "구현 방향" 1번에서 먼저 보강한다.
- `src/main/java/com/gongu/server/domain/product/controller/AdminProductController.java` — 실제 관리자 상품 엔드포인트 (`/admin/products`, `hasRole('STORE_ADMIN')`). **회원용 `UserProductController`의 `/products`(`hasRole('USER')`)와 절대 혼동하지 말 것.**
- `src/main/java/com/gongu/server/domain/product/dto/ProductSummaryResponse.java`, `ProductDetailResponse.java` — 실제 응답 DTO 필드 (스펙 작성의 유일한 근거)
- `docs/02-domain-rules.md` — Product 불변식 (price>0, totalStock>0, startAt<endAt)
- `admin-web/src/router.tsx` — Task 4에서 만든 라우트 경로

**수정 대상 파일:**
- Modify: `docs/api/openapi.yaml` — `/admin/products`에 `GET`(목록) 추가, `/admin/products/{product_id}` 경로 신설 후 `GET`/`PUT`/`DELETE` 추가. **주의**: 기존 `/products`의 `ProductListResponse`/`ProductItem`/`PageInfo` 스키마 컴포넌트는 필드명이 snake_case로 실제 응답(camelCase)과 다르므로 참고하지 않는다. 대신 아래 실제 DTO 필드를 그대로 사용한다 (`security: [BearerAuth]` 포함):
  - 목록(`ProductSummaryResponse`, Page로 래핑): `id`, `name`, `price`, `remainingStock`, `status`, `startAt`, `endAt`
  - 상세/등록/수정 응답(`ProductDetailResponse`): `id`, `name`, `description`, `price`, `totalStock`, `remainingStock`, `status`, `startAt`, `endAt`
  - 삭제(`DELETE`) 응답은 본문 없음(204 No Content)
  - Page 래핑은 Spring Data 기본 직렬화 구조(`content`, `totalElements`, `totalPages`, `number`, `size` 등)를 따른다 — `PageInfo` 컴포넌트(snake_case)를 재사용하지 않는다
- Create: `admin-web/src/routes/products/ProductListPage.tsx`
- Create: `admin-web/src/routes/products/ProductFormPage.tsx` (등록/수정 공용)
- Create: `admin-web/src/routes/products/ProductDetailPage.tsx`
- Modify: `admin-web/src/router.tsx` — placeholder를 실제 컴포넌트로 교체

**금지 사항:**
- 입고 처리 액션, 상품별 주문 목록 — Task 6에서 진행 (이 태스크의 상세 페이지에는 해당 UI를 넣지 않음)
- 회원용 `/products` 엔드포인트나 `UserProductController`는 건드리지 않음 (이 태스크는 관리자 화면이므로 `/admin/products`만 사용)

**구현 방향:**
0. `docs/api/openapi.yaml`의 `/admin/products`, `/admin/products/{product_id}` 스펙을 보강한 뒤 `cd admin-web && npm run generate:api`로 타입을 재생성한다.
- `ProductListPage`: TanStack Table + TanStack Query로 `GET /admin/products` 목록 조회, 페이지네이션은 서버 `Page<T>` 응답 구조(`content`, `totalPages`, `number` 등)에 맞춤
- `ProductFormPage`: 상품명/설명/가격/총수량/판매기간(시작~종료) 입력 폼, 클라이언트 사이드 검증(가격>0, 총수량>0, 시작<종료)은 서버 검증의 UX 보조 목적으로만 추가하고 서버 응답 에러를 최종 판단 기준으로 표시
- `ProductDetailPage`: 상품 상세 조회 + 수정/삭제 버튼 (수정은 `ProductFormPage`로 이동, 삭제는 확인 다이얼로그 후 `DELETE /admin/products/{id}`)

**검증:**
```bash
cd admin-web && npm run generate:api && npm run build
```
Expected: `schema.d.ts`에 `/admin/products`, `/admin/products/{product_id}` GET/PUT/DELETE 타입이 `unknown` 없이 생성됨, 타입 에러 없이 빌드 성공

수동 검증: 실제 서버 기동 후 상품 등록 → 목록에 표시 → 수정 → 삭제까지 전체 흐름을 브라우저에서 확인

**커밋:**
```bash
git add docs/api/openapi.yaml admin-web/src/lib/api admin-web/src/routes/products admin-web/src/router.tsx
git commit -m "feat: 상품 관리 페이지 구현 (#180)"
```

---

### Task 6: 상품 입고 처리 및 상품별 주문 목록

**참고 문서/파일 (읽어야 할 것):**
- `docs/api/openapi.yaml` — `PUT /admin/products/{productId}/arrive`, `GET /admin/products/{productId}/orders`. **주의**: `/admin/products/{productId}/orders`의 200 응답은 현재 `example`만 있고 `schema`가 없으며, 그 `example` 내용도 실제 응답과 다르다 (아래 참고).
- `src/main/java/com/gongu/server/domain/order/controller/AdminOrderController.java` — 실제 엔드포인트: `getOrdersByProduct`는 `ApiResponse<Page<OrderSummaryResponse>>`를 반환한다 (기존 스펙 example의 `product_id`/`user_name`/`user_phone`/`status_summary` 등은 실제 응답에 없음).
- `src/main/java/com/gongu/server/domain/order/dto/response/OrderSummaryResponse.java` — 실제 응답 DTO 필드 (스펙 작성의 유일한 근거): `orderId`, `productName`, `quantity`, `totalPrice`, `status`, `createdAt`. **주문자 이름/연락처 필드는 없다** — 상품별 주문 목록 화면에는 주문자 정보를 표시할 수 없다. 백엔드 확장은 [#190](https://github.com/hkjbrian/gongu/issues/190)으로 별도 추적 중이며, 이 이슈가 완료되면 `ProductOrdersPage`에 주문자 컬럼을 추가하는 후속 작업이 필요하다.
- `docs/02-domain-rules.md` — Order 상태 전이 (PAID → ARRIVED), 입고 처리 시 관련 주문 일괄 갱신 규칙
- `admin-web/src/routes/products/ProductDetailPage.tsx` — Task 5에서 만든 상세 페이지

**수정 대상 파일:**
- Modify: `docs/api/openapi.yaml` — `/admin/products/{product_id}/orders` 200 응답에 `OrderSummaryResponse` 실제 필드 기준 `schema` 추가 (Page 래핑은 Spring Data 기본 구조: `content`, `totalElements`, `totalPages` 등)
- Create: `admin-web/src/routes/products/ProductOrdersPage.tsx`
- Modify: `admin-web/src/routes/products/ProductDetailPage.tsx` — 입고 처리 버튼 및 주문 목록 링크 추가
- Modify: `admin-web/src/router.tsx` — `/products/:id/orders` placeholder를 실제 컴포넌트로 교체

**금지 사항:**
- 회원별 주문 이력 — Task 7에서 진행
- 존재하지 않는 필드(주문자 이름/연락처 등)를 화면에 표시하려 하지 않음 — `OrderSummaryResponse`에 있는 필드만 사용

**구현 방향:**
0. `docs/api/openapi.yaml`의 `/admin/products/{product_id}/orders` 200 응답에 `OrderSummaryResponse` 실제 필드로 `schema`를 추가한 뒤 `cd admin-web && npm run generate:api`로 타입을 재생성한다.
- `ProductDetailPage`에 "입고 처리" 버튼 추가, 클릭 시 확인 다이얼로그 → `PUT /admin/products/{productId}/arrive` 호출 → 성공 시 상품 상태 갱신 반영
- `ProductOrdersPage`: TanStack Table로 `GET /admin/products/{productId}/orders` 목록 조회 (상품명, 수량, 금액, 상태, 주문일시 컬럼 — 주문자 컬럼은 없음), 페이지네이션 적용

**검증:**
```bash
cd admin-web && npm run generate:api && npm run build
```
Expected: `/admin/products/{productId}/orders` 응답 타입이 `orderId`/`productName`/`quantity`/`totalPrice`/`status`/`createdAt` 필드로 정확히 생성됨 (unknown 없음), 타입 에러 없이 빌드 성공

수동 검증: ACTIVE 상품에 주문 생성 후 입고 처리 → 관련 주문 상태가 ARRIVED로 바뀌는지 상품별 주문 목록에서 확인

**커밋:**
```bash
git add docs/api/openapi.yaml admin-web/src/lib/api admin-web/src/routes/products admin-web/src/router.tsx
git commit -m "feat: 상품 입고 처리 및 상품별 주문 목록 구현 (#181)"
```

---

### Task 7: 회원 목록 및 회원별 주문 이력

**참고 문서/파일 (읽어야 할 것):**
- `docs/api/openapi.yaml` — `GET /admin/users` (name 쿼리 파라미터), `GET /admin/users/{userId}/orders`. **주의**: 두 엔드포인트 모두 200 응답이 `example`만 있고 `schema`가 없으며, 그 `example` 내용도 실제 응답과 다르다 (아래 참고).
- `src/main/java/com/gongu/server/domain/store/dto/response/AdminUserResponse.java` — 실제 회원 목록 응답 DTO 필드 (스펙 작성의 유일한 근거): `userId`, `name`, `phone`, `registeredAt`. **`order_count` 필드는 없다.**
- `src/main/java/com/gongu/server/domain/order/dto/response/OrderSummaryResponse.java` — `GET /admin/users/{userId}/orders`도 Task 6과 동일한 `OrderSummaryResponse`를 재사용한다 (`orderId`, `productName`, `quantity`, `totalPrice`, `status`, `createdAt`).
- `admin-web/src/router.tsx` — Task 4에서 만든 라우트 경로

**수정 대상 파일:**
- Modify: `docs/api/openapi.yaml` — `/admin/users` 200 응답에 `AdminUserResponse` 실제 필드 기준 `schema` 추가, `/admin/users/{user_id}/orders`는 Task 6에서 정의한 `OrderSummaryResponse` 스키마를 재사용
- Create: `admin-web/src/routes/users/UserListPage.tsx`
- Create: `admin-web/src/routes/users/UserOrdersPage.tsx`
- Modify: `admin-web/src/router.tsx` — `/users`, `/users/:id/orders` placeholder를 실제 컴포넌트로 교체

**금지 사항:**
- 상품 관련 페이지 — Task 5/6에서 이미 완료, 이 태스크에서 재수정하지 않음
- `UserListPage`에 존재하지 않는 `order_count`(주문 수) 컬럼을 넣지 않음

**구현 방향:**
0. `docs/api/openapi.yaml`의 `/admin/users` 200 응답에 `AdminUserResponse` 실제 필드로 `schema`를 추가한 뒤 `cd admin-web && npm run generate:api`로 타입을 재생성한다 (`/admin/users/{user_id}/orders`는 Task 6에서 이미 보강됨).
- `UserListPage`: 이름 검색 입력(디바운스 적용) + TanStack Table로 `GET /admin/users?name=` 목록 조회 (이름, 연락처, 가입일 컬럼), 각 행에서 `UserOrdersPage`로 이동하는 링크 제공
- `UserOrdersPage`: 특정 회원의 `GET /admin/users/{userId}/orders` 주문 이력 목록 (상품명, 수량, 금액, 상태, 주문일시 컬럼)

**검증:**
```bash
cd admin-web && npm run generate:api && npm run build
```
Expected: `/admin/users` 응답 타입이 `userId`/`name`/`phone`/`registeredAt` 필드로 정확히 생성됨 (unknown 없음), 타입 에러 없이 빌드 성공

수동 검증: 이름 검색으로 회원 필터링 → 특정 회원 클릭 → 주문 이력 목록 표시 확인

**커밋:**
```bash
git add docs/api/openapi.yaml admin-web/src/lib/api admin-web/src/routes/users admin-web/src/router.tsx
git commit -m "feat: 회원 목록 및 회원별 주문 이력 페이지 구현 (#182)"
```

---

### Task 8: admin-web 배포 설정 (nginx + CORS)

**참고 문서/파일 (읽어야 할 것):**
- Spec: "배포" 섹션
- `docker-compose.yml` — 기존 서비스 구조, `gongu-net` 네트워크명, `server` 서비스의 `CORS_ALLOWED_ORIGINS` 환경변수

**수정 대상 파일:**
- Modify: `docker-compose.yml` — `admin-web` 서비스(nginx) 추가, `server` 서비스 `CORS_ALLOWED_ORIGINS`에 신규 origin 추가
- Create: `admin-web/Dockerfile` (멀티스테이지: Node 빌드 → nginx 서빙)
- Create: `admin-web/nginx.conf` (SPA 라우팅을 위한 fallback 설정 + CSP 헤더 포함)

**금지 사항:**
- `redis`, `mysql`, `prometheus`, `grafana`, `server` 서비스의 기존 설정(포트, cpuset, 리소스 제한) — CORS 값 외에는 변경 금지
- 기존 `CORS_ALLOWED_ORIGINS` 값(예: `http://localhost:3000`)을 삭제/대체하지 않음 — 반드시 콤마로 이어붙여 추가만 한다 (대체 시 기존 고객용 프론트의 CORS가 깨짐)
- CI/CD 자동 빌드 파이프라인 — 범위 밖

**구현 방향:**
- `admin-web/Dockerfile`: build stage에서 `npm ci && npm run build`, runtime stage는 `nginx:alpine`으로 `dist/`를 `/usr/share/nginx/html`에 복사
- `admin-web/nginx.conf`: SPA이므로 존재하지 않는 경로는 `index.html`로 fallback (`try_files $uri /index.html`). refresh token이 `localStorage`에 저장되는 점(Task 3)을 감안해 `Content-Security-Policy` 헤더를 추가해 인라인 스크립트 실행을 제한한다 (XSS 완화).
- `docker-compose.yml`에 `admin-web` 서비스 추가: `build: ./admin-web`, `gongu-net` 네트워크 연결, 포트는 기존 서비스와 겹치지 않는 값(예: `3002:80`) 사용
- `server` 서비스의 `CORS_ALLOWED_ORIGINS` 값을 `${CORS_ALLOWED_ORIGINS:-http://localhost:3000},http://localhost:3002` 형태로 **기존 값에 추가**한다 (대체 금지)

**검증:**
```bash
docker compose build admin-web && docker compose up -d admin-web
curl -I http://localhost:3002
```
Expected: `200 OK` 응답, 브라우저로 접속 시 로그인 페이지 정상 표시. 로그인 후 API 호출 시 CORS 에러 없음 확인

**커밋:**
```bash
git add docker-compose.yml admin-web/Dockerfile admin-web/nginx.conf
git commit -m "chore: admin-web 배포 설정 nginx+CORS 구성 (#183)"
```

---

## PR 및 리뷰

각 태스크 완료 후 PR 생성 → CLAUDE.md 9~11단계(Codex 리뷰 위임 → Claude 판정 → 인라인 코멘트/재반영) 절차를 따른다. PR은 태스크당 1개씩 생성한다 (하나의 거대 PR로 묶지 않음).
