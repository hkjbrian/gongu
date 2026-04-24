# 테이블 정의서

> 변경 시 반드시 이 문서와 `ddl.sql`을 함께 수정할 것.

## 목차

- [stores](#stores)
- [store_admins](#store_admins)
- [member_stores](#member_stores)
- [users](#users)
- [user_social](#user_social)
- [products](#products)
- [orders](#orders)
- [order_items](#order_items)
- [payments](#payments)

---

## stores

매장 정보. 소프트 딜리트 적용 (`deleted_at`).

| 컬럼 | 타입 | NULL | 기본값 | 설명 |
|------|------|------|--------|------|
| id | bigint | NO | — | PK |
| name | varchar(255) | NO | — | 매장명 |
| address | varchar(255) | NO | — | 주소 |
| phone | varchar(20) | NO | — | 전화번호 |
| is_active | boolean | NO | — | 서비스 노출 여부 |
| created_at | datetime | NO | — | 생성일시 |
| updated_at | datetime | NO | — | 수정일시 |
| deleted_at | datetime | YES | NULL | 삭제일시 (NULL = 활성) |

**인덱스**

| 이름 | 대상 컬럼 | 종류 |
|------|-----------|------|
| PK_STORES | id | PRIMARY |

---

## store_admins

매장 관리자 계정. 이메일/비밀번호 로그인 전용. 소프트 딜리트 적용.

| 컬럼 | 타입 | NULL | 기본값 | 설명 |
|------|------|------|--------|------|
| id | bigint | NO | — | PK |
| store_id | bigint | NO | — | FK → stores.id |
| email | varchar(255) | NO | — | 로그인 이메일 (UNIQUE) |
| password | varchar(100) | NO | — | 암호화된 비밀번호 |
| name | varchar(50) | NO | — | 관리자명 |
| is_active | boolean | NO | — | 계정 활성 여부 |
| created_at | datetime | NO | — | 생성일시 |
| updated_at | datetime | NO | — | 수정일시 |
| deleted_at | datetime | YES | NULL | 삭제일시 (NULL = 활성) |

**인덱스**

| 이름 | 대상 컬럼 | 종류 |
|------|-----------|------|
| PK_STORE_ADMINS | id | PRIMARY |
| UQ_STORE_ADMINS_EMAIL | email | UNIQUE |
| IDX_STORE_ADMINS_STORE_ID | store_id | INDEX (FK) |

**FK**

| 컬럼 | 참조 테이블 | 참조 컬럼 |
|------|-------------|-----------|
| store_id | stores | id |

---

## member_stores

회원-매장 다대다 연결 테이블. 회원이 이용 중인 매장 목록을 관리.

| 컬럼 | 타입 | NULL | 기본값 | 설명 |
|------|------|------|--------|------|
| id | bigint | NO | — | PK |
| user_id | bigint | NO | — | FK → users.id |
| store_id | bigint | NO | — | FK → stores.id |
| is_preferred | boolean | NO | — | 단골 매장 여부 |
| created_at | datetime | NO | — | 등록일시 |

**인덱스**

| 이름 | 대상 컬럼 | 종류 | 비고 |
|------|-----------|------|------|
| PK_MEMBER_STORES | id | PRIMARY | |
| UQ_MEMBER_STORES_USER_STORE | (user_id, store_id) | UNIQUE | 동일 매장 중복 등록 방지 |
| IDX_MEMBER_STORES_USER_ID | user_id | INDEX (FK) | |
| IDX_MEMBER_STORES_STORE_ID | store_id | INDEX (FK) | |

**FK**

| 컬럼 | 참조 테이블 | 참조 컬럼 |
|------|-------------|-----------|
| user_id | users | id |
| store_id | stores | id |

---

## users

일반 회원. 소셜 로그인 전용. 소프트 딜리트 적용.

| 컬럼 | 타입 | NULL | 기본값 | 설명 |
|------|------|------|--------|------|
| id | bigint | NO | — | PK |
| name | varchar(50) | NO | — | 회원명 |
| email | varchar(255) | NO | — | 이메일 (UNIQUE) |
| phone | varchar(20) | NO | — | 전화번호 |
| is_active | boolean | NO | — | 계정 활성 여부 |
| created_at | datetime | NO | — | 생성일시 |
| updated_at | datetime | NO | — | 수정일시 |
| deleted_at | datetime | YES | NULL | 삭제일시 (NULL = 활성) |

**인덱스**

| 이름 | 대상 컬럼 | 종류 |
|------|-----------|------|
| PK_USERS | id | PRIMARY |
| UQ_USERS_EMAIL | email | UNIQUE |

---

## user_social

소셜 로그인 연결 정보. 회원 1명이 복수의 소셜 계정을 연결할 수 있음.

| 컬럼 | 타입 | NULL | 기본값 | 설명 |
|------|------|------|--------|------|
| id | bigint | NO | — | PK |
| user_id | bigint | NO | — | FK → users.id |
| provider | varchar(50) | NO | — | 소셜 제공자 (KAKAO, GOOGLE, NAVER) |
| provider_id | varchar(50) | NO | — | 소셜 제공자의 사용자 식별자 |
| created_at | datetime | NO | — | 최초 연결일시 |

**인덱스**

| 이름 | 대상 컬럼 | 종류 |
|------|-----------|------|
| PK_USER_SOCIAL | id | PRIMARY |
| UQ_USER_SOCIAL_USER_PROVIDER | (user_id, provider) | UNIQUE |
| IDX_USER_SOCIAL_USER_ID | user_id | INDEX (FK) |

**FK**

| 컬럼 | 참조 테이블 | 참조 컬럼 |
|------|-------------|-----------|
| user_id | users | id |

---

## products

공구 물품. `version` 컬럼으로 JPA 낙관적 락 적용 (선착순 재고 차감).

| 컬럼 | 타입 | NULL | 기본값 | 설명 |
|------|------|------|--------|------|
| id | bigint | NO | — | PK |
| store_id | bigint | NO | — | FK → stores.id |
| name | varchar(255) | NO | — | 물품명 |
| description | text | NO | — | 물품 설명 |
| price | bigint | NO | — | 판매 단가 (원) |
| total_stock | int | NO | — | 총 수량 |
| remaining_stock | int | NO | — | 잔여 재고 (낙관적 락 대상) |
| status | varchar(20) | NO | — | 상태 (UPCOMING / ACTIVE / SOLD_OUT / CLOSED) |
| start_at | datetime | NO | — | 판매 시작일시 |
| end_at | datetime | NO | — | 판매 종료일시 |
| version | bigint | NO | — | 낙관적 락용 버전 (`@Version`) |
| created_at | datetime | NO | — | 생성일시 |
| updated_at | datetime | NO | — | 수정일시 |

**status 값 정의**

| 값 | 설명 |
|----|------|
| UPCOMING | 판매 전 (start_at 미도래) |
| ACTIVE | 판매 중 |
| SOLD_OUT | 재고 소진 |
| CLOSED | 판매 종료 |

**인덱스**

| 이름 | 대상 컬럼 | 종류 | 비고 |
|------|-----------|------|------|
| PK_PRODUCTS | id | PRIMARY | |
| IDX_PRODUCTS_STORE_ID | store_id | INDEX (FK) | |
| IDX_PRODUCTS_STORE_STATUS | (store_id, status) | INDEX | 매장별 상태 필터 조회 |
| IDX_PRODUCTS_START_END | (start_at, end_at) | INDEX | 판매 기간 조회 |

**FK**

| 컬럼 | 참조 테이블 | 참조 컬럼 |
|------|-------------|-----------|
| store_id | stores | id |

---

## orders

주문. 취소 관련 컬럼(`cancelled_at`, `cancel_reason`)은 취소 시에만 값이 채워짐.

| 컬럼 | 타입 | NULL | 기본값 | 설명 |
|------|------|------|--------|------|
| id | bigint | NO | — | PK |
| user_id | bigint | NO | — | FK → users.id |
| status | varchar(20) | NO | — | 상태 (RESERVED / PAID / ARRIVED / RECEIVED / CANCELLED) |
| total_price | bigint | NO | — | 총 주문금액 (원) |
| cancelled_at | datetime | YES | NULL | 취소일시 |
| cancel_reason | varchar(255) | YES | NULL | 취소 사유 |
| created_at | datetime | NO | — | 생성일시 (= 예약일시) |
| updated_at | datetime | NO | — | 수정일시 |

**status 값 정의 및 전이**

```
RESERVED → PAID → ARRIVED → RECEIVED
    ↓         ↓
CANCELLED  CANCELLED
```

| 값 | 설명 |
|----|------|
| RESERVED | 예약 완료 (결제 전) |
| PAID | 결제 완료 |
| ARRIVED | 물품 입고 완료 |
| RECEIVED | 수령 완료 |
| CANCELLED | 취소 (RESERVED·PAID 상태에서만 가능) |

**인덱스**

| 이름 | 대상 컬럼 | 종류 | 비고 |
|------|-----------|------|------|
| PK_ORDERS | id | PRIMARY | |
| IDX_ORDERS_USER_ID | user_id | INDEX (FK) | |
| IDX_ORDERS_USER_STATUS | (user_id, status) | INDEX | 내 주문 상태 필터 조회 |

**FK**

| 컬럼 | 참조 테이블 | 참조 컬럼 |
|------|-------------|-----------|
| user_id | users | id |

---

## order_items

주문 물품 상세. `unit_price`는 주문 시점의 가격 스냅샷.

| 컬럼 | 타입 | NULL | 기본값 | 설명 |
|------|------|------|--------|------|
| id | bigint | NO | — | PK |
| product_id | bigint | NO | — | FK → products.id |
| order_id | bigint | NO | — | FK → orders.id |
| quantity | bigint | NO | — | 주문 수량 |
| unit_price | bigint | NO | — | 주문 시점 단가 스냅샷 (원) |
| created_at | datetime | NO | — | 생성일시 |

**인덱스**

| 이름 | 대상 컬럼 | 종류 |
|------|-----------|------|
| PK_ORDER_ITEMS | id | PRIMARY |
| IDX_ORDER_ITEMS_ORDER_ID | order_id | INDEX (FK) |
| IDX_ORDER_ITEMS_PRODUCT_ID | product_id | INDEX (FK) |

**FK**

| 컬럼 | 참조 테이블 | 참조 컬럼 |
|------|-------------|-----------|
| order_id | orders | id |
| product_id | products | id |

---

## payments

결제 정보. PortOne(아임포트) PG 연동. `paid_at` / `cancelled_at`은 각 시점에만 채워짐.

| 컬럼 | 타입 | NULL | 기본값 | 설명 |
|------|------|------|--------|------|
| id | bigint | NO | — | PK |
| order_id | bigint | NO | — | FK → orders.id |
| idempotency_key | varchar(255) | NO | — | 멱등키 (중복 결제 방지, UNIQUE) |
| imp_uid | varchar(50) | NO | — | PG사(PortOne) 결제 고유번호 (UNIQUE) |
| merchant_uid | varchar(255) | NO | — | 서버 주문번호 (UNIQUE) |
| amount | bigint | NO | — | 결제금액 (원) |
| status | varchar(20) | NO | — | 상태 (PENDING / PAID / CANCELLED / FAILED) |
| paid_at | datetime | YES | NULL | 결제 완료일시 |
| cancelled_at | datetime | YES | NULL | 결제 취소일시 |
| created_at | datetime | NO | — | 생성일시 |
| updated_at | datetime | NO | — | 수정일시 |

**status 값 정의**

| 값 | 설명 |
|----|------|
| PENDING | 결제 대기 |
| PAID | 결제 완료 |
| CANCELLED | 결제 취소 |
| FAILED | 결제 실패 |

**인덱스**

| 이름 | 대상 컬럼 | 종류 | 비고 |
|------|-----------|------|------|
| PK_PAYMENTS | id | PRIMARY | |
| UQ_PAYMENTS_IDEMPOTENCY_KEY | idempotency_key | UNIQUE | 중복 결제 방지 |
| UQ_PAYMENTS_IMP_UID | imp_uid | UNIQUE | PG사 결제번호 중복 불가 |
| UQ_PAYMENTS_MERCHANT_UID | merchant_uid | UNIQUE | 서버 주문번호 중복 불가 |
| IDX_PAYMENTS_ORDER_ID | order_id | INDEX (FK) | |
| IDX_PAYMENTS_STATUS | status | INDEX | 정산 배치·상태별 조회 |

**FK**

| 컬럼 | 참조 테이블 | 참조 컬럼 |
|------|-------------|-----------|
| order_id | orders | id |

---

## 변경 이력

| 날짜 | 내용 |
|------|------|
| 2026-04-19 | 최초 작성 |
| 2026-04-25 | member_stores 테이블 추가 (#36) |
