 // 목적: 순수 주문 생성 TPS 베이스라인 측정
// executor: constant-arrival-rate — 요청 도달률(req/s)을 직접 제어
// 결제 플로우 없음 — createOrder 단일 엔드포인트만 호출
// stock=1_000_000 으로 재고 소진 변수 제거
// 병목 포인트: ProductRepository.findByIdWithLock (비관적 락 직렬화)

import { check, sleep } from 'k6';
import { createOrder } from '../lib/client.js';

import { setup as libSetup } from '../lib/setup.js';
export { teardown } from '../lib/setup.js';

export function setup() {
  return libSetup({ totalStock: 1_000_000 });
}

export const options = {
  scenarios: {
    order_tps_550: {
      executor: 'constant-arrival-rate',
      rate: 550,
      timeUnit: '1s',
      duration: '2m',
      preAllocatedVUs: 600,
      maxVUs: 1200,
      startTime: '0s',
    },
    order_tps_600: {
      executor: 'constant-arrival-rate',
      rate: 600,
      timeUnit: '1s',
      duration: '2m',
      preAllocatedVUs: 650,
      maxVUs: 1300,
      startTime: '130s',
    },
    order_tps_650: {
      executor: 'constant-arrival-rate',
      rate: 650,
      timeUnit: '1s',
      duration: '2m',
      preAllocatedVUs: 700,
      maxVUs: 1400,
      startTime: '260s',
    },
    order_tps_700: {
      executor: 'constant-arrival-rate',
      rate: 700,
      timeUnit: '1s',
      duration: '2m',
      preAllocatedVUs: 750,
      maxVUs: 1500,
      startTime: '390s',
    },
  },
  thresholds: {
    http_req_failed: ['rate<0.05'],
    http_req_duration: ['p(95)<1000'],
  },
};

export default function (data) {
  const token = data.tokens[(__VU - 1) % data.tokens.length];
  const productId = data.productId;

  const res = createOrder(token, productId, 1);
  const ok = check(res, {
    'order created': (r) => r.status === 200 || r.status === 201,
  });
  if (!ok) {
    sleep(0.5);
    return;
  }
}
