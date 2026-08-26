import { useState } from "react";
import type { Category, CategoryRuleForm, RecategorizeScope } from "../api/client";
import { RuleFields } from "./RuleFields";

interface Props {
  categories: Category[];
  /** Rule as the transaction implies it — the starting point, not the verdict. */
  initial: CategoryRuleForm;
  categoryName: string;
  onConfirm: (form: CategoryRuleForm, scope: RecategorizeScope) => void;
  onSkip: () => void;
}

type Step = "ask" | "rule";

export function CategoryRuleModal({ categories, initial, categoryName, onConfirm, onSkip }: Props) {
  const [step, setStep] = useState<Step>("ask");
  const [scope, setScope] = useState<RecategorizeScope>("UNCATEGORIZED_ONLY");
  const [form, setForm] = useState<CategoryRuleForm>(initial);

  function set<K extends keyof CategoryRuleForm>(k: K, v: CategoryRuleForm[K]) {
    setForm((f) => ({ ...f, [k]: v }));
  }

  function handleSubmit(e: React.FormEvent) {
    e.preventDefault();
    onConfirm(form, scope);
  }

  return (
    <div className="fixed inset-0 z-50 flex items-center justify-center bg-black/40" onClick={onSkip}>
      <div
        className="max-h-[90vh] w-full max-w-md overflow-y-auto rounded-xl bg-white p-6 shadow-xl"
        onClick={(e) => e.stopPropagation()}
      >
        {step === "ask" ? (
          <>
            <h2 className="mb-2 text-lg font-semibold text-neutral-900">Create a rule?</h2>
            <p className="mb-5 text-sm text-neutral-600">
              Categorized as <span className="font-medium text-neutral-900">{categoryName}</span>. Always
              categorize <span className="font-medium text-neutral-900">"{initial.pattern}"</span> this way in
              future imports?
            </p>
            <div className="flex justify-end gap-3">
              <button
                onClick={onSkip}
                className="rounded-md border border-neutral-300 px-4 py-2 text-sm font-medium text-neutral-700 hover:bg-neutral-50"
              >
                Just this one
              </button>
              <button
                onClick={() => setStep("rule")}
                className="rounded-md bg-primary-600 px-4 py-2 text-sm font-medium text-white hover:bg-primary-700"
              >
                Yes, create rule
              </button>
            </div>
          </>
        ) : (
          <>
            <h2 className="mb-1 text-lg font-semibold text-neutral-900">New rule</h2>
            {/* The guessed pattern is this one payee's full name, which is
                usually narrower than the rule wants to be — "Bonus Selfoss"
                where one "Bonus" would cover the whole chain. Editable here so
                broadening it doesn't mean a second trip to the Rules page. */}
            <p className="mb-4 text-sm text-neutral-600">
              Widen the pattern if it should catch more than this one payee.
            </p>
            <form onSubmit={handleSubmit} className="space-y-3">
              <RuleFields form={form} categories={categories} onChange={set} />

              <div className="pt-1">
                <p className="mb-1 text-xs font-medium text-neutral-600">Existing transactions</p>
                <div className="space-y-2">
                  <ScopeOption
                    value="NONE"
                    current={scope}
                    onChange={setScope}
                    label="Don't touch existing transactions"
                    hint="Only future imports will be affected."
                  />
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
                    hint="Including ones you already categorized differently — they'll be overwritten."
                  />
                </div>
              </div>

              <div className="flex justify-end gap-3 pt-2">
                <button
                  type="button"
                  onClick={onSkip}
                  className="rounded-md border border-neutral-300 px-4 py-2 text-sm font-medium text-neutral-700 hover:bg-neutral-50"
                >
                  Cancel
                </button>
                <button
                  type="submit"
                  className="rounded-md bg-primary-600 px-4 py-2 text-sm font-medium text-white hover:bg-primary-700"
                >
                  Create rule
                </button>
              </div>
            </form>
          </>
        )}
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
      <input
        type="radio"
        checked={current === value}
        onChange={() => onChange(value)}
        className="mt-0.5"
      />
      <span>
        <span className="block text-sm font-medium text-neutral-900">{label}</span>
        <span className="block text-xs text-neutral-500">{hint}</span>
      </span>
    </label>
  );
}
