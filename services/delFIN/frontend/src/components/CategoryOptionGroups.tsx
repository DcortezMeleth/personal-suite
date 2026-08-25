import type { Category } from "../api/client";

function categoryLabel(c: Category) {
  return c.icon ? `${c.icon} ${c.name}` : c.name;
}

// For assignment contexts (a transaction's category, a rule's target
// category) — a category with subcategories is a pure rollup container and
// can never be assigned directly, so it becomes a disabled optgroup label
// with its children as the only selectable options underneath. A top-level
// category with no children stays a normal, directly selectable option.
export function CategoryOptionGroups({ categories, currentId }: { categories: Category[]; currentId?: string | null }) {
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
            {/* Something already assigned to this rollup parent needs an option
                of its own, or the <select> matches nothing and the browser falls
                back to rendering the FIRST option — reporting "Uncategorized",
                or an unrelated category, for a row that is neither. Kept
                disabled so it still can't be chosen going forward, which is
                what assertAssignable enforces server-side anyway. Rules do
                target these parents (e.g. Travels), so this is normal data,
                not a leftover. */}
            {currentId === top.id && (
              <option value={top.id} disabled>{categoryLabel(top)}</option>
            )}
            {children.map((child) => (
              <option key={child.id} value={child.id}>{categoryLabel(child)}</option>
            ))}
          </optgroup>
        );
      })}
    </>
  );
}
