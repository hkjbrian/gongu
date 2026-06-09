import http from 'k6/http';

const BASE_URL = __ENV.BASE_URL || 'http://localhost:8080';
const MOCK_PG_URL = __ENV.MOCK_PG_URL || 'http://localhost:8090';

function authHeaders(token) {
  return { headers: { 'Content-Type': 'application/json', Authorization: `Bearer ${token}` } };
}

function jsonHeaders() {
  return { headers: { 'Content-Type': 'application/json' } };
}

// 주문 생성: POST /orders
// body: { productId, quantity }
export function createOrder(token, productId, quantity) {
  return http.post(
    `${BASE_URL}/orders`,
    JSON.stringify({ productId, quantity }),
    authHeaders(token),
  );
}

// 주문 취소: POST /orders/{orderId}/cancel
// body: { reason }
export function cancelOrder(token, orderId, reason) {
  return http.post(
    `${BASE_URL}/orders/${orderId}/cancel`,
    JSON.stringify({ reason }),
    authHeaders(token),
  );
}

// 결제 준비: POST /payments/prepare
// body: { order_id }
export function preparePayment(token, orderId) {
  return http.post(
    `${BASE_URL}/payments/prepare`,
    JSON.stringify({ order_id: orderId }),
    authHeaders(token),
  );
}

// 결제 검증: POST /payments/verify
// body: { order_id, payment_id }
// 호출자가 token과 orderId를 함께 전달해야 함 (서버가 ownership 검증)
export function verifyPayment(token, orderId, paymentId) {
  return http.post(
    `${BASE_URL}/payments/verify`,
    JSON.stringify({ order_id: orderId, payment_id: paymentId }),
    authHeaders(token),
  );
}

// Mock PG: 결제 완료 등록
// POST /control/payments/:id/complete
// body: { amount, delayMs, webhookDelayMs }
export function mockCompletePayment(paymentId, amount, delayMs, webhookDelayMs) {
  return http.post(
    `${MOCK_PG_URL}/control/payments/${paymentId}/complete`,
    JSON.stringify({ amount, delayMs: delayMs || 0, webhookDelayMs: webhookDelayMs || 0 }),
    jsonHeaders(),
  );
}

// Mock PG: 결제 실패 등록
// POST /control/payments/:id/fail
export function mockFailPayment(paymentId) {
  return http.post(
    `${MOCK_PG_URL}/control/payments/${paymentId}/fail`,
    null,
    jsonHeaders(),
  );
}

// Mock PG: 서버 에러 모드 활성화
// POST /control/server-error
export function mockServerError() {
  return http.post(`${MOCK_PG_URL}/control/server-error`, null, jsonHeaders());
}

// Mock PG: 상태 초기화
// POST /control/reset
export function mockReset() {
  return http.post(`${MOCK_PG_URL}/control/reset`, null, jsonHeaders());
}
