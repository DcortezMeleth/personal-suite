import { useCallback, useEffect, useState } from "react";
import { DataCard, AlertBanner } from "@delfin/ui";
import { CategoryRuleModal } from "../components/CategoryRuleModal";
import { api, Category, CreateRuleResult, RecategorizeScope, TransactionSearchResult } from "../api/client";

function fmt(amount: number) {
  return amount.toLocaleString("pl-PL", { style: "currency", currency: "PLN" });
}

type SortBy = "date" | "amount";
type SortDir = "asc" | "desc";

interface PendingRule {
  txId: string;
  pattern: string;
  categoryId: string;
  categoryName: string;
}

const PAGE_SIZE = 50;

export function TransactionsPage() {
  const [categories, setCategories] = useState<Category[]>([]);
  const [result, setResult] = useState<TransactionSearchResult | null>(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [pendingRule, setPendingRule] = useState<PendingRule | null>(null);
  const [ruleMsg, setRuleMsg] = useState<string | null>(null);

  const [dateFrom, setDateFrom] = useState("");
  const [dateTo, setDateTo] = useState("");
  const [categoryId, setCategoryId] = useState("");
  const [search, setSearch] = useState("");
  const [minAmount, setMinAmount] = useState("");
  const [maxAmount, setMaxAmount] = useState("");
  const [sortBy, setSortBy] = useState<SortBy>("date");
  const [sortDir, setSortDir] = useState<SortDir>("desc");
  const [page, setPage] = useState(0);

  useEffect(() => {
    api.get<Category[]>("/categories").then(setCategories).catch(() => {});
  }, []);

  useEffect(() => { setPage(0); }, [dateFrom, dateTo, categoryId, search, minAmount, maxAmount, sortBy, sortDir]);

  const load = useCallback(async () => {
    setLoading(true);
    setError(null);
    try {
      const params = new URLSearchParams();
      if (dateFrom) params.set("dateFrom", dateFrom);
      if (dateTo) params.set("dateTo", dateTo);
      if (categoryId) params.set("categoryId", categoryId);
      if (search.trim()) params.set("search", search.trim());
      if (minAmount) params.set("minAmount", minAmount);
      if (maxAmount) params.set("maxAmount", maxAmount);
      params.set("sortBy", sortBy);
      params.set("sortDir", sortDir);
      params.set("page", String(page));
      params.set("pageSize", String(PAGE_SIZE));
      const res = await api.get<TransactionSearchResult>(`/transactions/search?${params.toString()}`);
      setResult(res);
    } catch (e) {
      setError(e instanceof Error ? e.message : "Failed to load transactions");
    } finally {
      setLoading(false);
    }
  }, [dateFrom, dateTo, categoryId, search, minAmount, maxAmount, sortBy, sortDir, page]);

  useEffect(() => {
    const t = setTimeout(load, 300);
    return () => clearTimeout(t);
  }, [load]);

  // Sets the category for this one transaction only — no side effects. Whether to
  // also turn it into a standing rule is a separate, explicit choice (see below):
  // a correction might just be a one-off exception (e.g. Lidl while on holiday),
  // not "always categorize Lidl this way".
  async function handleCategoryChange(txId: string, newCategoryId: string) {
    if (!result || !newCategoryId) return; // clearing a category isn't supported by the API yet
    try {
      await api.patch(`/transactions/${txId}/category`, { categoryId: newCategoryId });
      const category = categories.find((c) => c.id === newCategoryId);
      setResult({
        ...result,
        items: result.items.map((tx) =>
          tx.id === txId
            ? { ...tx, categoryId: newCategoryId, categoryName: category?.name ?? null, categoryColor: category?.color ?? null }
            : tx
        ),
      });
      const tx = result.items.find((t) => t.id === txId);
      const pattern = tx?.counterparty ?? tx?.title;
      if (category && pattern) {
        setRuleMsg(null);
        setPendingRule({ txId, pattern, categoryId: newCategoryId, categoryName: category.name });
      }
    } catch (e) {
      setError(e instanceof Error ? e.message : "Failed to update category");
    }
  }

  async function handleNotesChange(txId: string, notes: string) {
    if (!result) return;
    const trimmed = notes.trim();
    const prev = result.items.find((t) => t.id === txId)?.notes ?? null;
    if (trimmed === (prev ?? "")) return; // no change, skip the request
    try {
      await api.patch(`/transactions/${txId}/notes`, { notes: trimmed || null });
      setResult({
        ...result,
        items: result.items.map((tx) => (tx.id === txId ? { ...tx, notes: trimmed || null } : tx)),
      });
    } catch (e) {
      setError(e instanceof Error ? e.message : "Failed to save note");
    }
  }

  async function handleCreateRule(scope: RecategorizeScope) {
    if (!pendingRule) return;
    try {
      const res = await api.post<CreateRuleResult>(`/transactions/${pendingRule.txId}/category-rule`, {
        categoryId: pendingRule.categoryId,
        scope,
      });
      setRuleMsg(
        res.ruleCreated
          ? `Done — "${res.rulePattern}" will always be categorized as "${pendingRule.categoryName}" now` +
            (res.affected > 0 ? ` (${res.affected} other existing transaction${res.affected === 1 ? "" : "s"} updated too).` : ".")
          : `A rule for "${pendingRule.pattern}" → "${pendingRule.categoryName}" already exists.`
      );
      setPendingRule(null);
      load();
    } catch (e) {
      setError(e instanceof Error ? e.message : "Failed to create rule");
    }
  }

  function toggleSort(col: SortBy) {
    if (sortBy === col) {
      setSortDir((d) => (d === "asc" ? "desc" : "asc"));
    } else {
      setSortBy(col);
      setSortDir(col === "amount" ? "asc" : "desc");
    }
  }

  const totalPages = result ? Math.max(1, Math.ceil(result.total / PAGE_SIZE)) : 1;

  return (
    <div className="space-y-6">
      <h1 className="text-2xl font-bold text-neutral-900">Transactions</h1>

      {error && <AlertBanner level="danger" message={error} />}
      {ruleMsg && <AlertBanner level="success" message={ruleMsg} onDismiss={() => setRuleMsg(null)} />}
      {pendingRule && (
        <CategoryRuleModal
          pattern={pendingRule.pattern}
          categoryName={pendingRule.categoryName}
          onConfirm={handleCreateRule}
          onSkip={() => setPendingRule(null)}
        />
      )}

      <DataCard title="Filters">
        <div className="grid grid-cols-2 gap-3 sm:grid-cols-3 lg:grid-cols-6">
          <Field label="From">
            <input type="date" value={dateFrom} onChange={(e) => setDateFrom(e.target.value)} className={inputCls} />
          </Field>
          <Field label="To">
            <input type="date" value={dateTo} onChange={(e) => setDateTo(e.target.value)} className={inputCls} />
          </Field>
          <Field label="Category">
            <select value={categoryId} onChange={(e) => setCategoryId(e.target.value)} className={inputCls}>
              <option value="">All</option>
              {categories.map((c) => (
                <option key={c.id} value={c.id}>{c.name}</option>
              ))}
            </select>
          </Field>
          <Field label="Search">
            <input
              type="text"
              placeholder="Title, counterparty, notes…"
              value={search}
              onChange={(e) => setSearch(e.target.value)}
              className={inputCls}
            />
          </Field>
          <Field label="Min amount">
            <input type="number" step="0.01" value={minAmount} onChange={(e) => setMinAmount(e.target.value)} className={inputCls} />
          </Field>
          <Field label="Max amount">
            <input type="number" step="0.01" value={maxAmount} onChange={(e) => setMaxAmount(e.target.value)} className={inputCls} />
          </Field>
        </div>
      </DataCard>

      <DataCard title={result ? `${result.total} transactions` : "Transactions"}>
        {loading && !result ? (
          <p className="py-6 text-center text-sm text-neutral-500">Loading…</p>
        ) : result && result.items.length > 0 ? (
          <>
            <div className="overflow-x-auto">
              <table className="w-full text-sm">
                <thead>
                  <tr className="border-b border-neutral-200 text-left text-xs font-semibold uppercase tracking-wide text-neutral-500">
                    <SortableHeader label="Date" col="date" sortBy={sortBy} sortDir={sortDir} onClick={toggleSort} />
                    <th className="py-2 pr-4">Title</th>
                    <th className="py-2 pr-4">Counterparty</th>
                    <th className="py-2 pr-4">Account</th>
                    <th className="py-2 pr-4">Notes</th>
                    <th className="py-2 pr-4">Category</th>
                    <SortableHeader label="Amount" col="amount" sortBy={sortBy} sortDir={sortDir} onClick={toggleSort} align="right" />
                  </tr>
                </thead>
                <tbody>
                  {result.items.map((tx) => (
                    <tr key={tx.id} className="border-b border-neutral-100 last:border-0 hover:bg-neutral-50">
                      <td className="py-2 pr-4 text-neutral-500">{tx.date}</td>
                      <td className="py-2 pr-4 text-neutral-900 max-w-xs truncate" title={tx.title}>
                        {tx.title}
                      </td>
                      <td className="py-2 pr-4 text-neutral-500 max-w-xs truncate" title={tx.counterparty ?? undefined}>
                        {tx.counterparty ?? <span className="text-neutral-300">—</span>}
                      </td>
                      <td className="py-2 pr-4 text-neutral-500">{tx.accountName}</td>
                      <td className="py-2 pr-4">
                        <NotesCell value={tx.notes} onSave={(notes) => handleNotesChange(tx.id, notes)} />
                      </td>
                      <td className="py-2 pr-4">
                        <select
                          value={tx.categoryId ?? ""}
                          onChange={(e) => handleCategoryChange(tx.id, e.target.value)}
                          className="rounded-md border border-transparent bg-transparent px-1.5 py-0.5 text-xs font-medium hover:border-neutral-300 focus:outline-none focus:ring-2 focus:ring-primary-500"
                          style={tx.categoryColor ? { color: tx.categoryColor } : undefined}
                        >
                          <option value="">Uncategorized</option>
                          {categories.map((c) => (
                            <option key={c.id} value={c.id}>{c.name}</option>
                          ))}
                        </select>
                      </td>
                      <td className={`py-2 text-right font-medium ${tx.amount < 0 ? "text-red-600" : "text-green-600"}`}>
                        {fmt(tx.amount)}
                      </td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>

            <div className="mt-4 flex items-center justify-between text-sm text-neutral-600">
              <span>Page {page + 1} of {totalPages}</span>
              <div className="flex gap-2">
                <button
                  onClick={() => setPage((p) => Math.max(0, p - 1))}
                  disabled={page === 0}
                  className={pagerBtnCls}
                >
                  Prev
                </button>
                <button
                  onClick={() => setPage((p) => Math.min(totalPages - 1, p + 1))}
                  disabled={page >= totalPages - 1}
                  className={pagerBtnCls}
                >
                  Next
                </button>
              </div>
            </div>
          </>
        ) : (
          <p className="py-6 text-center text-sm text-neutral-500">No transactions match these filters.</p>
        )}
      </DataCard>
    </div>
  );
}

const inputCls = "w-full rounded-md border border-neutral-300 bg-white px-3 py-1.5 text-sm focus:outline-none focus:ring-2 focus:ring-primary-500";
const pagerBtnCls = "rounded-md border border-neutral-300 bg-white px-3 py-1.5 text-sm font-medium text-neutral-700 hover:bg-neutral-50 disabled:opacity-50 disabled:cursor-not-allowed";

function Field({ label, children }: { label: string; children: React.ReactNode }) {
  return (
    <div>
      <label className="mb-1 block text-xs font-medium text-neutral-600">{label}</label>
      {children}
    </div>
  );
}

function NotesCell({ value, onSave }: { value: string | null; onSave: (notes: string) => void }) {
  const [editing, setEditing] = useState(false);
  const [draft, setDraft] = useState(value ?? "");

  useEffect(() => {
    if (!editing) setDraft(value ?? "");
  }, [value, editing]);

  function commit() {
    setEditing(false);
    onSave(draft);
  }

  if (!editing) {
    return (
      <button
        type="button"
        onClick={() => setEditing(true)}
        className="max-w-[12rem] truncate rounded-md border border-transparent px-1.5 py-0.5 text-left text-neutral-500 hover:border-neutral-300 hover:bg-neutral-50 focus:outline-none focus:ring-2 focus:ring-primary-500"
        title={value ?? "Add a note"}
      >
        {value || <span className="text-neutral-300">+ add note</span>}
      </button>
    );
  }

  return (
    <input
      type="text"
      autoFocus
      value={draft}
      onChange={(e) => setDraft(e.target.value)}
      onBlur={commit}
      onKeyDown={(e) => {
        if (e.key === "Enter") e.currentTarget.blur();
        if (e.key === "Escape") { setDraft(value ?? ""); setEditing(false); }
      }}
      placeholder="invoice #, what was bought…"
      className="w-40 rounded-md border border-neutral-300 bg-white px-1.5 py-0.5 text-xs focus:outline-none focus:ring-2 focus:ring-primary-500"
    />
  );
}

function SortableHeader({
  label, col, sortBy, sortDir, onClick, align = "left",
}: {
  label: string;
  col: SortBy;
  sortBy: SortBy;
  sortDir: SortDir;
  onClick: (c: SortBy) => void;
  align?: "left" | "right";
}) {
  const active = sortBy === col;
  return (
    <th
      className={`cursor-pointer select-none py-2 pr-4 ${align === "right" ? "text-right" : ""}`}
      onClick={() => onClick(col)}
    >
      {label}{active ? (sortDir === "asc" ? " ▲" : " ▼") : ""}
    </th>
  );
}
