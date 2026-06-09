// 목표: 동일 상품 포함 주문 동시 취소 시 데드락 없음 증명
// 30개 VU가 각자의 주문을 동시에 취소 → DB row-level lock 경합 시 deadlock 발생 여부 확인

import http from 'k6/http';
import { check } from 'k6';
import { createOrder, cancelOrder } from '../lib/client.js';
import { setup as libSetup } from '../lib/setup.js';

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

export function setup() {
  const setupData = libSetup();

  // 30개 VU 각각에 대해 주문 생성
  const orderIds = [];
  for (let i = 0; i < 30; i++) {
    const token = setupData.tokens[i];
    const orderRes = createOrder(token, setupData.productId, 1);
    check(orderRes, { [`setup order created userId=${i + 1}`]: (r) => r.status === 200 || r.status === 201 });
    const orderId = orderRes.json('data.id');
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
    'cancel: success or already cancelled': (r) => r.status === 200 || r.status === 400,
  });
}
