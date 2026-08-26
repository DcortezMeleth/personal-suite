import { useCallback, useEffect, useState } from "react";
import { DataCard, AlertBanner, ConfirmDialog } from "@delfin/ui";
import { RuleFormModal } from "../components/RuleFormModal";
import { ReapplyRuleModal } from "../components/ReapplyRuleModal";
import { CategoryFilterOptions } from "../components/CategoryFilterOptions";
import {
  api,
  Category,
  CategoryRule,
  CategoryRuleForm,
  ReapplyResult,
  RecategorizeScope,
  RuleDirection,
  RuleMatchType,
} from "../api/client";

const DIRECTION_LABEL: Record<string, string> = {
  ANY: "Any",
  EXPENSE: "Outgoing only",
  INCOME: "Incoming only",
};

const MATCH_TYPE_LABEL: Record<string, string> = {
  CONTAINS: "Contains",
  EXACT: "Exact",
  REGEX: "Regex",
};

export function RulesPage() {
  const [categories, setCategories] = useState<Category[]>([]);
  const [rules, setRules] = useState<CategoryRule[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [statusMsg, setStatusMsg] = useState<string | null>(null);

  const [showForm, setShowForm] = useState(false);
  const [editingRule, setEditingRule] = useState<CategoryRule | null>(null);
  const [reapplyingRule, setReapplyingRule] = useState<CategoryRule | null>(null);
  const [pendingDelete, setPendingDelete] = useState<CategoryRule | null>(null);

  const [filterCategoryId, setFilterCategoryId] = useState("");
  const [filterMatchType, setFilterMatchType] = useState<RuleMatchType | "">("");
  const [filterDirection, setFilterDirection] = useState<RuleDirection | "">("");
  const [filterSearch, setFilterSearch] = useState("");

  const load = useCallback(async () => {
    setLoading(true);
    setError(null);
    try {
      const [cats, ruleList] = await Promise.all([
        api.get<Category[]>("/categories"),
        api.get<CategoryRule[]>("/category-rules"),
      ]);
      setCategories(cats);
      setRules(ruleList);
    } catch (e) {
      setError(e instanceof Error ? e.message : "Failed to load rules");
    } finally {
      setLoading(false);
    }
  }, []);

  useEffect(() => { load(); }, [load]);

  const categoryById = new Map(categories.map((c) => [c.id, c]));

  // Picking a parent category (e.g. "Car") also matches rules on its
  // children (VW/Audi) — consistent with how the transactions filter and
  // dashboard rollup treat a parent as covering everything under it.
  const filteredRules = rules.filter((rule) => {
    if (filterCategoryId) {
      const cat = categoryById.get(rule.categoryId);
      if (rule.categoryId !== filterCategoryId && cat?.parentId !== filterCategoryId) return false;
    }
    if (filterMatchType && rule.matchType !== filterMatchType) return false;
    if (filterDirection && rule.direction !== filterDirection) return false;
    if (filterSearch.trim() && !rule.pattern.toLowerCase().includes(filterSearch.trim().toLowerCase())) return false;
    return true;
  });

  async function handleCreate(form: CategoryRuleForm) {
    try {
      await api.post("/category-rules", form);
      setShowForm(false);
      setStatusMsg("Rule created. It applies to future imports — use “Reapply” to also touch existing transactions.");
      load();
    } catch (e) {
      setError(e instanceof Error ? e.message : "Failed to create rule");
    }
  }

  async function handleUpdate(form: CategoryRuleForm) {
    if (!editingRule) return;
    try {
      const updated = await api.put<CategoryRule>(`/category-rules/${editingRule.id}`, form);
      setEditingRule(null);
      setStatusMsg("Rule updated — existing transactions unchanged so far.");
      // Straight on into the reapply prompt with the rule as just saved: an edit
      // is usually made *because* the rule should now catch something it didn't,
      // and the alternative was hunting the row down again in a list this long
      // only to press its Reapply button. Cancelling leaves the message above
      // standing, so declining is still a clear "saved, nothing else touched".
      setReapplyingRule(updated);
      load();
    } catch (e) {
      setError(e instanceof Error ? e.message : "Failed to update rule");
    }
  }

  async function confirmDelete() {
    if (!pendingDelete) return;
    const rule = pendingDelete;
    setPendingDelete(null);
    try {
      await api.del(`/category-rules/${rule.id}`);
      load();
    } catch (e) {
      setError(e instanceof Error ? e.message : "Failed to delete rule");
    }
  }

  async function handleReapply(scope: RecategorizeScope) {
    if (!reapplyingRule) return;
    try {
      const res = await api.post<ReapplyResult>(`/category-rules/${reapplyingRule.id}/reapply`, { scope });
      setStatusMsg(`Done — ${res.affected} transaction${res.affected === 1 ? "" : "s"} updated.`);
      setReapplyingRule(null);
    } catch (e) {
      setError(e instanceof Error ? e.message : "Failed to reapply rule");
    }
  }

  return (
    <div className="space-y-6">
      <div className="flex items-center justify-between">
        <h1 className="text-2xl font-bold text-neutral-900">Category Rules</h1>
        <button
          onClick={() => setShowForm(true)}
          className="rounded-md bg-primary-600 px-4 py-2 text-sm font-medium text-white hover:bg-primary-700"
        >
          + New Rule
        </button>
      </div>

      {error && <AlertBanner level="danger" message={error} onDismiss={() => setError(null)} />}
      {statusMsg && <AlertBanner level="success" message={statusMsg} onDismiss={() => setStatusMsg(null)} />}

      <DataCard title="Filters">
        <div className="grid grid-cols-2 gap-3 sm:grid-cols-4">
          <Field label="Category">
            <select value={filterCategoryId} onChange={(e) => setFilterCategoryId(e.target.value)} className={inputCls}>
              <option value="">All</option>
              <CategoryFilterOptions categories={categories} />
            </select>
          </Field>
          <Field label="Match type">
            <select value={filterMatchType} onChange={(e) => setFilterMatchType(e.target.value as RuleMatchType | "")} className={inputCls}>
              <option value="">All</option>
              <option value="CONTAINS">Contains</option>
              <option value="EXACT">Exact</option>
              <option value="REGEX">Regex</option>
            </select>
          </Field>
          <Field label="Direction">
            <select value={filterDirection} onChange={(e) => setFilterDirection(e.target.value as RuleDirection | "")} className={inputCls}>
              <option value="">All</option>
              <option value="ANY">Any</option>
              <option value="EXPENSE">Outgoing only</option>
              <option value="INCOME">Incoming only</option>
            </select>
          </Field>
          <Field label="Pattern search">
            <input
              type="text"
              placeholder="e.g. LIDL"
              value={filterSearch}
              onChange={(e) => setFilterSearch(e.target.value)}
              className={inputCls}
            />
          </Field>
        </div>
      </DataCard>

      <DataCard title={`${filteredRules.length} of ${rules.length} rules`}>
        {/* Only stand in for the table on the very first load. Swapping a long
            table for a one-line placeholder on every refresh collapsed the page
            height, so the browser clamped the scroll position to the top and
            saving an edit dumped you back at the header. */}
        {loading && rules.length === 0 ? (
          <p className="py-6 text-center text-sm text-neutral-500">Loading…</p>
        ) : rules.length === 0 ? (
          <p className="py-6 text-center text-sm text-neutral-500">No rules yet.</p>
        ) : filteredRules.length === 0 ? (
          <p className="py-6 text-center text-sm text-neutral-500">No rules match these filters.</p>
        ) : (
          <div className="overflow-x-auto">
            <table className="w-full text-sm">
              <thead>
                <tr className="border-b border-neutral-200 text-left text-xs font-semibold uppercase tracking-wide text-neutral-500">
                  <th className="py-2 pr-4">Priority</th>
                  <th className="py-2 pr-4">Pattern</th>
                  <th className="py-2 pr-4">Match type</th>
                  <th className="py-2 pr-4">Direction</th>
                  <th className="py-2 pr-4">Category</th>
                  <th className="py-2 text-right">Actions</th>
                </tr>
              </thead>
              <tbody>
                {filteredRules.map((rule) => {
                  const category = categoryById.get(rule.categoryId);
                  return (
                    <tr key={rule.id} className="border-b border-neutral-100 last:border-0 hover:bg-neutral-50">
                      <td className="py-2 pr-4 text-neutral-500">{rule.priority}</td>
                      <td className="py-2 pr-4 font-mono text-neutral-900">{rule.pattern}</td>
                      <td className="py-2 pr-4 text-neutral-500">{MATCH_TYPE_LABEL[rule.matchType]}</td>
                      <td className="py-2 pr-4 text-neutral-500">{DIRECTION_LABEL[rule.direction]}</td>
                      <td className="py-2 pr-4">
                        {category ? (
                          <span
                            className="inline-flex items-center gap-1 rounded-full px-2 py-0.5 text-xs font-medium text-white"
                            style={{ backgroundColor: category.color }}
                          >
                            {category.icon && <span>{category.icon}</span>}
                            {category.name}
                          </span>
                        ) : (
                          <span className="text-neutral-400">—</span>
                        )}
                      </td>
                      <td className="py-2 text-right">
                        <div className="flex justify-end gap-2">
                          <button
                            onClick={() => setReapplyingRule(rule)}
                            className="rounded-md border border-neutral-300 bg-white px-2.5 py-1 text-xs font-medium text-neutral-700 hover:bg-neutral-50"
                          >
                            Reapply
                          </button>
                          <button
                            onClick={() => setEditingRule(rule)}
                            className="rounded-md border border-neutral-300 bg-white px-2.5 py-1 text-xs font-medium text-neutral-700 hover:bg-neutral-50"
                          >
                            Edit
                          </button>
                          <button
                            onClick={() => setPendingDelete(rule)}
                            className="rounded-md border border-red-200 bg-white px-2.5 py-1 text-xs font-medium text-red-600 hover:bg-red-50"
                          >
                            Delete
                          </button>
                        </div>
                      </td>
                    </tr>
                  );
                })}
              </tbody>
            </table>
          </div>
        )}
      </DataCard>

      {showForm && (
        <RuleFormModal categories={categories} onSave={handleCreate} onClose={() => setShowForm(false)} />
      )}
      {editingRule && (
        <RuleFormModal
          categories={categories}
          initial={editingRule}
          onSave={handleUpdate}
          onClose={() => setEditingRule(null)}
        />
      )}
      {reapplyingRule && (
        <ReapplyRuleModal
          ruleLabel={`"${reapplyingRule.pattern}" → ${categoryById.get(reapplyingRule.categoryId)?.name ?? "?"}`}
          onConfirm={handleReapply}
          onClose={() => setReapplyingRule(null)}
        />
      )}
      {pendingDelete && (
        <ConfirmDialog
          title="Delete rule"
          message={`Delete rule "${pendingDelete.pattern}" → ${categoryById.get(pendingDelete.categoryId)?.name ?? "?"}?`}
          confirmLabel="Delete"
          onConfirm={confirmDelete}
          onCancel={() => setPendingDelete(null)}
        />
      )}
    </div>
  );
}

const inputCls = "w-full rounded-md border border-neutral-300 bg-white px-3 py-1.5 text-sm focus:outline-none focus:ring-2 focus:ring-primary-500";

function Field({ label, children }: { label: string; children: React.ReactNode }) {
  return (
    <div>
      <label className="mb-1 block text-xs font-medium text-neutral-600">{label}</label>
      {children}
    </div>
  );
}
