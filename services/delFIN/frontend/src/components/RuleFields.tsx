import type { Category, CategoryRuleForm, RuleDirection, RuleMatchType } from "../api/client";
import { CategoryOptionGroups } from "./CategoryOptionGroups";

interface Props {
  form: CategoryRuleForm;
  categories: Category[];
  onChange: <K extends keyof CategoryRuleForm>(key: K, value: CategoryRuleForm[K]) => void;
}

// The editable body of a rule, shared by the Rules page's own form and by the
// prompt that offers a rule after a transaction correction — both need the
// same fields, and the ad-hoc one is only useful if the pattern it guessed can
// be widened on the spot.
export function RuleFields({ form, categories, onChange }: Props) {
  return (
    <>
      <Field label="Pattern">
        <input
          required
          type="text"
          value={form.pattern}
          onChange={(e) => onChange("pattern", e.target.value)}
          className={ruleInputCls}
          placeholder="e.g. LIDL"
        />
      </Field>

      <div className="grid grid-cols-2 gap-3">
        <Field label="Match type">
          <select value={form.matchType} onChange={(e) => onChange("matchType", e.target.value as RuleMatchType)} className={ruleInputCls}>
            <option value="CONTAINS">Contains</option>
            <option value="EXACT">Exact</option>
            <option value="REGEX">Regex</option>
          </select>
        </Field>
        <Field label="Direction">
          <select value={form.direction} onChange={(e) => onChange("direction", e.target.value as RuleDirection)} className={ruleInputCls}>
            <option value="ANY">Any</option>
            <option value="EXPENSE">Outgoing only</option>
            <option value="INCOME">Incoming only</option>
          </select>
        </Field>
      </div>

      <div className="grid grid-cols-2 gap-3">
        <Field label="Category">
          <select value={form.categoryId} onChange={(e) => onChange("categoryId", e.target.value)} className={ruleInputCls}>
            <CategoryOptionGroups categories={categories} currentId={form.categoryId} />
          </select>
        </Field>
        <Field label="Priority (lower = checked first)">
          <input
            required
            type="number"
            value={form.priority}
            onChange={(e) => onChange("priority", Number(e.target.value))}
            className={ruleInputCls}
          />
        </Field>
      </div>
    </>
  );
}

export const ruleInputCls = "w-full rounded-md border border-neutral-300 px-3 py-1.5 text-sm focus:outline-none focus:ring-2 focus:ring-primary-500";

export function Field({ label, children }: { label: string; children: React.ReactNode }) {
  return (
    <div>
      <label className="mb-1 block text-xs font-medium text-neutral-600">{label}</label>
      {children}
    </div>
  );
}
