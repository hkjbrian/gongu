import createClient, { type Middleware } from "openapi-fetch";

import type { paths } from "@/lib/api/schema";
import {
  clearTokens,
  getAccessToken,
  getRefreshToken,
  setAccessToken,
} from "@/lib/auth/token-storage";

const RETRY_HEADER = "x-gongu-auth-retry";
const requestClones = new WeakMap<Request, Request>();

export const rawAuthClient = createClient<paths>({
  baseUrl: import.meta.env.VITE_API_BASE_URL,
});

let refreshPromise: Promise<string | null> | null = null;
let initializationPromise: Promise<boolean> | null = null;

function redirectToLogin() {
  if (typeof window === "undefined" || window.location.pathname === "/login") {
    return;
  }

  window.location.href = "/login";
}

async function refreshAccessToken() {
  const refreshToken = getRefreshToken();

  if (!refreshToken) {
    return null;
  }

  refreshPromise ??= rawAuthClient
    .POST("/auth/token/refresh", {
      body: { refreshToken },
    })
    .then(({ data }) => {
      const nextAccessToken = data?.data?.accessToken ?? null;

      if (nextAccessToken) {
        setAccessToken(nextAccessToken);
      }

      return nextAccessToken;
    })
    .catch(() => null)
    .finally(() => {
      refreshPromise = null;
    });

  return refreshPromise;
}

export function initializeAuth() {
  if (getAccessToken()) {
    return Promise.resolve(true);
  }

  if (!getRefreshToken()) {
    return Promise.resolve(false);
  }

  initializationPromise ??= refreshAccessToken()
    .then(Boolean)
    .finally(() => {
      initializationPromise = null;
    });

  return initializationPromise;
}

export function isAuthenticated() {
  return Boolean(getAccessToken());
}

const authMiddleware: Middleware = {
  onRequest({ request }) {
    const accessToken = getAccessToken();

    if (!accessToken) {
      return request;
    }

    const headers = new Headers(request.headers);
    headers.set("Authorization", `Bearer ${accessToken}`);

    const newRequest = new Request(request, { headers });

    if (newRequest.body) {
      requestClones.set(newRequest, newRequest.clone());
    }

    return newRequest;
  },

  async onResponse({ request, response }) {
    if (response.status !== 401 || request.headers.get(RETRY_HEADER) === "1") {
      return response;
    }

    const nextAccessToken = await refreshAccessToken();

    if (!nextAccessToken) {
      clearTokens();
      redirectToLogin();
      return response;
    }

    const headers = new Headers(request.headers);
    headers.set("Authorization", `Bearer ${nextAccessToken}`);
    headers.set(RETRY_HEADER, "1");

    const baseRequest = requestClones.get(request) ?? request;

    return fetch(new Request(baseRequest, { headers }));
  },
};

export const authApiClient = createClient<paths>({
  baseUrl: import.meta.env.VITE_API_BASE_URL,
});

authApiClient.use(authMiddleware);
