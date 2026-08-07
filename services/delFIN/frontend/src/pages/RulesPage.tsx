import { useCallback, useEffect, useState } from "react";
import { DataCard, AlertBanner } from "@delfin/ui";
import { RuleFormModal } from "../components/RuleFormModal";
import { ReapplyRuleModal } from "../components/ReapplyRuleModal";
import {
  api,
  Category,
  CategoryRule,
  CategoryRuleForm,
  ReapplyResult,
  RecategorizeScope,
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
      await api.put(`/category-rules/${editingRule.id}`, form);
      setEditingRule(null);
      setStatusMsg("Rule updated. This didn't touch any existing transactions — use “Reapply” if you want that.");
      load();
    } catch (e) {
      setError(e instanceof Error ? e.message : "Failed to update rule");
    }
  }

  async function handleDelete(rule: CategoryRule) {
    const category = categoryById.get(rule.categoryId);
    if (!window.confirm(`Delete rule "${rule.pattern}" → ${category?.name ?? "?"}?`)) return;
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

      <DataCard title={`${rules.length} rules`}>
        {loading ? (
          <p className="py-6 text-center text-sm text-neutral-500">Loading…</p>
        ) : rules.length === 0 ? (
          <p className="py-6 text-center text-sm text-neutral-500">No rules yet.</p>
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
                {rules.map((rule) => {
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
                            onClick={() => handleDelete(rule)}
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
    </div>
  );
}
