-- =============================================================
-- Gongu Database DDL
-- =============================================================

-- -------------------------------------------------------------
-- Tables
-- -------------------------------------------------------------

CREATE TABLE `stores` (
    `id`         bigint       NOT NULL,
    `name`       varchar(255) NOT NULL,
    `address`    varchar(255) NOT NULL,
    `phone`      varchar(20)  NOT NULL,
    `is_active`  boolean      NOT NULL,
    `created_at` datetime     NOT NULL,
    `updated_at` datetime     NOT NULL,
    `deleted_at` datetime     NULL
);

CREATE TABLE `store_admins` (
    `id`         bigint       NOT NULL,
    `store_id`   bigint       NOT NULL,
    `email`      varchar(255) NOT NULL,
    `password`   varchar(100) NOT NULL,
    `name`       varchar(50)  NOT NULL,
    `is_active`  boolean      NOT NULL,
    `created_at` datetime     NOT NULL,
    `updated_at` datetime     NOT NULL,
    `deleted_at` datetime     NULL
);

CREATE TABLE `users` (
    `id`         bigint       NOT NULL,
    `name`       varchar(50)  NOT NULL,
    `email`      varchar(255) NULL,
    `phone`      varchar(20)  NOT NULL,
    `is_active`  boolean      NOT NULL,
    `created_at` datetime     NOT NULL,
    `updated_at` datetime     NOT NULL,
    `deleted_at` datetime     NULL
);

CREATE TABLE `user_social` (
    `id`          bigint      NOT NULL,
    `user_id`     bigint      NOT NULL,
    `provider`    varchar(50) NOT NULL,
    `provider_id` varchar(50) NOT NULL,
    `created_at`  datetime    NOT NULL
);

CREATE TABLE `products` (
    `id`              bigint       NOT NULL,
    `store_id`        bigint       NOT NULL,
    `name`            varchar(255) NOT NULL,
    `description`     text         NOT NULL,
    `price`           bigint       NOT NULL,
    `total_stock`     int          NOT NULL,
    `remaining_stock` int          NOT NULL,
    `status`          varchar(20)  NOT NULL,
    `start_at`        datetime     NOT NULL,
    `end_at`          datetime     NOT NULL,
    `version`         bigint       NOT NULL,
    `created_at`      datetime     NOT NULL,
    `updated_at`      datetime     NOT NULL
);

CREATE TABLE `orders` (
    `id`            bigint       NOT NULL,
    `user_id`       bigint       NOT NULL,
    `status`        varchar(20)  NOT NULL,
    `total_price`   bigint       NOT NULL,
    `cancelled_at`  datetime     NULL,
    `cancel_reason` varchar(255) NULL,
    `created_at`    datetime     NOT NULL,
    `updated_at`    datetime     NOT NULL
);

CREATE TABLE `order_items` (
    `id`         bigint   NOT NULL,
    `product_id` bigint   NOT NULL,
    `order_id`   bigint   NOT NULL,
    `quantity`   bigint   NOT NULL,
    `unit_price` bigint   NOT NULL,
    `created_at` datetime NOT NULL
);

CREATE TABLE `member_stores` (
    `id`           bigint   NOT NULL,
    `user_id`      bigint   NOT NULL,
    `store_id`     bigint   NOT NULL,
    `is_preferred` boolean  NOT NULL,
    `created_at`   datetime NOT NULL
);

CREATE TABLE `payments` (
    `id`               bigint       NOT NULL,
    `order_id`         bigint       NOT NULL,
    `idempotency_key`  varchar(255) NOT NULL,
    `imp_uid`          varchar(50)  NOT NULL,
    `merchant_uid`     varchar(255) NOT NULL,
    `amount`           bigint       NOT NULL,
    `status`           varchar(20)  NOT NULL,
    `paid_at`          datetime     NULL,
    `cancelled_at`     datetime     NULL,
    `created_at`       datetime     NOT NULL,
    `updated_at`       datetime     NOT NULL
);

-- -------------------------------------------------------------
-- Primary Keys
-- -------------------------------------------------------------

ALTER TABLE `stores`       ADD CONSTRAINT `PK_STORES`       PRIMARY KEY (`id`);
ALTER TABLE `store_admins` ADD CONSTRAINT `PK_STORE_ADMINS` PRIMARY KEY (`id`);
ALTER TABLE `users`        ADD CONSTRAINT `PK_USERS`        PRIMARY KEY (`id`);
ALTER TABLE `user_social`  ADD CONSTRAINT `PK_USER_SOCIAL`  PRIMARY KEY (`id`);
ALTER TABLE `products`     ADD CONSTRAINT `PK_PRODUCTS`     PRIMARY KEY (`id`);
ALTER TABLE `orders`       ADD CONSTRAINT `PK_ORDERS`       PRIMARY KEY (`id`);
ALTER TABLE `order_items`  ADD CONSTRAINT `PK_ORDER_ITEMS`  PRIMARY KEY (`id`);
ALTER TABLE `member_stores` ADD CONSTRAINT `PK_MEMBER_STORES` PRIMARY KEY (`id`);
ALTER TABLE `payments`     ADD CONSTRAINT `PK_PAYMENTS`     PRIMARY KEY (`id`);

-- -------------------------------------------------------------
-- Foreign Key Constraints
-- -------------------------------------------------------------

