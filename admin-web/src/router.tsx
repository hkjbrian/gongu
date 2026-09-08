import { createBrowserRouter, Link, Navigate, useRouteError } from "react-router-dom";

import { RequireAuth } from "@/lib/auth/RequireAuth";
import { AdminLayout } from "@/routes/layout/AdminLayout";
import { LoginPage } from "@/routes/login/LoginPage";
import { ProductDetailPage } from "@/routes/products/ProductDetailPage";
import { ProductFormPage } from "@/routes/products/ProductFormPage";
import { ProductListPage } from "@/routes/products/ProductListPage";
import { ProductOrdersPage } from "@/routes/products/ProductOrdersPage";
import { UserListPage } from "@/routes/users/UserListPage";
import { UserOrdersPage } from "@/routes/users/UserOrdersPage";

function RouteErrorElement() {
  const error = useRouteError();
  const message = error instanceof Error ? error.message : undefined;

  return (
    <div className="flex min-h-screen flex-col items-center justify-center gap-3 p-6 text-center">
      <p className="text-lg font-semibold text-destructive">문제가 발생했습니다</p>
      {message && <p className="text-sm text-muted-foreground">{message}</p>}
      <Link to="/products" className="text-sm text-muted-foreground underline">
        상품 목록으로 돌아가기
      </Link>
    </div>
  );
}

export const router = createBrowserRouter([
  {
    path: "/login",
    element: <LoginPage />,
  },
  {
    element: (
      <RequireAuth>
        <AdminLayout />
      </RequireAuth>
    ),
    errorElement: <RouteErrorElement />,
    children: [
      { index: true, element: <Navigate to="/products" replace /> },
      { path: "products", element: <ProductListPage /> },
      { path: "products/new", element: <ProductFormPage /> },
      { path: "products/:id", element: <ProductDetailPage /> },
      { path: "products/:id/orders", element: <ProductOrdersPage /> },
      { path: "users", element: <UserListPage /> },
      { path: "users/:id/orders", element: <UserOrdersPage /> },
    ],
  },
  {
    path: "*",
    element: <Navigate to="/products" replace />,
  },
], {
  future: {
    v7_relativeSplatPath: true,
  },
});
