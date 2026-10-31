import type { ReactNode } from "react";
import { AppHeader, type NavItem } from "./AppHeader";
import { SideNav, type NavGroup } from "./SideNav";

export interface PageLayoutProps {
  title?: string;
  /** A flat row across the top. Fine up to about six. */
  navItems?: NavItem[];
  /**
   * Grouped navigation down the side, for an application with more pages
   * than a row can hold. Takes precedence over `navItems` when given, so a
   * service opts in without the other having to move.
   */
  navGroups?: NavGroup[];
  children: ReactNode;
}

export function PageLayout({ title, navItems, navGroups, children }: PageLayoutProps) {
  if (navGroups && navGroups.length > 0) {
    return (
      <div className="flex min-h-screen bg-neutral-100">
        <SideNav title={title} groups={navGroups} />
        {/* Not centred in the remaining space: a sidebar already anchors the
            page to the left, and centring what is left of it puts the content
            somewhere different on every screen width. */}
        <main className="min-w-0 flex-1 px-4 py-8 sm:px-6 lg:px-8">{children}</main>
      </div>
    );
  }

  return (
    <div className="min-h-screen bg-neutral-100">
      <AppHeader title={title} navItems={navItems} />
      <main className="mx-auto max-w-screen-xl px-4 py-8 sm:px-6 lg:px-8">
        {children}
      </main>
    </div>
  );
}