ALTER TABLE `store_admins`
    ADD CONSTRAINT `FK_STORE_ADMINS_STORE_ID`
    FOREIGN KEY (`store_id`) REFERENCES `stores` (`id`);

ALTER TABLE `member_stores`
    ADD CONSTRAINT `FK_MEMBER_STORES_USER_ID`
    FOREIGN KEY (`user_id`) REFERENCES `users` (`id`);

ALTER TABLE `member_stores`
    ADD CONSTRAINT `FK_MEMBER_STORES_STORE_ID`
    FOREIGN KEY (`store_id`) REFERENCES `stores` (`id`);

ALTER TABLE `user_social`
    ADD CONSTRAINT `FK_USER_SOCIAL_USER_ID`
    FOREIGN KEY (`user_id`) REFERENCES `users` (`id`);

ALTER TABLE `products`
    ADD CONSTRAINT `FK_PRODUCTS_STORE_ID`
    FOREIGN KEY (`store_id`) REFERENCES `stores` (`id`);

ALTER TABLE `orders`
    ADD CONSTRAINT `FK_ORDERS_USER_ID`
    FOREIGN KEY (`user_id`) REFERENCES `users` (`id`);

ALTER TABLE `order_items`
    ADD CONSTRAINT `FK_ORDER_ITEMS_ORDER_ID`
    FOREIGN KEY (`order_id`) REFERENCES `orders` (`id`);

ALTER TABLE `order_items`
    ADD CONSTRAINT `FK_ORDER_ITEMS_PRODUCT_ID`
    FOREIGN KEY (`product_id`) REFERENCES `products` (`id`);

ALTER TABLE `payments`
    ADD CONSTRAINT `FK_PAYMENTS_ORDER_ID`
    FOREIGN KEY (`order_id`) REFERENCES `orders` (`id`);

-- -------------------------------------------------------------
-- Unique Indexes
-- -------------------------------------------------------------

-- 이메일 중복 가입 방지
ALTER TABLE `store_admins` ADD CONSTRAINT `UQ_STORE_ADMINS_EMAIL` UNIQUE (`email`);

-- 같은 소셜 제공자로 중복 연결 방지
ALTER TABLE `user_social`
    ADD CONSTRAINT `UQ_USER_SOCIAL_USER_PROVIDER`
    UNIQUE (`user_id`, `provider`);

-- 동일 회원의 중복 매장 등록 방지
ALTER TABLE `member_stores`
    ADD CONSTRAINT `UQ_MEMBER_STORES_USER_STORE`
    UNIQUE (`user_id`, `store_id`);

-- 결제 멱등키 / PG사 결제번호 / 서버 주문번호 중복 방지
ALTER TABLE `payments` ADD CONSTRAINT `UQ_PAYMENTS_IDEMPOTENCY_KEY` UNIQUE (`idempotency_key`);
ALTER TABLE `payments` ADD CONSTRAINT `UQ_PAYMENTS_IMP_UID`         UNIQUE (`imp_uid`);
ALTER TABLE `payments` ADD CONSTRAINT `UQ_PAYMENTS_MERCHANT_UID`    UNIQUE (`merchant_uid`);

-- -------------------------------------------------------------
-- FK Indexes (FK 컬럼 탐색 성능)
-- -------------------------------------------------------------

CREATE INDEX `IDX_STORE_ADMINS_STORE_ID`   ON `store_admins` (`store_id`);
CREATE INDEX `IDX_MEMBER_STORES_USER_ID`   ON `member_stores` (`user_id`);
CREATE INDEX `IDX_MEMBER_STORES_STORE_ID`  ON `member_stores` (`store_id`);
CREATE INDEX `IDX_USER_SOCIAL_USER_ID`     ON `user_social`  (`user_id`);
CREATE INDEX `IDX_PRODUCTS_STORE_ID`       ON `products`     (`store_id`);
CREATE INDEX `IDX_ORDERS_USER_ID`          ON `orders`       (`user_id`);
CREATE INDEX `IDX_ORDER_ITEMS_ORDER_ID`    ON `order_items`  (`order_id`);
CREATE INDEX `IDX_ORDER_ITEMS_PRODUCT_ID`  ON `order_items`  (`product_id`);
CREATE INDEX `IDX_PAYMENTS_ORDER_ID`       ON `payments`     (`order_id`);

-- -------------------------------------------------------------
-- Filtering Indexes (조회 조건 성능)
-- -------------------------------------------------------------

-- 상품 목록: 매장별 + 상태 필터 (GET /products?store_id=&status=)
CREATE INDEX `IDX_PRODUCTS_STORE_STATUS`   ON `products` (`store_id`, `status`);

-- 상품 목록: 판매 기간 필터 (ACTIVE 상품 기간 조회)
CREATE INDEX `IDX_PRODUCTS_START_END`      ON `products` (`start_at`, `end_at`);

-- 주문 목록: 회원별 + 상태 필터 (GET /orders/me?status=)
CREATE INDEX `IDX_ORDERS_USER_STATUS`      ON `orders`   (`user_id`, `status`);

-- 결제 상태 필터 (정산 배치, 상태별 조회)
CREATE INDEX `IDX_PAYMENTS_STATUS`         ON `payments` (`status`);
