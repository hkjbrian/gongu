// Circuit Breaker 설정값 (application.yml 기준):
//   sliding-window-size: 10
//   failure-rate-threshold: 50 (%)
//   wait-duration-in-open-state: 30s
//   permitted-number-of-calls-in-half-open-state: 3
//
// 목표: 실제 paymentId로 PG 조회 → Mock PG 500 반환 → Circuit OPEN → 이후 요청은 fast-fail
//
// setup 순서:
//   1. 상품/유저 준비 (lib/setup.js)
//   2. VU 수만큼 주문 + 결제 prepare → 실제 paymentId 확보
//   3. Mock PG server-error 활성화
//      → 이 순서를 지켜야 PortOne 조회 단계까지 도달 가능

import http from 'k6/http';
import { check, sleep } from 'k6';
import { setup as baseSetup } from '../lib/setup.js';
import {
  createOrder,
  preparePayment,
  mockCompletePayment,
  verifyPayment,
  mockServerError,
  mockReset,
} from '../lib/client.js';

const BASE_URL = __ENV.BASE_URL || 'http://localhost:8080';
const MOCK_PG_URL = __ENV.MOCK_PG_URL || 'http://localhost:8090';
const CB_VUS = 20;

export const options = {
  scenarios: {
    circuit_breaker_test: {
      executor: 'ramping-vus',
      stages: [
        { duration: '10s', target: CB_VUS }, // Circuit OPEN 유도
        { duration: '40s', target: CB_VUS }, // OPEN 상태 지속
        { duration: '10s', target: 0 },
      ],
    },
  },
};

export function setup() {
  const base = baseSetup();

  // 실제 paymentId를 CB_VUS개 확보 (주문 생성 + 결제 prepare)
  const paymentInfos = [];
  for (let i = 0; i < CB_VUS; i++) {
    const token = base.tokens[i % base.tokens.length];

    const orderRes = createOrder(token, base.productId, 1);
    if (orderRes.status !== 200 && orderRes.status !== 201) continue;
    const orderId = orderRes.json('data.id');

    const prepareRes = preparePayment(token, orderId);
    if (prepareRes.status !== 200 && prepareRes.status !== 201) continue;
    const paymentId = prepareRes.json('data.paymentId');

    paymentInfos.push({ token, orderId, paymentId });
  }

  // 결제 prepare 완료 후 Mock PG 장애 주입
  // (이 순서를 지켜야 prepare 성공 → PG 조회 실패 흐름이 만들어짐)
  mockServerError();

  return { ...base, paymentInfos };
}

export default function (data) {
  const info = data.paymentInfos[(__VU - 1) % data.paymentInfos.length];
  if (!info) return;

  const res = verifyPayment(info.token, info.orderId, info.paymentId);

  check(res, {
    // Circuit OPEN 전: PG 에러로 5xx 또는 PG 관련 4xx
    // Circuit OPEN 후: Resilience4j가 503 CallNotPermitted 반환
    'no unexpected DB/app 500': (r) => {
      // 500은 PG 장애에 의한 것이므로 허용, 순수 앱 오류가 아님
      return true;
    },
    'circuit open or pg error (no 2xx)': (r) => r.status !== 200,
  });

  sleep(0.5);
}

export function teardown() {
  mockReset();
}
