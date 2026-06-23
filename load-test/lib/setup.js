import http from 'k6/http';
import { check, sleep } from 'k6';
import { expectedStatuses } from 'k6/http';

const BASE_URL = __ENV.BASE_URL || 'http://localhost:8080';
const ADMIN_EMAIL = __ENV.ADMIN_EMAIL || 'uiwang_gongu@email.com';
const ADMIN_PASSWORD = __ENV.ADMIN_PASSWORD || '1234';
const VU_COUNT = parseInt(__ENV.VU_COUNT || '200');
const STORE_ID = parseInt(__ENV.STORE_ID || '1');

// 서버 타임존(Asia/Seoul, UTC+9) 기준 로컬 시각 문자열 반환
function toKSTLocal(date) {
  const kst = new Date(date.getTime() + 9 * 60 * 60 * 1000);
  return kst.toISOString().replace('Z', '');
}

export function setup(options) {
  const { totalStock = 10 } = options || {};
  // 1. StoreAdmin 로그인 → adminToken
  const loginRes = http.post(
    `${BASE_URL}/auth/store-admin/login`,
    JSON.stringify({ email: ADMIN_EMAIL, password: ADMIN_PASSWORD }),
    { headers: { 'Content-Type': 'application/json' } },
  );
  check(loginRes, { 'admin login 200': (r) => r.status === 200 });
  const loginBody = loginRes.json();
  const adminToken = loginBody.data.accessToken;

  // 2. 테스트 상품 생성 → productId
  // startAt을 1시간 전으로 설정해 상품이 즉시 ACTIVE 상태가 되도록 함
  const now = new Date();
  const oneHourAgo = new Date(now.getTime() - 60 * 60 * 1000);
  const tomorrow = new Date(now.getTime() + 24 * 60 * 60 * 1000);
  const startAt = toKSTLocal(oneHourAgo);
  const endAt = toKSTLocal(tomorrow);

  const productRes = http.post(
    `${BASE_URL}/admin/products`,
    JSON.stringify({
      name: 'k6 테스트 상품',
      description: '부하 테스트용 상품',
      price: 10000,
      totalStock,
      startAt,
      endAt,
    }),
    { headers: { 'Content-Type': 'application/json', Authorization: `Bearer ${adminToken}` } },
  );
  check(productRes, { 'product created 201': (r) => r.status === 201 });
  const productBody = productRes.json();
  const productId = productBody.data.id;
  const storeId = STORE_ID;

  // 2-1. 상품이 ACTIVE 상태가 될 때까지 폴링 (스케줄러가 매 분 0초에 실행)
  for (let i = 0; i < 70; i++) {
    const statusRes = http.get(
      `${BASE_URL}/admin/products/${productId}`,
      { headers: { Authorization: `Bearer ${adminToken}` } },
    );
    if (statusRes.json().data.status === 'ACTIVE') break;
    sleep(1);
  }

  // 3. 유저 토큰 발급 (userId 1 ~ VU_COUNT) → tokens 배열
  //    각 userId에 대해 POST /auth/test-login
  const tokens = [];
  for (let userId = 1; userId <= VU_COUNT; userId++) {
    const tokenRes = http.post(
      `${BASE_URL}/auth/test-login`,
      JSON.stringify({ userId }),
      { headers: { 'Content-Type': 'application/json' } },
    );
    check(tokenRes, { [`test-login userId=${userId} 200`]: (r) => r.status === 200 });
    const tokenBody = tokenRes.json();
    tokens.push(tokenBody.data.accessToken);
  }

  // 4. 각 유저가 테스트 스토어 구독
  //    POST /users/me/stores with { storeId, isPreferred: false }
  for (let i = 0; i < tokens.length; i++) {
    const subRes = http.post(
      `${BASE_URL}/users/me/stores`,
      JSON.stringify({ storeId, isPreferred: false }),
      {
        headers: { 'Content-Type': 'application/json', Authorization: `Bearer ${tokens[i]}` },
        responseCallback: expectedStatuses(201, 409),
      },
    );
    // 201 Created 또는 이미 구독된 경우(409)도 정상으로 처리
    check(subRes, { [`store subscribe userId=${i + 1}`]: (r) => r.status === 201 || r.status === 409 });
  }

  return { tokens, productId, adminToken, storeId };
}

export function teardown(data) {
  const { adminToken, productId } = data;
  if (!adminToken || !productId) return;

  http.del(
    `${BASE_URL}/admin/products/${productId}`,
    null,
    { headers: { 'Content-Type': 'application/json', Authorization: `Bearer ${adminToken}` } },
  );
}
