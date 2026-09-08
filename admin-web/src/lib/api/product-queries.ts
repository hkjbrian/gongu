import { authApiClient } from "@/lib/auth/auth-fetch";

/** Shared query definition for the admin product detail (GET /admin/products/{id}). */
export function adminProductDetailQuery(productId: number) {
  return {
    queryKey: ["admin-product", productId] as const,
    queryFn: async () => {
      const { data, error, response } = await authApiClient.GET(
        "/admin/products/{product_id}",
        { params: { path: { product_id: productId } } },
      );
      if (error || !data) {
        throw new Error(
          response.status === 404 ? "NOT_FOUND" : "상품을 불러오지 못했습니다.",
        );
      }
      return data;
    },
  };
}
