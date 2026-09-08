import { type ReactNode } from "react";
import { Link, useNavigate, useParams } from "react-router-dom";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";

import { authApiClient } from "@/lib/auth/auth-fetch";

const statusLabels: Record<string, string> = {
  UPCOMING: "예정",
  ACTIVE: "진행중",
  SOLD_OUT: "품절",
  CLOSED: "마감",
};

const NOT_FOUND = "NOT_FOUND";

function formatPrice(price: number | undefined) {
  if (typeof price !== "number") {
    return "-";
  }
  return `₩${price.toLocaleString("ko-KR")}`;
}

function formatDateTime(value: string | undefined) {
  if (!value) {
    return "-";
  }
  return value.replace("T", " ").slice(0, 16);
}

function Field({ label, value }: { label: string; value: ReactNode }) {
  return (
    <div className="grid grid-cols-[120px_1fr] gap-4 border-b border-border py-3 text-sm last:border-b-0">
      <dt className="text-muted-foreground">{label}</dt>
      <dd className="whitespace-pre-wrap">{value}</dd>
    </div>
  );
}

export function ProductDetailPage() {
  const { id } = useParams();
  const productId = Number(id);
  const navigate = useNavigate();
  const queryClient = useQueryClient();

  const { data, isPending, isError, error } = useQuery({
    queryKey: ["admin-product", productId],
    queryFn: async () => {
      const { data, error, response } = await authApiClient.GET(
        "/admin/products/{product_id}",
        { params: { path: { product_id: productId } } },
      );
      if (error || !data) {
        throw new Error(response.status === 404 ? NOT_FOUND : "상품을 불러오지 못했습니다.");
      }
      return data;
    },
    enabled: Number.isFinite(productId),
  });

  const closeMutation = useMutation({
    mutationFn: async () => {
      const { error, response } = await authApiClient.DELETE(
        "/admin/products/{product_id}",
        { params: { path: { product_id: productId } } },
      );
      if (error && response.status !== 204) {
        throw new Error("상품 마감에 실패했습니다.");
      }
    },
    onSuccess: async () => {
      await queryClient.invalidateQueries({ queryKey: ["admin-products"] });
      await queryClient.invalidateQueries({ queryKey: ["admin-product", productId] });
      navigate("/products");
    },
  });

  function handleClose() {
    if (window.confirm("이 상품을 마감하시겠습니까? 마감 후에는 되돌릴 수 없습니다.")) {
      closeMutation.mutate();
    }
  }

  const invalidId = !Number.isFinite(productId);

  if (invalidId || isError) {
    const notFound =
      invalidId || (error instanceof Error && error.message === NOT_FOUND);
    return (
      <div className="space-y-4">
        <p className="rounded-md bg-destructive/10 px-3 py-2 text-sm text-destructive" role="alert">
          {notFound
            ? "존재하지 않는 상품입니다."
            : error instanceof Error
              ? error.message
              : "상품을 불러오지 못했습니다."}
        </p>
        <Link to="/products" className="text-sm text-muted-foreground underline">
          상품 목록으로 돌아가기
        </Link>
      </div>
    );
  }

  if (isPending) {
    return <p className="text-sm text-muted-foreground">불러오는 중…</p>;
  }

  const product = data.data ?? {};

  return (
    <div className="space-y-6">
      <div className="flex items-center justify-between">
        <h1 className="text-xl font-semibold tracking-normal">{product.name ?? "상품 상세"}</h1>
        <div className="flex gap-2">
          <Link
            to={`/products/${productId}/edit`}
            className="h-9 rounded-md border border-input px-4 text-sm font-medium leading-9 transition-colors hover:bg-accent"
          >
            수정
          </Link>
          <button
            type="button"
            onClick={handleClose}
            disabled={closeMutation.isPending || product.status === "CLOSED"}
            className="h-9 rounded-md bg-destructive px-4 text-sm font-medium leading-9 text-destructive-foreground transition-colors hover:bg-destructive/90 disabled:cursor-not-allowed disabled:opacity-50"
          >
            상품 마감
          </button>
        </div>
      </div>

      {closeMutation.isError ? (
        <p className="rounded-md bg-destructive/10 px-3 py-2 text-sm text-destructive" role="alert">
          {closeMutation.error instanceof Error
            ? closeMutation.error.message
            : "상품 마감에 실패했습니다."}
        </p>
      ) : null}

      <dl className="rounded-md border border-border px-4">
        <Field label="상품명" value={product.name ?? "-"} />
        <Field label="설명" value={product.description ?? "-"} />
        <Field label="가격" value={formatPrice(product.price)} />
        <Field label="총 수량" value={product.totalStock ?? "-"} />
        <Field label="잔여 수량" value={product.remainingStock ?? "-"} />
        <Field
          label="상태"
          value={product.status ? (statusLabels[product.status] ?? product.status) : "-"}
        />
        <Field label="판매 시작" value={formatDateTime(product.startAt)} />
        <Field label="판매 종료" value={formatDateTime(product.endAt)} />
      </dl>
    </div>
  );
}
