import http from 'k6/http';
import { check } from 'k6';

const BASE_URL = __ENV.BASE_URL || 'http://localhost:8080';
const ADMIN_EMAIL = __ENV.ADMIN_EMAIL || 'admin@test.com';
const ADMIN_PASSWORD = __ENV.ADMIN_PASSWORD || 'password';
const VU_COUNT = parseInt(__ENV.VU_COUNT || '200');

// 서버 타임존(Asia/Seoul, UTC+9) 기준 로컬 시각 문자열 반환
function toKSTLocal(date) {
  const kst = new Date(date.getTime() + 9 * 60 * 60 * 1000);
  return kst.toISOString().replace('Z', '');
}

export function setup({ totalStock = 10 } = {}) {
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
  const now = new Date();
  const tomorrow = new Date(now.getTime() + 24 * 60 * 60 * 1000);
  const startAt = toKSTLocal(now);
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
  const storeId = productBody.data.storeId;

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
      { headers: { 'Content-Type': 'application/json', Authorization: `Bearer ${tokens[i]}` } },
    );
    // 201 Created 또는 이미 구독된 경우(409 등)도 허용
    check(subRes, { [`store subscribe userId=${i + 1}`]: (r) => r.status === 201 || r.status === 409 });
  }

  return { tokens, productId, adminToken, storeId };
}
