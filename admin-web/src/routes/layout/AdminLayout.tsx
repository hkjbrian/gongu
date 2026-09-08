import { NavLink, Outlet, useMatch } from "react-router-dom";

const navItems = [
  { to: "/products", label: "상품 관리" },
  { to: "/users", label: "회원 관리" },
];

const baseNavClassName =
  "block rounded-md px-3 py-2 text-sm font-medium transition-colors";
const activeNavClassName = "bg-primary text-primary-foreground";
const inactiveNavClassName =
  "text-muted-foreground hover:bg-accent hover:text-accent-foreground";

function NavItem({ to, label }: { to: string; label: string }) {
  // Section-level highlight: active when the current path is at or below `to`.
  const isSectionActive = useMatch({ path: to, end: false }) != null;

  return (
    <NavLink
      // `end` makes NavLink's own active state (and thus `aria-current="page"`)
      // an exact match only, so `/products/new` does not mark "상품 관리".
      end
      to={to}
      className={() =>
        `${baseNavClassName} ${
          isSectionActive ? activeNavClassName : inactiveNavClassName
        }`
      }
    >
      {label}
    </NavLink>
  );
}

export function AdminLayout() {
  return (
    <div className="flex min-h-screen bg-background text-foreground">
      <aside className="flex w-60 shrink-0 flex-col border-r border-border px-4 py-6">
        <div className="mb-8 px-3">
          <p className="text-sm font-medium text-muted-foreground">Gongu Admin</p>
          <h1 className="mt-1 text-lg font-semibold tracking-normal">매장 관리</h1>
        </div>

        <nav className="space-y-1" aria-label="주요">
          {navItems.map((item) => (
            <NavItem key={item.to} to={item.to} label={item.label} />
          ))}
        </nav>
      </aside>

      <main id="main-content" className="min-w-0 flex-1 px-8 py-6">
        <div className="overflow-x-auto">
          <Outlet />
        </div>
      </main>
    </div>
  );
}
