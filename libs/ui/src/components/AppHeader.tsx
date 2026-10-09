export interface NavItem {
  label: string;
  href: string;
  active?: boolean;
}

export interface AppHeaderProps {
  title?: string;
  navItems?: NavItem[];
}

export function AppHeader({ title = "delFIN", navItems = [] }: AppHeaderProps) {
  return (
    <header className="bg-primary-900 text-white shadow-md">
      <div className="mx-auto max-w-screen-xl px-4 sm:px-6 lg:px-8">
        <div className="flex h-16 items-center justify-between">
          <span className="text-xl font-semibold tracking-tight">{title}</span>
          {navItems.length > 0 && (
            <nav className="flex gap-6">
              {navItems.map((item) => (
                <a
                  key={item.href}
                  href={item.href}
                  className={
                    item.active
                      ? "border-b-2 border-white pb-0.5 text-sm font-medium"
                      : "text-sm font-medium text-blue-200 hover:text-white transition-colors"
                  }
                >
                  {item.label}
                </a>
              ))}
            </nav>
          )}
        </div>
      </div>
    </header>
  );
}
