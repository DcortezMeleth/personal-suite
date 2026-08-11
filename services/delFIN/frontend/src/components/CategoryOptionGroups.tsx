import type { Category } from "../api/client";

function categoryLabel(c: Category) {
  return c.icon ? `${c.icon} ${c.name}` : c.name;
}

// For assignment contexts (a transaction's category, a rule's target
// category) — a category with subcategories is a pure rollup container and
// can never be assigned directly, so it becomes a disabled optgroup label
// with its children as the only selectable options underneath. A top-level
// category with no children stays a normal, directly selectable option.
export function CategoryOptionGroups({ categories }: { categories: Category[] }) {
  const topLevel = categories.filter((c) => !c.parentId);
  const childrenOf = (id: string) => categories.filter((c) => c.parentId === id);

  return (
    <>
      {topLevel.map((top) => {
        const children = childrenOf(top.id);
        if (children.length === 0) {
          return <option key={top.id} value={top.id}>{categoryLabel(top)}</option>;
        }
        return (
          <optgroup key={top.id} label={categoryLabel(top)}>
            {children.map((child) => (
              <option key={child.id} value={child.id}>{categoryLabel(child)}</option>
            ))}
          </optgroup>
        );
      })}
    </>
  );
}
