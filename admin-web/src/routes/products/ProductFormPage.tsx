import { FormEvent, useEffect, useState } from "react";
import { Link, useNavigate, useParams } from "react-router-dom";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";

import type { components } from "@/lib/api/schema";
import { authApiClient } from "@/lib/auth/auth-fetch";

type ErrorResponse = components["schemas"]["ErrorResponse"];

const inputClassName =
  "h-11 w-full rounded-md border border-input bg-background px-3 text-sm outline-none transition-colors placeholder:text-muted-foreground focus:border-ring focus:ring-2 focus:ring-ring/15";

type FormState = {
  name: string;
  description: string;
  price: string;
  totalStock: string;
  startAt: string;
  endAt: string;
};

const emptyForm: FormState = {
  name: "",
  description: "",
  price: "",
  totalStock: "",
  startAt: "",
  endAt: "",
};

/** `2026-09-01T00:00:00` (or with fractional/offset) -> `2026-09-01T00:00` for datetime-local. */
function toInputDateTime(value: string | undefined) {
  if (!value) {
    return "";
  }
  const match = value.match(/^(\d{4}-\d{2}-\d{2}T\d{2}:\d{2})/);
  return match ? match[1] : "";
}

/** `2026-09-01T00:00` -> `2026-09-01T00:00:00` (backend wants seconds, no `Z`). */
function toBackendDateTime(value: string) {
  return value.length === 16 ? `${value}:00` : value;
}

function collectHints(form: FormState): string[] {
  const hints: string[] = [];
  if (!form.name.trim()) hints.push("상품명을 입력해 주세요.");
  if (!form.description.trim()) hints.push("설명을 입력해 주세요.");
  if (!(Number(form.price) > 0)) hints.push("가격은 0보다 커야 합니다.");
  if (!(Number(form.totalStock) > 0)) hints.push("총 수량은 0보다 커야 합니다.");
  if (!form.startAt) hints.push("판매 시작일시를 입력해 주세요.");
  if (!form.endAt) hints.push("판매 종료일시를 입력해 주세요.");
  if (form.startAt && form.endAt && form.startAt >= form.endAt) {
    hints.push("판매 시작은 종료보다 앞서야 합니다.");
  }
  return hints;
}

function messagesFromError(body: ErrorResponse | undefined): string[] {
  const reasons = body?.errors?.map((entry) => entry.reason).filter(Boolean) as string[] | undefined;
  if (reasons && reasons.length > 0) {
    return reasons;
  }
  return ["요청을 처리하지 못했습니다. 입력값을 확인해 주세요."];
}

