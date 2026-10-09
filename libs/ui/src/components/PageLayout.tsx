import type { ReactNode } from "react";
import { AppHeader, type NavItem } from "./AppHeader";

export interface PageLayoutProps {
  title?: string;
  navItems?: NavItem[];
  children: ReactNode;
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
