import { useState } from "react";
import type { Category, CategoryForm } from "../api/client";
import { EmojiInput } from "./EmojiInput";

interface Props {
  categories: Category[];
  initial?: Category;
  onSave: (form: CategoryForm) => void;
  onClose: () => void;
}

export function CategoryFormModal({ categories, initial, onSave, onClose }: Props) {
  const [form, setForm] = useState<CategoryForm>({
    name: initial?.name ?? "",
    color: initial?.color ?? "#64748b",
    icon: initial?.icon ?? "",
    parentId: initial?.parentId ?? "",
  });

  function set<K extends keyof CategoryForm>(k: K, v: CategoryForm[K]) {
    setForm((f) => ({ ...f, [k]: v }));
  }

  function handleSubmit(e: React.FormEvent) {
    e.preventDefault();
    onSave({ ...form, icon: form.icon?.trim() || null, parentId: form.parentId || null });
  }

  // A category can't be its own parent, and (to keep this simple) can't be
  // set as a parent of one of its own children either — not worth a full
  // cycle check for a couple of nesting levels of personal-finance categories.
  const parentOptions = categories.filter((c) => c.id !== initial?.id);

  return (
    <div className="fixed inset-0 z-50 flex items-center justify-center bg-black/40" onClick={onClose}>
      <div className="w-full max-w-md rounded-xl bg-white p-6 shadow-xl" onClick={(e) => e.stopPropagation()}>
        <h2 className="mb-4 text-lg font-semibold text-neutral-900">{initial ? "Edit Category" : "New Category"}</h2>
        <form onSubmit={handleSubmit} className="space-y-3">
          <Field label="Name">
            <input
              required
              type="text"
              value={form.name}
              onChange={(e) => set("name", e.target.value)}
              className={inputCls}
              placeholder="e.g. Savings - IKE"
            />
          </Field>

          <div className="grid grid-cols-2 gap-3">
            <Field label="Color">
              <div className="flex items-center gap-2">
                <input
                  type="color"
                  value={form.color}
                  onChange={(e) => set("color", e.target.value)}
                  className="h-8 w-10 shrink-0 cursor-pointer rounded border border-neutral-300"
                />
                <input
                  type="text"
                  value={form.color}
                  onChange={(e) => set("color", e.target.value)}
                  className={inputCls}
                />
              </div>
            </Field>
            <Field label="Icon (emoji)">
              <EmojiInput
                value={form.icon ?? ""}
                onChange={(v) => set("icon", v)}
                className={inputCls}
                placeholder="🏦"
              />
            </Field>
          </div>

          <Field label="Parent category (optional)">
            <select value={form.parentId ?? ""} onChange={(e) => set("parentId", e.target.value)} className={inputCls}>
              <option value="">None</option>
              {parentOptions.map((c) => (
                <option key={c.id} value={c.id}>{c.name}</option>
              ))}
            </select>
          </Field>

          <div className="flex justify-end gap-3 pt-2">
            <button
              type="button"
              onClick={onClose}
              className="rounded-md border border-neutral-300 px-4 py-2 text-sm font-medium text-neutral-700 hover:bg-neutral-50"
            >
              Cancel
            </button>
            <button
              type="submit"
              className="rounded-md bg-primary-600 px-4 py-2 text-sm font-medium text-white hover:bg-primary-700"
            >
              {initial ? "Save" : "Create"}
            </button>
          </div>
        </form>
      </div>
    </div>
  );
}

const inputCls = "w-full rounded-md border border-neutral-300 px-3 py-1.5 text-sm focus:outline-none focus:ring-2 focus:ring-primary-500";

function Field({ label, children }: { label: string; children: React.ReactNode }) {
  return (
    <div>
      <label className="mb-1 block text-xs font-medium text-neutral-600">{label}</label>
      {children}
    </div>
  );
}
