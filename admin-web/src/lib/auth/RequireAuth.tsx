import { useEffect, useState, type ReactNode } from "react";

import { initializeAuth, isAuthenticated } from "@/lib/auth/auth-fetch";

type RequireAuthProps = {
  children: ReactNode;
};

export function RequireAuth({ children }: RequireAuthProps) {
  const [initializing, setInitializing] = useState(true);
  const [authenticated, setAuthenticated] = useState(() => isAuthenticated());

  useEffect(() => {
    let mounted = true;

    initializeAuth().then((nextAuthenticated) => {
      if (!mounted) {
        return;
      }

      setAuthenticated(nextAuthenticated);
      setInitializing(false);
    });

    return () => {
      mounted = false;
    };
  }, []);

  useEffect(() => {
    if (initializing || authenticated || window.location.pathname === "/login") {
      return;
    }

    window.location.href = "/login";
  }, [authenticated, initializing]);

  if (initializing || !authenticated) {
    return null;
  }

  return <>{children}</>;
}
