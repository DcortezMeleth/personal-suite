import { AppHeader } from "./AppHeader";

interface NavItem {
  label: string;
  href: string;
  active?: boolean;
}

interface PageLayoutProps {
  title?: string;
  navItems?: NavItem[];
  children: React.ReactNode;
}

export function PageLayout({ title, navItems, children }: PageLayoutProps) {
  return (
    <div className="min-h-screen bg-neutral-100">
      <AppHeader title={title} navItems={navItems} />
      <main className="mx-auto max-w-screen-xl px-4 py-8 sm:px-6 lg:px-8">
        {children}
      </main>
    </div>
  );
}
