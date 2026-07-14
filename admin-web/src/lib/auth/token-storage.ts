const REFRESH_TOKEN_KEY = "gongu.admin.refreshToken";

let accessToken: string | null = null;

function canUseLocalStorage() {
  return typeof window !== "undefined" && typeof window.localStorage !== "undefined";
}

export function getAccessToken() {
  return accessToken;
}

export function setAccessToken(token: string | null) {
  accessToken = token;
}

export function getRefreshToken() {
  if (!canUseLocalStorage()) {
    return null;
  }

  return window.localStorage.getItem(REFRESH_TOKEN_KEY);
}

export function setRefreshToken(token: string | null) {
  if (!canUseLocalStorage()) {
    return;
  }

  if (token) {
    window.localStorage.setItem(REFRESH_TOKEN_KEY, token);
    return;
  }

  window.localStorage.removeItem(REFRESH_TOKEN_KEY);
}

export function saveTokens(tokens: { accessToken: string; refreshToken: string }) {
  setAccessToken(tokens.accessToken);
  setRefreshToken(tokens.refreshToken);
}

export function clearTokens() {
  setAccessToken(null);
  setRefreshToken(null);
}
