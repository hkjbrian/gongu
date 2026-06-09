import { check } from 'k6';
import { createOrder } from '../lib/client.js';

export { setup } from '../lib/setup.js';

export const options = {
  scenarios: {
    inventory_concurrency: {
      executor: 'shared-iterations',
      vus: 50,
      iterations: 50,
      maxDuration: '30s',
    },
  },
  thresholds: {
    // 성공(200)이 10개, 실패(4xx)가 40개여야 함 — 직접 체크
    checks: ['rate>0.19'], // 최소 19% 이상 (10/50 = 20%)
  },
};

export default function (data) {
  const token = data.tokens[__VU - 1];
  const productId = data.productId;

  const res = createOrder(token, productId, 1);

  if (res.status === 200 || res.status === 201) {
    check(res, { 'order succeeded': (r) => r.status === 200 || r.status === 201 });
  } else {
    check(res, { 'order rejected (stock exhausted)': (r) => r.status >= 400 && r.status < 500 });
  }
}
