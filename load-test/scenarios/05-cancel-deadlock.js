// 목표: 동일 상품 포함 주문 동시 취소 시 데드락 없음 + 재고 완전 복원 증명
// 30개 VU가 각자의 주문을 동시에 취소 → DB row-level lock 경합 시 deadlock 발생 여부 및
// 전체 취소 완료 후 remainingStock이 totalStock으로 정확히 복원되었는지 검증

import http from 'k6/http';
import { check } from 'k6';
import { createOrder, cancelOrder } from '../lib/client.js';
import { setup as libSetup } from '../lib/setup.js';

const BASE_URL = __ENV.BASE_URL || 'http://localhost:8080';

export const options = {
  scenarios: {
    cancel_deadlock: {
      executor: 'shared-iterations',
      vus: 30,
      iterations: 30,
      maxDuration: '60s',
    },
  },
  thresholds: {
    http_req_failed: ['rate<0.01'], // 네트워크/5xx 에러 없음
  },
};

const TOTAL_STOCK = 50;
const ORDER_COUNT = 30;

export function setup() {
  const setupData = libSetup({ totalStock: TOTAL_STOCK });

  // ORDER_COUNT개 VU 각각에 대해 주문 생성
  const orderIds = [];
  for (let i = 0; i < ORDER_COUNT; i++) {
    const token = setupData.tokens[i];
    const orderRes = createOrder(token, setupData.productId, 1);
    check(orderRes, { [`setup order created userId=${i + 1}`]: (r) => r.status === 200 || r.status === 201 });
    const orderId = orderRes.json('data.orderId');
    orderIds.push(orderId);
  }

  return { ...setupData, orderIds };
}

export default function (data) {
  const token = data.tokens[__VU - 1];
  const orderId = data.orderIds[__VU - 1];

  const res = cancelOrder(token, orderId, '테스트 취소');

  check(res, {
    'cancel: no deadlock (no 5xx)': (r) => r.status < 500,
    'cancel: success or already cancelled': (r) => r.status === 204 || r.status === 409,
  });
}

export function teardown(data) {
  const res = http.get(
    `${BASE_URL}/admin/products/${data.productId}`,
    { headers: { Authorization: `Bearer ${data.adminToken}` } },
  );

  const remainingStock = res.json('data.remainingStock');
  const expectedStock = TOTAL_STOCK; // 주문 30건 취소 → 재고 전량 복원

  check(res, {
    'teardown: product stock query 200': (r) => r.status === 200,
    [`teardown: stock fully restored (expected=${expectedStock}, actual=${remainingStock})`]:
      () => remainingStock === expectedStock,
  });
}
