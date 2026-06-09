// Circuit Breaker 설정값 (application.yml 기준):
//   sliding-window-size: 10
//   failure-rate-threshold: 50 (%)
//   wait-duration-in-open-state: 30s
//   permitted-number-of-calls-in-half-open-state: 3
//
// 목표:
//   Phase 1 (0~50s): PG 500 반복 → Circuit OPEN → 이후 요청은 503 fast-fail
//   Phase 2 (42s~): mockReset 후 HALF_OPEN에서 정상 결제 성공 → Circuit CLOSE 확인
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
    // Phase 1: PG 500 반복 → Circuit OPEN 유도
    circuit_breaker_test: {
      executor: 'ramping-vus',
      stages: [
        { duration: '10s', target: CB_VUS }, // Circuit OPEN 유도
        { duration: '30s', target: CB_VUS }, // OPEN 상태 지속 (wait-duration: 30s)
        { duration: '10s', target: 0 },
      ],
    },
    // Phase 2 준비: wait-duration(30s) 경과 후 Mock PG 정상화 → HALF_OPEN 진입
    reset_mock_pg: {
      executor: 'shared-iterations',
      vus: 1,
      iterations: 1,
      startTime: '40s',
      exec: 'resetMockPG',
    },
    // Phase 2: HALF_OPEN 상태에서 정상 결제 성공 → Circuit CLOSE 확인
    circuit_recovery: {
      executor: 'shared-iterations',
      vus: 3,
      iterations: 3,
      startTime: '42s', // reset 후 2초 여유
      exec: 'runRecovery',
    },
  },
};

export function setup() {
  const base = baseSetup({ totalStock: CB_VUS });

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

  // setup 검증 — 준비된 결제건이 CB_VUS보다 적으면 테스트가 왜곡됨
  if (paymentInfos.length !== CB_VUS) {
    throw new Error(`setup failed: expected ${CB_VUS} paymentInfos, got ${paymentInfos.length}`);
  }

  // 결제 prepare 완료 후 Mock PG 장애 주입
  // (이 순서를 지켜야 prepare 성공 → PG 조회 실패 흐름이 만들어짐)
  mockServerError();

  return { ...base, paymentInfos };
}

// Phase 1: Circuit OPEN 유도 — PG 에러(500) 반복 → Resilience4j 503 fast-fail 확인
export default function (data) {
  const info = data.paymentInfos[(__VU - 1) % data.paymentInfos.length];
  if (!info) return;

  const res = verifyPayment(info.token, info.orderId, info.paymentId);

  check(res, {
    // Circuit OPEN 전: PG 에러로 500, OPEN 후: Resilience4j CallNotPermitted → 503
    'expected pg error or circuit open (500/503)': (r) => r.status === 500 || r.status === 503,
    'circuit open or pg error (no 2xx)': (r) => r.status !== 200,
  });

  sleep(0.5);
}

// Phase 2 준비: Mock PG 정상화
export function resetMockPG() {
  mockReset();
}

// Phase 2: HALF_OPEN 상태에서 정상 결제 성공 → Circuit CLOSE 검증
export function runRecovery(data) {
  const info = data.paymentInfos[(__VU - 1) % data.paymentInfos.length];
  if (!info) return;

  // reset 후 Mock PG에 결제 완료 상태 재등록
  mockCompletePayment(info.paymentId, 10000, 0, 0);

  const res = verifyPayment(info.token, info.orderId, info.paymentId);
  check(res, {
    'circuit recovery: 200 OK (HALF_OPEN → CLOSED)': (r) => r.status === 200,
  });
}

export function teardown() {
  mockReset();
}
