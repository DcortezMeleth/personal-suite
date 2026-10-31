import type { NavItem } from "./AppHeader";

/**
 * A named set of pages that belong together.
 *
 * The label is a heading, not a control. Eleven items and three headings fit
 * a desktop screen without scrolling, so collapsing them would cost a click
 * to reach anything shut, hide where you are, and remove the overview that is
 * the reason for grouping in the first place. Accordions earn their keep at
 * thirty items or on a phone; this application is neither.
 */
export interface NavGroup {
  label: string;
  items: NavItem[];
}

export interface SideNavProps {
  title?: string;
  groups: NavGroup[];
}

/**
 * Navigation down the side rather than across the top.
 *
 * A top bar holds six comfortably and belFER grew to eleven, several of them
 * phrases rather than words — "Zajęcia międzyoddziałowe" — so the row ran out
 * of width and wrapped. Vertically there is room for every item at once, and
 * room for the labels to stay in the planner's own vocabulary instead of
 * being shortened until they fit.
 */
export function SideNav({ title = "delFIN", groups }: SideNavProps) {
  return (
    <nav className="flex w-60 shrink-0 flex-col gap-6 bg-primary-900 px-4 py-6 text-white">
      <span className="px-2 text-xl font-semibold tracking-tight">{title}</span>

      {groups.map((group) => (
        <div key={group.label}>
          <h2 className="px-2 pb-1 text-xs font-semibold uppercase tracking-wider text-blue-300">
            {group.label}
          </h2>
          <ul>
            {group.items.map((item) => (
              <li key={item.href}>
                <a
                  href={item.href}
                  // The current page is marked by a filled block rather than
                  // only by colour: this sits against a dark ground where a
                  // shade of blue is easy to miss.
                  aria-current={item.active ? "page" : undefined}
                  className={`block rounded px-2 py-1.5 text-sm transition-colors ${
                    item.active
                      ? "bg-primary-700 font-medium text-white"
                      : "text-blue-200 hover:bg-primary-800 hover:text-white"
                  }`}
                >
                  {item.label}
                </a>
              </li>
            ))}
          </ul>
        </div>
      ))}
    </nav>
  );
}
