import { useState } from "react";
import { useBackdropDismiss } from "@delfin/ui";
import type { RecategorizeScope } from "../api/client";

interface Props {
  ruleLabel: string;
  onConfirm: (scope: RecategorizeScope) => void;
  onClose: () => void;
}

export function ReapplyRuleModal({ ruleLabel, onConfirm, onClose }: Props) {
  const [scope, setScope] = useState<RecategorizeScope>("UNCATEGORIZED_ONLY");

  const backdrop = useBackdropDismiss(onClose);
  return (
    <div className="fixed inset-0 z-50 flex items-center justify-center bg-black/40" {...backdrop}>
      <div className="w-full max-w-md rounded-xl bg-white p-6 shadow-xl">
        <h2 className="mb-2 text-lg font-semibold text-neutral-900">Reapply rule</h2>
        <p className="mb-4 text-sm text-neutral-600">
          Apply <span className="font-medium text-neutral-900">{ruleLabel}</span> to already-imported transactions?
        </p>
        <div className="mb-5 space-y-2">
          <ScopeOption
            value="UNCATEGORIZED_ONLY"
            current={scope}
            onChange={setScope}
            label="Only fill in uncategorized ones (recommended)"
            hint="Existing transactions with a category already set are left alone."
          />
          <ScopeOption
            value="ALL"
            current={scope}
            onChange={setScope}
            label="Re-check all matching transactions"
            hint="Including ones already categorized differently — they'll be overwritten."
          />
        </div>
        <div className="flex justify-end gap-3">
          <button
            onClick={onClose}
            className="rounded-md border border-neutral-300 px-4 py-2 text-sm font-medium text-neutral-700 hover:bg-neutral-50"
          >
            Cancel
          </button>
          <button
            onClick={() => onConfirm(scope)}
            className="rounded-md bg-primary-600 px-4 py-2 text-sm font-medium text-white hover:bg-primary-700"
          >
            Apply
          </button>
        </div>
      </div>
    </div>
  );
}

function ScopeOption({
  value, current, onChange, label, hint,
}: {
  value: RecategorizeScope;
  current: RecategorizeScope;
  onChange: (v: RecategorizeScope) => void;
  label: string;
  hint: string;
}) {
  return (
    <label className="flex cursor-pointer items-start gap-2 rounded-md border border-neutral-200 p-2.5 hover:bg-neutral-50">
      <input type="radio" checked={current === value} onChange={() => onChange(value)} className="mt-0.5" />
      <span>
        <span className="block text-sm font-medium text-neutral-900">{label}</span>
        <span className="block text-xs text-neutral-500">{hint}</span>
      </span>
    </label>
  );
}
