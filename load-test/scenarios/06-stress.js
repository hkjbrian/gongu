// 목표: 커넥션 풀 고갈 임계점 탐색 (관찰용)
// thresholds 없음 — Grafana에서 hikaricp_connections_active 모니터링
// VU가 200 초과 시 토큰을 순환 사용 (data.tokens.length = 200)

import http from 'k6/http';
import { check, sleep } from 'k6';
import {
  createOrder,
  preparePayment,
  mockCompletePayment,
  verifyPayment,
} from '../lib/client.js';

import { setup as libSetup } from '../lib/setup.js';

// 커넥션 풀 고갈 탐색용 — 충분한 재고로 전체 결제 흐름이 지속 실행되도록
export function setup() {
  return libSetup({ totalStock: 10000 });
}

export const options = {
  scenarios: {
    stress_test: {
      executor: 'ramping-vus',
      stages: [
        { duration: '3m', target: 10 },
        { duration: '3m', target: 50 },
        { duration: '3m', target: 100 },
        { duration: '3m', target: 200 },
        { duration: '1m', target: 0 },
      ],
    },
  },
  // thresholds 없음 — 관찰용, Grafana에서 hikaricp_connections_active 모니터링
};

export default function (data) {
  // VU가 200 초과 시 토큰 순환
  const token = data.tokens[(__VU - 1) % data.tokens.length];
  const productId = data.productId;

  // 1. 주문 생성
  const orderRes = createOrder(token, productId, 1);
  const orderOk = check(orderRes, {
    'order created': (r) => r.status === 200 || r.status === 201,
  });
  if (!orderOk) {
    sleep(1);
    return;
  }
  const orderId = orderRes.json('data.orderId');

  // 2. 결제 준비
  const prepareRes = preparePayment(token, orderId);
  const prepareOk = check(prepareRes, {
    'payment prepared': (r) => r.status === 200 || r.status === 201,
  });
  if (!prepareOk) {
    sleep(1);
    return;
  }
  const paymentId = prepareRes.json('data.paymentId');
  const amount = 10000;

  // 3. Mock PG 결제 완료 등록
  const mockRes = mockCompletePayment(paymentId, amount, 0, 0);
  const mockOk = check(mockRes, {
    'mock payment complete': (r) => r.status === 200,
  });
  if (!mockOk) {
    sleep(1);
    return;
  }

  // 4. 결제 검증
  const verifyRes = verifyPayment(token, orderId, paymentId);
  check(verifyRes, {
    'payment verified': (r) => r.status === 200,
  });

  sleep(1);
}
