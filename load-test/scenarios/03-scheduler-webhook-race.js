import http from 'k6/http';
import { check, sleep } from 'k6';
import { createOrder, preparePayment, mockCompletePayment, verifyPayment } from '../lib/client.js';

const BASE_URL = __ENV.BASE_URL || 'http://localhost:8080';

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

// 서버 타임존(Asia/Seoul, UTC+9) 기준 로컬 시각 문자열 반환
function toKSTLocal(date) {
  const kst = new Date(date.getTime() + 9 * 60 * 60 * 1000);
  return kst.toISOString().replace('Z', '');
}

export function setup() {
  // 1. 로그인
  const loginRes = http.post(
    `${BASE_URL}/auth/store-admin/login`,
    JSON.stringify({ email: __ENV.ADMIN_EMAIL || 'admin@test.com', password: __ENV.ADMIN_PASSWORD || 'password' }),
    { headers: { 'Content-Type': 'application/json' } },
  );
  check(loginRes, { 'admin login 200': (r) => r.status === 200 });
  const adminToken = loginRes.json('data.accessToken');

  // 2. 테스트 상품 생성 (KST 기준 시각)
  const now = new Date();
  const tomorrow = new Date(now.getTime() + 24 * 60 * 60 * 1000);
  const startAt = toKSTLocal(now);
  const endAt = toKSTLocal(tomorrow);

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
  const orderId = orderRes.json('data.orderId');

  // 6. 결제 준비
  const prepareRes = preparePayment(token, orderId);
  check(prepareRes, { 'payment prepared': (r) => r.status === 200 || r.status === 201 });
  const paymentId = prepareRes.json('data.paymentId');

  // 7. Mock PG에 결제 완료 상태 등록 (webhook 미발송)
  //    runVerify가 PG 조회 시 PAID 응답을 받을 수 있도록 등록만 수행.
  //    실제 webhook은 runWebhook이 TTL 만료 후 발송하여 race condition을 재현.
  const mockRes = mockCompletePayment(paymentId, amount, 0, 0);
  check(mockRes, { 'mock PG registered': (r) => r.status === 200 });

  // 8. TTL 2분 + 10초 여유 대기 — 스케줄러가 만료 처리하도록
  // 주의: k6 setup phase에서 실행되므로 테스트 시작이 130초 지연됨. 의도된 동작.
  sleep(130);

  return { token, orderId, paymentId, amount };
}

// verify와 webhook이 TTL 만료 후 동시에 도달할 때 멱등성 확인
export function runVerify(data) {
  const res = verifyPayment(data.token, data.orderId, data.paymentId);
  check(res, {
    'verify: 200 or 4xx (no 5xx)': (r) => r.status === 200 || (r.status >= 400 && r.status < 500),
  });
}

export function runWebhook(data) {
  // mock PG를 통해 webhook을 서버에 즉시 발송
  // server.js 조건이 `if (webhookDelayMs > 0)` 이므로 0이면 webhook 미발송 → 최솟값 1ms 설정
  const res = mockCompletePayment(data.paymentId, data.amount, 0, 1);
  check(res, { 'webhook trigger: 200': (r) => r.status === 200 });
}
