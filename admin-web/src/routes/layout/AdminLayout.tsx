import { NavLink, Outlet } from "react-router-dom";

const navItems = [
  { to: "/products", label: "상품 관리" },
  { to: "/users", label: "회원 관리" },
];

function navLinkClassName({ isActive }: { isActive: boolean }) {
  const base =
    "block rounded-md px-3 py-2 text-sm font-medium transition-colors";

  if (isActive) {
    return `${base} bg-primary text-primary-foreground`;
  }

  return `${base} text-muted-foreground hover:bg-accent hover:text-accent-foreground`;
}

export function AdminLayout() {
  return (
    <div className="flex min-h-screen bg-background text-foreground">
      <aside className="flex w-60 flex-col border-r border-border px-4 py-6">
        <div className="mb-8 px-3">
          <p className="text-sm font-medium text-muted-foreground">Gongu Admin</p>
          <h1 className="mt-1 text-lg font-semibold tracking-normal">매장 관리</h1>
        </div>

        <nav className="space-y-1">
          {navItems.map((item) => (
            <NavLink key={item.to} to={item.to} className={navLinkClassName}>
              {item.label}
            </NavLink>
          ))}
        </nav>
      </aside>

      <main className="flex-1 px-8 py-6">
        <Outlet />
      </main>
    </div>
  );
}
