import http from 'k6/http';
import { check, sleep } from 'k6';
import { createOrder, preparePayment, mockCompletePayment, verifyPayment } from '../lib/client.js';

const BASE_URL = __ENV.BASE_URL || 'http://localhost:8080';
const MOCK_PG_URL = __ENV.MOCK_PG_URL || 'http://localhost:8090';

export const options = {
  scenarios: {
    verify_call: {
      executor: 'shared-iterations',
      vus: 1,
      iterations: 1,
      startTime: '0s',
      exec: 'runVerify',
    },
    webhook_call: {
      executor: 'shared-iterations',
      vus: 1,
      iterations: 1,
      startTime: '0s',
      exec: 'runWebhook',
    },
  },
};

export function setup() {
  // 1. 로그인
  const loginRes = http.post(
    `${BASE_URL}/auth/store-admin/login`,
    JSON.stringify({ email: __ENV.ADMIN_EMAIL || 'admin@test.com', password: __ENV.ADMIN_PASSWORD || 'password' }),
    { headers: { 'Content-Type': 'application/json' } },
  );
  check(loginRes, { 'admin login 200': (r) => r.status === 200 });
  const adminToken = loginRes.json('data.accessToken');

  // 2. 테스트 상품 생성
  const now = new Date();
  const tomorrow = new Date(now.getTime() + 24 * 60 * 60 * 1000);
  const startAt = now.toISOString().replace('Z', '');
  const endAt = tomorrow.toISOString().replace('Z', '');

  const productRes = http.post(
    `${BASE_URL}/admin/products`,
    JSON.stringify({
      name: 'k6 스케줄러-웹훅 레이스 테스트 상품',
      description: '스케줄러-웹훅 레이스 컨디션 테스트용',
      price: 10000,
      totalStock: 10,
      startAt,
      endAt,
    }),
    { headers: { 'Content-Type': 'application/json', Authorization: `Bearer ${adminToken}` } },
  );
  check(productRes, { 'product created 201': (r) => r.status === 201 });
  const productId = productRes.json('data.id');
  const storeId = productRes.json('data.storeId');
  const amount = 10000;

  // 3. 유저 토큰 발급
  const tokenRes = http.post(
    `${BASE_URL}/auth/test-login`,
    JSON.stringify({ userId: 1 }),
    { headers: { 'Content-Type': 'application/json' } },
  );
  check(tokenRes, { 'test-login 200': (r) => r.status === 200 });
  const token = tokenRes.json('data.accessToken');

  // 4. 스토어 구독
  http.post(
    `${BASE_URL}/users/me/stores`,
    JSON.stringify({ storeId, isPreferred: false }),
    { headers: { 'Content-Type': 'application/json', Authorization: `Bearer ${token}` } },
  );

  // 5. 주문 생성
  const orderRes = createOrder(token, productId, 1);
  check(orderRes, { 'order created': (r) => r.status === 200 || r.status === 201 });
  const orderId = orderRes.json('data.id');

  // 6. 결제 준비
  const prepareRes = preparePayment(token, orderId);
  check(prepareRes, { 'payment prepared': (r) => r.status === 200 || r.status === 201 });
  const paymentId = prepareRes.json('data.paymentId');

  // 7. Mock PG에 결제 완료 등록 (즉시 처리, webhook도 즉시)
  mockCompletePayment(paymentId, amount, 0, 0);

  // 8. TTL 2분 + 10초 여유 대기 — 스케줄러가 만료 처리하도록
  // 주의: k6 setup phase에서 실행되므로 테스트 시작이 130초 지연됨. 의도된 동작.
  sleep(130);

  return { token, orderId, paymentId, amount };
}

// verify와 webhook이 동시에 도달할 때 멱등성 확인
export function runVerify(data) {
  const res = verifyPayment(data.token, data.orderId, data.paymentId);
  check(res, {
    'verify: 200 or 4xx (no 5xx)': (r) => r.status === 200 || (r.status >= 400 && r.status < 500),
  });
}

export function runWebhook(data) {
  const res = http.post(
    `${MOCK_PG_URL}/control/payments/${data.paymentId}/complete`,
    JSON.stringify({ amount: data.amount, delayMs: 0, webhookDelayMs: 0 }),
    { headers: { 'Content-Type': 'application/json' } },
  );
  check(res, { 'webhook trigger: 200': (r) => r.status === 200 });
}
