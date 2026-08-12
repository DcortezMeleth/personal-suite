import { Fragment } from "react";
import type { Category } from "../api/client";

function categoryLabel(c: Category) {
  return c.icon ? `${c.icon} ${c.name}` : c.name;
}

// For filter contexts (transactions list, rules list) — unlike assignment
// contexts, a parent category stays directly selectable here (filtering by
// "Car" rolls its children in too, matching the backend), just visually
// indented under it rather than grouped into a disabled optgroup.
export function CategoryFilterOptions({ categories }: { categories: Category[] }) {
  return (
    <>
      {categories.filter((c) => !c.parentId).map((top) => (
        <Fragment key={top.id}>
          <option value={top.id}>{categoryLabel(top)}</option>
          {categories.filter((c) => c.parentId === top.id).map((child) => (
            <option key={child.id} value={child.id}>{"  ↳ " + categoryLabel(child)}</option>
          ))}
        </Fragment>
      ))}
    </>
  );
}
