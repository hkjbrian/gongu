import { FormEvent, useState } from "react";

import { rawAuthClient } from "@/lib/auth/auth-fetch";
import { saveTokens } from "@/lib/auth/token-storage";

export function LoginPage() {
  const [email, setEmail] = useState("");
  const [password, setPassword] = useState("");
  const [errorMessage, setErrorMessage] = useState<string | null>(null);
  const [submitting, setSubmitting] = useState(false);

  async function handleSubmit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    setErrorMessage(null);
    setSubmitting(true);

    try {
      const { data, error } = await rawAuthClient.POST("/auth/store-admin/login", {
        body: { email, password },
      });

      const accessToken = data?.data?.accessToken;
      const refreshToken = data?.data?.refreshToken;

      if (error || !accessToken || !refreshToken) {
        setErrorMessage("이메일 또는 비밀번호를 확인해 주세요.");
        return;
      }

      saveTokens({ accessToken, refreshToken });
      window.location.href = "/products";
    } catch {
      setErrorMessage("로그인 중 문제가 발생했습니다. 잠시 후 다시 시도해 주세요.");
    } finally {
      setSubmitting(false);
    }
  }

  return (
    <main className="flex min-h-screen items-center justify-center bg-background px-6 py-10 text-foreground">
      <section className="w-full max-w-sm">
        <div className="mb-8">
          <p className="text-sm font-medium text-muted-foreground">Gongu Admin</p>
          <h1 className="mt-2 text-2xl font-semibold tracking-normal">매장 관리자 로그인</h1>
        </div>

        <form className="space-y-5" onSubmit={handleSubmit}>
          <div className="space-y-2">
            <label className="text-sm font-medium" htmlFor="email">
              이메일
            </label>
            <input
              autoComplete="email"
              className="h-11 w-full rounded-md border border-input bg-background px-3 text-sm outline-none transition-colors placeholder:text-muted-foreground focus:border-ring focus:ring-2 focus:ring-ring/15"
              id="email"
              inputMode="email"
              name="email"
              onChange={(event) => setEmail(event.target.value)}
              placeholder="admin@gongu.com"
              required
              type="email"
              value={email}
            />
          </div>

          <div className="space-y-2">
            <label className="text-sm font-medium" htmlFor="password">
              비밀번호
            </label>
            <input
              autoComplete="current-password"
              className="h-11 w-full rounded-md border border-input bg-background px-3 text-sm outline-none transition-colors placeholder:text-muted-foreground focus:border-ring focus:ring-2 focus:ring-ring/15"
              id="password"
              name="password"
              onChange={(event) => setPassword(event.target.value)}
              required
              type="password"
              value={password}
            />
          </div>

          {errorMessage ? (
            <p className="rounded-md bg-destructive/10 px-3 py-2 text-sm text-destructive" role="alert">
              {errorMessage}
            </p>
          ) : null}

          <button
            className="h-11 w-full rounded-md bg-primary px-4 text-sm font-medium text-primary-foreground transition-colors hover:bg-primary/90 disabled:cursor-not-allowed disabled:opacity-60"
            disabled={submitting}
            type="submit"
          >
            {submitting ? "로그인 중" : "로그인"}
          </button>
        </form>
      </section>
    </main>
  );
}
