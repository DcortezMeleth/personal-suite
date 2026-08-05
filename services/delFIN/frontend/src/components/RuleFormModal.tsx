import { useState } from "react";
import type { Category, CategoryRule, CategoryRuleForm, RuleDirection, RuleMatchType } from "../api/client";

interface Props {
  categories: Category[];
  initial?: CategoryRule;
  onSave: (form: CategoryRuleForm) => void;
  onClose: () => void;
}

export function RuleFormModal({ categories, initial, onSave, onClose }: Props) {
  const [form, setForm] = useState<CategoryRuleForm>({
    categoryId: initial?.categoryId ?? categories[0]?.id ?? "",
    pattern: initial?.pattern ?? "",
    matchType: initial?.matchType ?? "CONTAINS",
    priority: initial?.priority ?? 10,
    direction: initial?.direction ?? "ANY",
  });

  function set<K extends keyof CategoryRuleForm>(k: K, v: CategoryRuleForm[K]) {
    setForm((f) => ({ ...f, [k]: v }));
  }

  function handleSubmit(e: React.FormEvent) {
    e.preventDefault();
    onSave(form);
  }

  return (
    <div className="fixed inset-0 z-50 flex items-center justify-center bg-black/40" onClick={onClose}>
      <div className="w-full max-w-md rounded-xl bg-white p-6 shadow-xl" onClick={(e) => e.stopPropagation()}>
        <h2 className="mb-4 text-lg font-semibold text-neutral-900">{initial ? "Edit Rule" : "New Rule"}</h2>
        <form onSubmit={handleSubmit} className="space-y-3">
          <Field label="Pattern">
            <input
              required
              type="text"
              value={form.pattern}
              onChange={(e) => set("pattern", e.target.value)}
              className={inputCls}
              placeholder="e.g. LIDL"
            />
          </Field>

          <div className="grid grid-cols-2 gap-3">
            <Field label="Match type">
              <select value={form.matchType} onChange={(e) => set("matchType", e.target.value as RuleMatchType)} className={inputCls}>
                <option value="CONTAINS">Contains</option>
                <option value="EXACT">Exact</option>
                <option value="REGEX">Regex</option>
              </select>
            </Field>
            <Field label="Direction">
              <select value={form.direction} onChange={(e) => set("direction", e.target.value as RuleDirection)} className={inputCls}>
                <option value="ANY">Any</option>
                <option value="EXPENSE">Outgoing only</option>
                <option value="INCOME">Incoming only</option>
              </select>
            </Field>
          </div>

          <div className="grid grid-cols-2 gap-3">
            <Field label="Category">
              <select value={form.categoryId} onChange={(e) => set("categoryId", e.target.value)} className={inputCls}>
                {categories.map((c) => (
                  <option key={c.id} value={c.id}>{c.name}</option>
                ))}
              </select>
            </Field>
            <Field label="Priority (lower = checked first)">
              <input
                required
                type="number"
                value={form.priority}
                onChange={(e) => set("priority", Number(e.target.value))}
                className={inputCls}
              />
            </Field>
          </div>

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
