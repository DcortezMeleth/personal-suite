import { useState } from "react";
import { useBackdropDismiss } from "@delfin/ui";
import type { Category, CategoryRule, CategoryRuleForm } from "../api/client";
import { RuleFields } from "./RuleFields";

interface Props {
  categories: Category[];
  initial?: CategoryRule;
  onSave: (form: CategoryRuleForm) => void;
  onClose: () => void;
}

export function RuleFormModal({ categories, initial, onSave, onClose }: Props) {
  // Default to the first assignable category — a category with subcategories
  // is a rollup container and can't be a rule's target.
  const firstAssignable = categories.find((c) => !categories.some((child) => child.parentId === c.id));

  const [form, setForm] = useState<CategoryRuleForm>({
    categoryId: initial?.categoryId ?? firstAssignable?.id ?? "",
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

  const backdrop = useBackdropDismiss(onClose);
  return (
    <div className="fixed inset-0 z-50 flex items-center justify-center bg-black/40" {...backdrop}>
      <div
        className="max-h-[90vh] w-full max-w-md overflow-y-auto rounded-xl bg-white p-6 shadow-xl"
       
      >
        <h2 className="mb-4 text-lg font-semibold text-neutral-900">{initial ? "Edit Rule" : "New Rule"}</h2>
        <form onSubmit={handleSubmit} className="space-y-3">
          <RuleFields form={form} categories={categories} onChange={set} />

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
