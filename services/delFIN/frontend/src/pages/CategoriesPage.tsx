import { Fragment, useCallback, useEffect, useState } from "react";
import { DataCard, AlertBanner, ConfirmDialog } from "@delfin/ui";
import { CategoryFormModal } from "../components/CategoryFormModal";
import { api, Category, CategoryForm } from "../api/client";

export function CategoriesPage() {
  const [categories, setCategories] = useState<Category[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [statusMsg, setStatusMsg] = useState<string | null>(null);

  const [showForm, setShowForm] = useState(false);
  const [editingCategory, setEditingCategory] = useState<Category | null>(null);
  const [pendingDelete, setPendingDelete] = useState<Category | null>(null);

  const load = useCallback(async () => {
    setLoading(true);
    setError(null);
    try {
      setCategories(await api.get<Category[]>("/categories"));
    } catch (e) {
      setError(e instanceof Error ? e.message : "Failed to load categories");
    } finally {
      setLoading(false);
    }
  }, []);

  useEffect(() => { load(); }, [load]);

  const topLevel = categories.filter((c) => !c.parentId);
  const childrenOf = (parentId: string) => categories.filter((c) => c.parentId === parentId);

  async function handleCreate(form: CategoryForm) {
    try {
      await api.post("/categories", form);
      setShowForm(false);
      load();
    } catch (e) {
      setError(e instanceof Error ? e.message : "Failed to create category");
    }
  }

  async function handleUpdate(form: CategoryForm) {
    if (!editingCategory) return;
    try {
      await api.put(`/categories/${editingCategory.id}`, form);
      setEditingCategory(null);
      load();
    } catch (e) {
      setError(e instanceof Error ? e.message : "Failed to update category");
    }
  }

  async function confirmDelete() {
    if (!pendingDelete) return;
    const category = pendingDelete;
    setPendingDelete(null);
    try {
      await api.del(`/categories/${category.id}`);
      setStatusMsg(`Deleted "${category.name}".`);
      load();
    } catch (e) {
      setError(e instanceof Error ? e.message : "Failed to delete category");
    }
  }

  return (
    <div className="space-y-6">
      <div className="flex items-center justify-between">
        <h1 className="text-2xl font-bold text-neutral-900">Categories</h1>
        <button
          onClick={() => setShowForm(true)}
          className="rounded-md bg-primary-600 px-4 py-2 text-sm font-medium text-white hover:bg-primary-700"
        >
          + New Category
        </button>
      </div>

      {error && <AlertBanner level="danger" message={error} onDismiss={() => setError(null)} />}
      {statusMsg && <AlertBanner level="success" message={statusMsg} onDismiss={() => setStatusMsg(null)} />}

      <DataCard title={`${categories.length} categories`}>
        {loading ? (
          <p className="py-6 text-center text-sm text-neutral-500">Loading…</p>
        ) : categories.length === 0 ? (
          <p className="py-6 text-center text-sm text-neutral-500">No categories yet.</p>
        ) : (
          <div className="overflow-x-auto">
            <table className="w-full text-sm">
              <thead>
                <tr className="border-b border-neutral-200 text-left text-xs font-semibold uppercase tracking-wide text-neutral-500">
                  <th className="py-2 pr-4">Category</th>
                  <th className="py-2 text-right">Actions</th>
                </tr>
              </thead>
              <tbody>
                {topLevel.map((top) => (
                  <Fragment key={top.id}>
                    <CategoryRow category={top} onEdit={setEditingCategory} onDelete={setPendingDelete} />
                    {childrenOf(top.id).map((child) => (
                      <CategoryRow key={child.id} category={child} indented onEdit={setEditingCategory} onDelete={setPendingDelete} />
                    ))}
                  </Fragment>
                ))}
              </tbody>
            </table>
          </div>
        )}
      </DataCard>

      {showForm && (
        <CategoryFormModal categories={categories} onSave={handleCreate} onClose={() => setShowForm(false)} />
      )}
      {editingCategory && (
        <CategoryFormModal
          categories={categories}
          initial={editingCategory}
          onSave={handleUpdate}
          onClose={() => setEditingCategory(null)}
        />
      )}
      {pendingDelete && (
        <ConfirmDialog
          title="Delete category"
          message={`Delete category "${pendingDelete.name}"?`}
          confirmLabel="Delete"
          onConfirm={confirmDelete}
          onCancel={() => setPendingDelete(null)}
        />
      )}
    </div>
  );
}

function CategoryRow({
  category, indented = false, onEdit, onDelete,
}: {
  category: Category;
  indented?: boolean;
  onEdit: (c: Category) => void;
  onDelete: (c: Category) => void;
}) {
  return (
    <tr className="border-b border-neutral-100 last:border-0 hover:bg-neutral-50">
      <td className="py-2 pr-4">
        <div className={indented ? "flex items-center gap-1.5 pl-6" : "flex items-center gap-1.5"}>
          {indented && <span className="text-neutral-300">↳</span>}
          <span
            className="inline-flex items-center gap-1.5 rounded-full px-2 py-0.5 text-xs font-medium text-white"
            style={{ backgroundColor: category.color }}
          >
            {category.icon && <span>{category.icon}</span>}
            {category.name}
          </span>
          {category.isInternal && (
            <span className="rounded-full bg-neutral-200 px-2 py-0.5 text-xs font-medium text-neutral-600">
              excluded from totals
            </span>
          )}
        </div>
      </td>
      <td className="py-2 text-right">
        <div className="flex justify-end gap-2">
          <button
            onClick={() => onEdit(category)}
            className="rounded-md border border-neutral-300 bg-white px-2.5 py-1 text-xs font-medium text-neutral-700 hover:bg-neutral-50"
          >
            Edit
          </button>
          <button
            onClick={() => onDelete(category)}
            className="rounded-md border border-red-200 bg-white px-2.5 py-1 text-xs font-medium text-red-600 hover:bg-red-50"
          >
            Delete
          </button>
        </div>
      </td>
    </tr>
  );
}
