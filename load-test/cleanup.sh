#!/usr/bin/env bash
# k6 테스트로 생성된 데이터 삭제
# 사용: ./load-test/cleanup.sh
# 전제: .env 파일이 프로젝트 루트에 있거나, 환경 변수가 이미 설정되어 있어야 함

set -e

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT_DIR="$(dirname "$SCRIPT_DIR")"

# .env 파일이 있으면 로드
if [ -f "$ROOT_DIR/.env" ]; then
  export $(grep -v '^#' "$ROOT_DIR/.env" | xargs)
fi

DB_ROOT_PW="${MYSQL_PASSWORD:?MYSQL_PASSWORD 환경변수가 필요합니다}"
DB_NAME="${MYSQL_DATABASE:?MYSQL_DATABASE 환경변수가 필요합니다}"
CONTAINER="${MYSQL_CONTAINER:-gongu-mysql}"

echo "🧹 k6 테스트 데이터 삭제 시작 (DB: $DB_NAME, container: $CONTAINER)"

docker exec -i "$CONTAINER" mysql -uroot -p"$DB_ROOT_PW" --default-character-set=utf8mb4 "$DB_NAME" <<'SQL'
-- order_id를 임시 테이블에 먼저 저장 (이후 링크가 끊겨도 삭제 가능)
CREATE TEMPORARY TABLE tmp_k6_order_ids AS
  SELECT DISTINCT o.id AS order_id
  FROM orders o
  INNER JOIN order_items oi ON oi.order_id = o.id
  INNER JOIN products pr    ON oi.product_id = pr.id
  WHERE pr.name = 'k6 테스트 상품';

-- Step 1: payments 삭제 (orders FK)
DELETE FROM payments WHERE order_id IN (SELECT order_id FROM tmp_k6_order_ids);

-- Step 2: order_items 삭제 (orders FK)
DELETE FROM order_items WHERE order_id IN (SELECT order_id FROM tmp_k6_order_ids);

-- Step 3: orders 삭제
DELETE FROM orders WHERE id IN (SELECT order_id FROM tmp_k6_order_ids);

-- Step 4: products 삭제
DELETE FROM products WHERE name = 'k6 테스트 상품';

DROP TEMPORARY TABLE tmp_k6_order_ids;
SQL

echo "✅ 완료"
