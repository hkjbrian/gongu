import { useState } from "react";
import { Link, useNavigate } from "react-router-dom";
import { keepPreviousData, useQuery } from "@tanstack/react-query";
import {
  createColumnHelper,
  flexRender,
  getCoreRowModel,
  useReactTable,
} from "@tanstack/react-table";

import type { components } from "@/lib/api/schema";
import { authApiClient } from "@/lib/auth/auth-fetch";

type ProductSummary = components["schemas"]["AdminProductSummary"];

const PAGE_SIZE = 20;

const statusLabels: Record<string, string> = {
  UPCOMING: "예정",
  ACTIVE: "진행중",
  SOLD_OUT: "품절",
  CLOSED: "마감",
};

function formatPrice(price: number | undefined) {
  if (typeof price !== "number") {
    return "-";
  }
  return `₩${price.toLocaleString("ko-KR")}`;
}

function formatDate(value: string | undefined) {
  if (!value) {
    return "-";
  }
  return value.slice(0, 10);
}

const columnHelper = createColumnHelper<ProductSummary>();

const columns = [
  columnHelper.accessor("name", {
    header: "상품명",
    cell: (info) => {
      const name = info.getValue() ?? "-";
      const id = info.row.original.id;
      return id != null ? (
        <Link
          to={`/products/${id}`}
          className="font-medium text-foreground underline-offset-2 hover:underline focus:outline-none focus:ring-2 focus:ring-ring/40"
          onClick={(event) => event.stopPropagation()}
        >
          {name}
        </Link>
      ) : (
        name
      );
    },
  }),
  columnHelper.accessor("price", {
    header: "가격",
    cell: (info) => formatPrice(info.getValue()),
  }),
  columnHelper.accessor("remainingStock", {
    header: "잔여 수량",
    cell: (info) => info.getValue() ?? "-",
  }),
  columnHelper.accessor("status", {
    header: "상태",
    cell: (info) => {
      const status = info.getValue();
      return status ? (statusLabels[status] ?? status) : "-";
    },
  }),
  columnHelper.display({
    id: "period",
    header: "판매기간",
    cell: (info) => {
      const row = info.row.original;
      return `${formatDate(row.startAt)} ~ ${formatDate(row.endAt)}`;
    },
  }),
];

export function ProductListPage() {
  const navigate = useNavigate();
  const [pageIndex, setPageIndex] = useState(0);

  const { data, isPending, isError, isFetching, error } = useQuery({
    queryKey: ["admin-products", pageIndex],
    queryFn: async () => {
      const { data, error } = await authApiClient.GET("/admin/products", {
        params: { query: { page: pageIndex, size: PAGE_SIZE } },
      });
      if (error) {
        throw new Error("상품 목록을 불러오지 못했습니다.");
      }
      return data;
    },
    placeholderData: keepPreviousData,
  });

  const page = data?.data;
  const rows = page?.content ?? [];
  const currentPage = page?.number ?? pageIndex;
  const totalPages = page?.totalPages ?? 0;
  const atFirstPage = pageIndex <= 0;
  const atLastPage = totalPages > 0 && pageIndex >= totalPages - 1;

  const table = useReactTable({
    data: rows,
    columns,
    getCoreRowModel: getCoreRowModel(),
  });

  return (
    <div className="space-y-6">
      <div className="flex items-center justify-between">
        <h1 className="text-xl font-semibold tracking-normal">상품 관리</h1>
        <Link
          to="/products/new"
          className="h-9 rounded-md bg-primary px-4 text-sm font-medium leading-9 text-primary-foreground transition-colors hover:bg-primary/90"
        >
          상품 등록
        </Link>
      </div>

      {isError ? (
        <p className="rounded-md bg-destructive/10 px-3 py-2 text-sm text-destructive" role="alert">
          {error instanceof Error ? error.message : "상품 목록을 불러오지 못했습니다."}
        </p>
      ) : isPending ? (
        <p className="text-sm text-muted-foreground">불러오는 중…</p>
      ) : rows.length === 0 ? (
        <p className="text-sm text-muted-foreground">등록된 상품이 없습니다.</p>
      ) : (
        <div className="overflow-x-auto rounded-md border border-border">
          <table className="w-full border-collapse text-sm">
            <thead>
              {table.getHeaderGroups().map((headerGroup) => (
                <tr key={headerGroup.id} className="border-b border-border bg-accent/40">
                  {headerGroup.headers.map((header) => (
                    <th
                      key={header.id}
                      className="px-4 py-2 text-left font-medium text-muted-foreground"
                    >
                      {header.isPlaceholder
                        ? null
                        : flexRender(header.column.columnDef.header, header.getContext())}
                    </th>
                  ))}
                </tr>
              ))}
            </thead>
            <tbody>
              {table.getRowModel().rows.map((row) => {
                const productId = row.original.id;
                const navigable = productId != null;
                const goToDetail = () => {
                  if (!navigable) {
                    return;
                  }
                  navigate(`/products/${productId}`);
                };
                return (
                  <tr
                    key={row.id}
                    onClick={goToDetail}
                    className={`border-b border-border last:border-b-0 transition-colors ${
                      navigable ? "cursor-pointer hover:bg-accent/40" : "cursor-default"
                    }`}
                  >
                    {row.getVisibleCells().map((cell) => (
                      <td key={cell.id} className="px-4 py-2">
                        {flexRender(cell.column.columnDef.cell, cell.getContext())}
                      </td>
                    ))}
                  </tr>
                );
              })}
            </tbody>
          </table>
        </div>
      )}

      {totalPages > 1 ? (
        <div className="flex items-center justify-between text-sm">
          <button
            type="button"
            onClick={() => setPageIndex((index) => Math.max(0, index - 1))}
            disabled={isFetching || atFirstPage}
            className="h-9 rounded-md border border-input px-3 transition-colors hover:bg-accent disabled:cursor-not-allowed disabled:opacity-50"
          >
            이전
          </button>
          <span className="text-muted-foreground">
            {currentPage + 1} / {totalPages}
          </span>
          <button
            type="button"
            onClick={() => setPageIndex((index) => index + 1)}
            disabled={isFetching || atLastPage}
            className="h-9 rounded-md border border-input px-3 transition-colors hover:bg-accent disabled:cursor-not-allowed disabled:opacity-50"
          >
            다음
          </button>
        </div>
      ) : null}
    </div>
  );
}
