import { check } from 'k6';
import { Counter } from 'k6/metrics';
import { createOrder } from '../lib/client.js';

export { setup } from '../lib/setup.js';

const orderSuccessCount = new Counter('order_success_count');

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
    // totalStock=10이므로 정확히 10건만 성공해야 초과 판매 없음 검증
    order_success_count: ['count==10'],
  },
};

export default function (data) {
  const token = data.tokens[__VU - 1];
  const productId = data.productId;

  const res = createOrder(token, productId, 1);

  if (res.status === 200 || res.status === 201) {
    orderSuccessCount.add(1);
    check(res, { 'order succeeded': (r) => r.status === 200 || r.status === 201 });
  } else {
    check(res, { 'order rejected (stock exhausted)': (r) => r.status >= 400 && r.status < 500 });
  }
}
