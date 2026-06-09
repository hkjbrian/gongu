import { check, sleep } from 'k6';
import { createOrder, preparePayment, mockCompletePayment, verifyPayment } from '../lib/client.js';

export { setup } from '../lib/setup.js';

export const options = {
  scenarios: {
    pg_latency: {
      executor: 'constant-vus',
      vus: 15,
      duration: '60s',
    },
  },
  // thresholds 없음 — 관찰용
};

export default function (data) {
  const token = data.tokens[__VU - 1];
  const productId = data.productId;
  const amount = 10000; // setup.js에서 price: 10000으로 생성

  // 1. 주문 생성
  const orderRes = createOrder(token, productId, 1);
  check(orderRes, { 'order created': (r) => r.status === 200 || r.status === 201 });
  if (orderRes.status !== 200 && orderRes.status !== 201) {
    return;
  }
  const orderId = orderRes.json('data.id');

  // 2. 결제 준비
  const prepareRes = preparePayment(token, orderId);
  check(prepareRes, { 'payment prepared': (r) => r.status === 200 || r.status === 201 });
  if (prepareRes.status !== 200 && prepareRes.status !== 201) {
    return;
  }
  const paymentId = prepareRes.json('data.paymentId');

  // 3. Mock PG에 결제 완료 등록 (3초 지연)
  const completeRes = mockCompletePayment(paymentId, amount, 3000, 0);
  check(completeRes, { 'mock pg complete registered': (r) => r.status === 200 || r.status === 201 });

  // 4. 결제 검증 (PG 3초 지연 중 DB 커넥션 홀딩 관찰)
  const verifyRes = verifyPayment(token, orderId, paymentId);
  check(verifyRes, { 'payment verified 200': (r) => r.status === 200 });

  sleep(1);
}
