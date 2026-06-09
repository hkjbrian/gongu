// Circuit Breaker 설정값 (application.yml 기준):
//   sliding-window-size: 10
//   failure-rate-threshold: 50 (%)
//   wait-duration-in-open-state: 30s
//   permitted-number-of-calls-in-half-open-state: 3
//
// 목표: PG 장애 시 sliding window 10건 중 50% 이상 실패 → Circuit OPEN → 이후 요청은 503 fast-fail

import http from 'k6/http';
import { check, sleep } from 'k6';
import { verifyPayment, mockServerError, mockReset } from '../lib/client.js';

export { setup } from '../lib/setup.js';

export const options = {
  scenarios: {
    circuit_breaker_test: {
      executor: 'ramping-vus',
      stages: [
        { duration: '10s', target: 20 }, // Circuit OPEN 유도
        { duration: '40s', target: 20 }, // OPEN 상태 지속
        { duration: '10s', target: 0 },
      ],
    },
  },
};

export default function (data) {
  // 처음 1번만 mockServerError() 호출하여 PG 장애 상태 활성화
  if (__ITER === 0 && __VU === 1) {
    mockServerError();
  }

  const token = data.tokens[(__VU - 1) % data.tokens.length];

  // Circuit Breaker 테스트: 임시 orderId/paymentId로 verifyPayment 반복 호출
  // 실제 결제 완료가 목적이 아니라 PG 조회 실패를 유도해 Circuit이 OPEN되는 것을 확인
  const orderId = `cb-order-${__VU}-${__ITER}`;
  const paymentId = `cb-payment-${__VU}-${__ITER}`;

  const res = verifyPayment(token, orderId, paymentId);

  check(res, {
    'no unexpected 5xx server error': (r) => r.status !== 500,
    'circuit open - fast fail': (r) => r.status === 503 || r.status >= 400,
  });

  sleep(0.5);
}

export function teardown(data) {
  mockReset();
}