export function ProductFormPage() {
  const { id } = useParams();
  const isEdit = id != null;
  const productId = Number(id);
  const navigate = useNavigate();
  const queryClient = useQueryClient();

  const [form, setForm] = useState<FormState>(emptyForm);
  const [serverErrors, setServerErrors] = useState<string[]>([]);

  const detailQuery = useQuery({
    queryKey: ["admin-product", productId],
    queryFn: async () => {
      const { data, error } = await authApiClient.GET("/admin/products/{product_id}", {
        params: { path: { product_id: productId } },
      });
      if (error || !data) {
        throw new Error("상품 정보를 불러오지 못했습니다.");
      }
      return data;
    },
    enabled: isEdit && Number.isFinite(productId),
  });

  useEffect(() => {
    const product = detailQuery.data?.data;
    if (!product) {
      return;
    }
    setForm({
      name: product.name ?? "",
      description: product.description ?? "",
      price: product.price != null ? String(product.price) : "",
      totalStock: product.totalStock != null ? String(product.totalStock) : "",
      startAt: toInputDateTime(product.startAt),
      endAt: toInputDateTime(product.endAt),
    });
  }, [detailQuery.data]);

  const mutation = useMutation({
    mutationFn: async () => {
      const body = {
        name: form.name,
        description: form.description,
        price: Number(form.price),
        totalStock: Number(form.totalStock),
        startAt: toBackendDateTime(form.startAt),
        endAt: toBackendDateTime(form.endAt),
      };

      if (isEdit) {
        const { data, error } = await authApiClient.PUT("/admin/products/{product_id}", {
          params: { path: { product_id: productId } },
          body,
        });
        if (error || !data) {
          throw error ?? new Error("수정에 실패했습니다.");
        }
        return data;
      }

      const { data, error } = await authApiClient.POST("/admin/products", { body });
      if (error || !data) {
        throw error ?? new Error("등록에 실패했습니다.");
      }
      return data;
    },
    onSuccess: async () => {
      await queryClient.invalidateQueries({ queryKey: ["admin-products"] });
      if (isEdit) {
        await queryClient.invalidateQueries({ queryKey: ["admin-product", productId] });
        navigate(`/products/${productId}`);
      } else {
        navigate("/products");
      }
    },
    onError: (error) => {
      const body = (error as { errors?: unknown; code?: unknown }) as ErrorResponse;
      if (body && (body.errors || body.code)) {
        setServerErrors(messagesFromError(body));
      } else {
        setServerErrors([error instanceof Error ? error.message : "요청을 처리하지 못했습니다."]);
      }
    },
  });

  const hints = collectHints(form);

  function update<K extends keyof FormState>(key: K, value: string) {
    setForm((prev) => ({ ...prev, [key]: value }));
  }

  function handleSubmit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    setServerErrors([]);
    mutation.mutate();
  }

  const invalidId = isEdit && !Number.isFinite(productId);

  if (invalidId || (isEdit && detailQuery.isError)) {
    const notFound =
      invalidId ||
      (detailQuery.error instanceof Error && detailQuery.error.message === "NOT_FOUND");
    return (
      <div className="space-y-4">
        <p className="rounded-md bg-destructive/10 px-3 py-2 text-sm text-destructive" role="alert">
          {notFound
            ? "존재하지 않는 상품입니다."
            : detailQuery.error instanceof Error
              ? detailQuery.error.message
              : "상품 정보를 불러오지 못했습니다."}
        </p>
        <Link to="/products" className="text-sm text-muted-foreground underline">
          상품 목록으로 돌아가기
        </Link>
      </div>
    );
  }

  if (isEdit && detailQuery.isPending) {
    return <p className="text-sm text-muted-foreground">불러오는 중…</p>;
  }

  return (
    <div className="max-w-xl space-y-6">
      <h1 className="text-xl font-semibold tracking-normal">
        {isEdit ? "상품 수정" : "상품 등록"}
      </h1>

      <form className="space-y-5" onSubmit={handleSubmit}>
        <div className="space-y-2">
          <label className="text-sm font-medium" htmlFor="name">
            상품명
          </label>
          <input
            id="name"
            className={inputClassName}
            value={form.name}
            onChange={(event) => update("name", event.target.value)}
          />
        </div>

        <div className="space-y-2">
          <label className="text-sm font-medium" htmlFor="description">
            설명
          </label>
          <textarea
            id="description"
            className={`${inputClassName} h-24 py-2`}
            value={form.description}
            onChange={(event) => update("description", event.target.value)}
          />
        </div>

        <div className="grid grid-cols-2 gap-4">
          <div className="space-y-2">
            <label className="text-sm font-medium" htmlFor="price">
              가격 (₩)
            </label>
            <input
              id="price"
              type="number"
              min={1}
              className={inputClassName}
              value={form.price}
              onChange={(event) => update("price", event.target.value)}
            />
          </div>
          <div className="space-y-2">
            <label className="text-sm font-medium" htmlFor="totalStock">
              총 수량
            </label>
            <input
              id="totalStock"
              type="number"
              min={1}
              className={inputClassName}
              value={form.totalStock}
              onChange={(event) => update("totalStock", event.target.value)}
            />
          </div>
        </div>

        <div className="grid grid-cols-2 gap-4">
          <div className="space-y-2">
            <label className="text-sm font-medium" htmlFor="startAt">
              판매 시작
            </label>
            <input
              id="startAt"
              type="datetime-local"
              className={inputClassName}
              value={form.startAt}
              onChange={(event) => update("startAt", event.target.value)}
            />
          </div>
          <div className="space-y-2">
            <label className="text-sm font-medium" htmlFor="endAt">
              판매 종료
            </label>
            <input
              id="endAt"
              type="datetime-local"
              className={inputClassName}
              value={form.endAt}
              onChange={(event) => update("endAt", event.target.value)}
            />
          </div>
        </div>

        {hints.length > 0 ? (
          <ul className="space-y-1 rounded-md bg-accent/40 px-3 py-2 text-sm text-muted-foreground">
            {hints.map((hint) => (
              <li key={hint}>• {hint}</li>
            ))}
          </ul>
        ) : null}

        {serverErrors.length > 0 ? (
          <ul
            className="space-y-1 rounded-md bg-destructive/10 px-3 py-2 text-sm text-destructive"
            role="alert"
          >
            {serverErrors.map((message) => (
              <li key={message}>{message}</li>
            ))}
          </ul>
        ) : null}

        <div className="flex gap-2">
          <button
            type="submit"
            disabled={mutation.isPending}
            className="h-11 rounded-md bg-primary px-4 text-sm font-medium text-primary-foreground transition-colors hover:bg-primary/90 disabled:cursor-not-allowed disabled:opacity-60"
          >
            {mutation.isPending ? "저장 중…" : isEdit ? "수정 저장" : "등록"}
          </button>
          <Link
            to={isEdit ? `/products/${productId}` : "/products"}
            className="h-11 rounded-md border border-input px-4 text-sm font-medium leading-[2.75rem] transition-colors hover:bg-accent"
          >
            취소
          </Link>
        </div>
      </form>
    </div>
  );
}
