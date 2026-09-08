import { createBrowserRouter, Navigate } from "react-router-dom";

import { RequireAuth } from "@/lib/auth/RequireAuth";
import { AdminLayout } from "@/routes/layout/AdminLayout";
import { LoginPage } from "@/routes/login/LoginPage";
import { ProductDetailPage } from "@/routes/products/ProductDetailPage";
import { ProductFormPage } from "@/routes/products/ProductFormPage";
import { ProductListPage } from "@/routes/products/ProductListPage";
import { ProductOrdersPage } from "@/routes/products/ProductOrdersPage";
import { UserListPage } from "@/routes/users/UserListPage";
import { UserOrdersPage } from "@/routes/users/UserOrdersPage";

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
