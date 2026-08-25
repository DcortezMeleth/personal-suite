import { useCallback, useEffect, useState } from "react";
import { DataCard, AlertBanner, DatePicker } from "@delfin/ui";
import { CategoryRuleModal } from "../components/CategoryRuleModal";
import { CategoryOptionGroups } from "../components/CategoryOptionGroups";
import { CategoryFilterOptions } from "../components/CategoryFilterOptions";
import { api, Account, Category, CreateRuleResult, RecategorizeScope, Tag, TransactionSearchResult } from "../api/client";

function fmt(amount: number) {
  return amount.toLocaleString("pl-PL", { style: "currency", currency: "PLN" });
}

function tagLabel(t: Tag) {
  return t.icon ? `${t.icon} ${t.name}` : t.name;
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

// Sentinel values for the category filter, mirroring CategoryFilter on the
// backend: pick out the rows still waiting to be categorized, or hide them
// once you're only interested in what has already been sorted out.
const UNCATEGORIZED = "none";
const CATEGORIZED = "any";

export function TransactionsPage() {
  const [categories, setCategories] = useState<Category[]>([]);
  const [accounts, setAccounts] = useState<Account[]>([]);
  const [tags, setTags] = useState<Tag[]>([]);
  const [result, setResult] = useState<TransactionSearchResult | null>(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [pendingRule, setPendingRule] = useState<PendingRule | null>(null);
  const [ruleMsg, setRuleMsg] = useState<string | null>(null);

  const [tagMode, setTagMode] = useState(false);
  const [selectedIds, setSelectedIds] = useState<Set<string>>(new Set());
  const [bulkTagId, setBulkTagId] = useState("");

  const [dateFrom, setDateFrom] = useState("");
  const [dateTo, setDateTo] = useState("");
  const [categoryId, setCategoryId] = useState("");
  const [accountId, setAccountId] = useState("");
  const [search, setSearch] = useState("");
  const [minAmount, setMinAmount] = useState("");
  const [maxAmount, setMaxAmount] = useState("");
  const [filterTagIds, setFilterTagIds] = useState<Set<string>>(new Set());
  const [sortBy, setSortBy] = useState<SortBy>("date");
  const [sortDir, setSortDir] = useState<SortDir>("desc");
  const [page, setPage] = useState(0);

  useEffect(() => {
    api.get<Category[]>("/categories").then(setCategories).catch(() => {});
    api.get<Account[]>("/accounts").then(setAccounts).catch(() => {});
    api.get<Tag[]>("/tags").then(setTags).catch(() => {});
  }, []);

  useEffect(() => { setPage(0); }, [dateFrom, dateTo, categoryId, accountId, search, minAmount, maxAmount, filterTagIds, sortBy, sortDir]);

  function toggleFilterTag(id: string) {
    setFilterTagIds((prev) => {
      const next = new Set(prev);
      if (next.has(id)) next.delete(id); else next.add(id);
      return next;
    });
  }

  const load = useCallback(async () => {
    setLoading(true);
    setError(null);
    try {
      const params = new URLSearchParams();
      if (dateFrom) params.set("dateFrom", dateFrom);
      if (dateTo) params.set("dateTo", dateTo);
      if (categoryId) params.set("categoryId", categoryId);
      if (accountId) params.set("accountId", accountId);
      if (search.trim()) params.set("search", search.trim());
      if (minAmount) params.set("minAmount", minAmount);
      if (maxAmount) params.set("maxAmount", maxAmount);
      if (filterTagIds.size > 0) params.set("tagIds", [...filterTagIds].join(","));
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
  }, [dateFrom, dateTo, categoryId, accountId, search, minAmount, maxAmount, filterTagIds, sortBy, sortDir, page]);

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
            ? { ...tx, categoryId: newCategoryId, categoryName: category?.name ?? null, categoryColor: category?.color ?? null, categoryIcon: category?.icon ?? null }
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

  function toggleTagMode() {
    setTagMode((on) => !on);
    setSelectedIds(new Set());
  }

  function toggleRowSelected(id: string) {
    setSelectedIds((prev) => {
      const next = new Set(prev);
      if (next.has(id)) next.delete(id); else next.add(id);
      return next;
    });
  }

  const allVisibleSelected = !!result && result.items.length > 0 && result.items.every((tx) => selectedIds.has(tx.id));

  function toggleSelectAllVisible() {
    if (!result) return;
    setSelectedIds(allVisibleSelected ? new Set() : new Set(result.items.map((tx) => tx.id)));
  }

  async function handleBulkAssignTag() {
    if (!result || !bulkTagId || selectedIds.size === 0) return;
    const tag = tags.find((t) => t.id === bulkTagId);
    if (!tag) return;
    try {
      await api.post("/transactions/tags/bulk-assign", { transactionIds: [...selectedIds], tagId: bulkTagId });
      setResult({
        ...result,
        items: result.items.map((tx) =>
          selectedIds.has(tx.id) && !tx.tags.some((t) => t.id === tag.id)
            ? { ...tx, tags: [...tx.tags, tag] }
            : tx
        ),
      });
      setRuleMsg(`Tagged ${selectedIds.size} transaction${selectedIds.size === 1 ? "" : "s"} with "${tag.name}".`);
      setSelectedIds(new Set());
    } catch (e) {
      setError(e instanceof Error ? e.message : "Failed to assign tag");
    }
  }

  // Single-transaction counterpart to handleBulkAssignTag — for the common
  // case of just tagging one or two rows, going through select mode is
  // overkill.
  async function handleAssignTag(txId: string, tagId: string) {
    if (!result) return;
    const tag = tags.find((t) => t.id === tagId);
    if (!tag) return;
    try {
      await api.post(`/transactions/${txId}/tags`, { tagId });
      setResult({
        ...result,
        items: result.items.map((tx) =>
          tx.id === txId && !tx.tags.some((t) => t.id === tagId) ? { ...tx, tags: [...tx.tags, tag] } : tx
        ),
      });
    } catch (e) {
      setError(e instanceof Error ? e.message : "Failed to assign tag");
    }
  }

  async function handleRemoveTag(txId: string, tagId: string) {
    if (!result) return;
    try {
      await api.del(`/transactions/${txId}/tags/${tagId}`);
      setResult({
        ...result,
        items: result.items.map((tx) =>
          tx.id === txId ? { ...tx, tags: tx.tags.filter((t) => t.id !== tagId) } : tx
        ),
      });
    } catch (e) {
      setError(e instanceof Error ? e.message : "Failed to remove tag");
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
      <div className="flex items-center justify-between">
        <h1 className="text-2xl font-bold text-neutral-900">Transactions</h1>
        <button
          onClick={toggleTagMode}
          className={`rounded-md border px-4 py-2 text-sm font-medium ${
            tagMode
              ? "border-primary-600 bg-primary-600 text-white hover:bg-primary-700"
              : "border-neutral-300 bg-white text-neutral-700 hover:bg-neutral-50"
          }`}
        >
          {tagMode ? "Done selecting" : "Select / Tag"}
        </button>
      </div>

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
        <div className="grid grid-cols-2 gap-3 sm:grid-cols-3 lg:grid-cols-4">
          <Field label="From">
            <DatePicker value={dateFrom} onChange={setDateFrom} />
          </Field>
          <Field label="To">
            <DatePicker value={dateTo} onChange={setDateTo} align="right" />
          </Field>
          <Field label="Category">
            <select value={categoryId} onChange={(e) => setCategoryId(e.target.value)} className={inputCls}>
              <option value="">All</option>
              <option value={UNCATEGORIZED}>Uncategorized only</option>
              <option value={CATEGORIZED}>Categorized only</option>
              <CategoryFilterOptions categories={categories} />
            </select>
          </Field>
          <Field label="Account">
            <select value={accountId} onChange={(e) => setAccountId(e.target.value)} className={inputCls}>
              <option value="">All</option>
              {accounts.map((a) => (
                <option key={a.id} value={a.id}>{a.name}</option>
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
        {tags.length > 0 && (
          <div className="mt-3">
            <label className="mb-1 block text-xs font-medium text-neutral-600">
              Tags {filterTagIds.size > 0 && "(any of)"}
            </label>
            <div className="flex flex-wrap gap-1.5">
              {tags.map((t) => {
                const active = filterTagIds.has(t.id);
                return (
                  <button
                    key={t.id}
                    onClick={() => toggleFilterTag(t.id)}
                    className="rounded-full px-2.5 py-1 text-xs font-medium transition"
                    style={
                      active
                        ? { backgroundColor: t.color, color: "white" }
                        : { backgroundColor: "transparent", color: t.color, border: `1px solid ${t.color}` }
                    }
                  >
                    {tagLabel(t)}
                  </button>
                );
              })}
            </div>
          </div>
        )}
      </DataCard>

      {tagMode && (
        <DataCard title="Bulk tag">
          <div className="flex flex-wrap items-center gap-3">
            <button onClick={toggleSelectAllVisible} className={pagerBtnCls}>
              {allVisibleSelected ? "Unselect all" : `Select all visible (${result?.items.length ?? 0})`}
            </button>
            <span className="text-sm text-neutral-600">{selectedIds.size} selected</span>
            <select value={bulkTagId} onChange={(e) => setBulkTagId(e.target.value)} className={inputCls + " max-w-xs"}>
              <option value="">Choose a tag…</option>
              {tags.map((t) => (
                <option key={t.id} value={t.id}>{tagLabel(t)}</option>
              ))}
            </select>
            <button
              onClick={handleBulkAssignTag}
              disabled={!bulkTagId || selectedIds.size === 0}
              className="rounded-md bg-primary-600 px-4 py-2 text-sm font-medium text-white hover:bg-primary-700 disabled:opacity-50"
            >
              Apply to {selectedIds.size} transaction{selectedIds.size === 1 ? "" : "s"}
            </button>
          </div>
        </DataCard>
      )}

      <DataCard title={result ? `${result.total} transactions` : "Transactions"}>
        {loading && !result ? (
          <p className="py-6 text-center text-sm text-neutral-500">Loading…</p>
        ) : result && result.items.length > 0 ? (
          <>
            <div className="overflow-x-auto">
              <table className="w-full text-sm">
                <thead>
                  <tr className="border-b border-neutral-200 text-left text-xs font-semibold uppercase tracking-wide text-neutral-500">
                    {tagMode && <th className="py-2 pr-2 w-8"></th>}
                    <SortableHeader label="Date" col="date" sortBy={sortBy} sortDir={sortDir} onClick={toggleSort} />
                    <th className="py-2 pr-4">Title</th>
                    <th className="py-2 pr-4">Counterparty</th>
                    <th className="py-2 pr-4">Account</th>
                    <th className="py-2 pr-4">Notes</th>
                    <th className="py-2 pr-4">Category</th>
                    <th className="py-2 pr-4">Tags</th>
                    <SortableHeader label="Amount" col="amount" sortBy={sortBy} sortDir={sortDir} onClick={toggleSort} align="right" />
                  </tr>
                </thead>
                <tbody>
                  {result.items.map((tx) => (
                    <tr key={tx.id} className="border-b border-neutral-100 last:border-0 hover:bg-neutral-50">
                      {tagMode && (
                        <td className="py-2 pr-2">
                          <input
                            type="checkbox"
                            checked={selectedIds.has(tx.id)}
                            onChange={() => toggleRowSelected(tx.id)}
                            className="h-4 w-4 cursor-pointer"
                          />
                        </td>
                      )}
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
                          <CategoryOptionGroups categories={categories} currentId={tx.categoryId} />
                        </select>
                      </td>
                      <td className="py-2 pr-4">
                        <div className="flex max-w-[10rem] flex-wrap gap-1">
                          {tx.tags.map((tag) => (
                            <span
                              key={tag.id}
                              className="inline-flex items-center gap-1 rounded-full px-2 py-0.5 text-xs font-medium text-white"
                              style={{ backgroundColor: tag.color }}
                            >
                              {tagLabel(tag)}
                              <button
                                onClick={() => handleRemoveTag(tx.id, tag.id)}
                                className="leading-none opacity-80 hover:opacity-100"
                                title={`Remove "${tag.name}"`}
                              >
                                ×
                              </button>
                            </span>
                          ))}
                          <AddTagButton
                            options={tags.filter((t) => !tx.tags.some((existing) => existing.id === t.id))}
                            onAdd={(tagId) => handleAssignTag(tx.id, tagId)}
                          />
                        </div>
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

function AddTagButton({ options, onAdd }: { options: Tag[]; onAdd: (tagId: string) => void }) {
  const [picking, setPicking] = useState(false);

  if (options.length === 0) return null;

  if (!picking) {
    return (
      <button
        type="button"
        onClick={() => setPicking(true)}
        title="Add a tag"
        className="inline-flex h-5 w-5 items-center justify-center rounded-full border border-dashed border-neutral-300 text-xs text-neutral-400 hover:border-neutral-400 hover:text-neutral-600"
      >
        +
      </button>
    );
  }

  return (
    <select
      autoFocus
      value=""
      onChange={(e) => {
        if (e.target.value) onAdd(e.target.value);
        setPicking(false);
      }}
      onBlur={() => setPicking(false)}
      className="rounded-md border border-neutral-300 bg-white px-1 py-0.5 text-xs focus:outline-none focus:ring-2 focus:ring-primary-500"
    >
      <option value="" disabled>Add tag…</option>
      {options.map((t) => (
        <option key={t.id} value={t.id}>{tagLabel(t)}</option>
      ))}
    </select>
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
