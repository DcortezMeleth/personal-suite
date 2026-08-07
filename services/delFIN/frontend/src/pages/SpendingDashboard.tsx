import { useCallback, useEffect, useState } from "react";
import {
  PieChart, Pie, Cell, Tooltip as PieTooltip,
  BarChart, Bar, XAxis, YAxis, Tooltip as BarTooltip, ResponsiveContainer,
} from "recharts";
import { AlertBanner, DataCard } from "@delfin/ui";
import { ImportModal } from "../components/ImportModal";
import { AddAccountModal } from "../components/AddAccountModal";
import {
  api,
  Account,
  BudgetStatus,
  ImportResult,
  MonthlySummary,
  MonthlyTrend,
  TransactionRow,
} from "../api/client";

function fmt(amount: number) {
  return amount.toLocaleString("pl-PL", { style: "currency", currency: "PLN" });
}

function currentMonthStr() {
  const now = new Date();
  return `${now.getFullYear()}-${String(now.getMonth() + 1).padStart(2, "0")}`;
}

type ViewMode = "month" | "all";

export function SpendingDashboard() {
  const [viewMode, setViewMode] = useState<ViewMode>("month");
  const [selectedMonth, setSelectedMonth] = useState(currentMonthStr());

  const [accounts, setAccounts] = useState<Account[]>([]);
  const [summary, setSummary] = useState<MonthlySummary | null>(null);
  const [trend, setTrend] = useState<MonthlyTrend[]>([]);
  const [topTx, setTopTx] = useState<TransactionRow[]>([]);
  const [budgets, setBudgets] = useState<BudgetStatus[]>([]);
  const [showImport, setShowImport] = useState(false);
  const [showAddAccount, setShowAddAccount] = useState(false);
  const [loading, setLoading] = useState(true);
  const [importMsg, setImportMsg] = useState<string | null>(null);

  const loadData = useCallback(async () => {
    setLoading(true);
    try {
      if (viewMode === "all") {
        const [accs, sum, tr, top] = await Promise.all([
          api.get<Account[]>("/accounts"),
          api.get<MonthlySummary>("/spending/summary/all-time"),
          api.get<MonthlyTrend[]>("/spending/trend?months=12"),
          api.get<TransactionRow[]>("/transactions/top/all-time?limit=10"),
        ]);
        setAccounts(accs);
        setSummary(sum);
        setTrend(tr);
        setTopTx(top);
        setBudgets([]);
      } else {
        const [year, month] = selectedMonth.split("-").map(Number);
        const [accs, sum, tr, top, bdg] = await Promise.all([
          api.get<Account[]>("/accounts"),
          api.get<MonthlySummary>(`/spending/summary?month=${selectedMonth}`),
          api.get<MonthlyTrend[]>("/spending/trend?months=12"),
          api.get<TransactionRow[]>(`/transactions/top?year=${year}&month=${month}&limit=10`),
          api.get<BudgetStatus[]>(`/budgets/status?month=${selectedMonth}`),
        ]);
        setAccounts(accs);
        setSummary(sum);
        setTrend(tr);
        setTopTx(top);
        setBudgets(bdg);
      }
    } catch (_) {
      // backend not reachable yet — leave state empty
    } finally {
      setLoading(false);
    }
  }, [viewMode, selectedMonth]);

  useEffect(() => { loadData(); }, [loadData]);

  const handleImported = (result: ImportResult) => {
    setImportMsg(
      `Imported ${result.imported} transactions (${result.skipped} skipped, ${result.transfersDetected} transfers detected)`
    );
    loadData();
  };

  const breached  = budgets.filter((b) => b.isBreached);
  const warnings  = budgets.filter((b) => b.isWarning && !b.isBreached);
  const noData    = !loading && summary?.spendingByCategory.length === 0;
  const periodLabel = viewMode === "all" ? "All Time" : selectedMonth;

  return (
    <div className="space-y-6">
      {/* ── Header ─────────────────────────────────────────────── */}
      <div className="flex flex-wrap items-center justify-between gap-3">
        <h1 className="text-2xl font-bold text-neutral-900">Spending — {periodLabel}</h1>
        <div className="flex flex-wrap items-center gap-3">
          <div className="flex rounded-md border border-neutral-300 bg-white p-0.5 shadow-sm">
            <button
              onClick={() => setViewMode("month")}
              className={`rounded px-3 py-1.5 text-sm font-medium ${
                viewMode === "month" ? "bg-primary-600 text-white" : "text-neutral-600 hover:bg-neutral-50"
              }`}
            >
              Monthly
            </button>
            <button
              onClick={() => setViewMode("all")}
              className={`rounded px-3 py-1.5 text-sm font-medium ${
                viewMode === "all" ? "bg-primary-600 text-white" : "text-neutral-600 hover:bg-neutral-50"
              }`}
            >
              All Time
            </button>
          </div>
          {viewMode === "month" && (
            <input
              type="month"
              value={selectedMonth}
              onChange={(e) => setSelectedMonth(e.target.value)}
              className="rounded-md border border-neutral-300 bg-white px-3 py-1.5 text-sm shadow-sm focus:outline-none focus:ring-2 focus:ring-primary-500"
            />
          )}
          <button
            onClick={() => setShowAddAccount(true)}
            className="rounded-md border border-neutral-300 bg-white px-4 py-2 text-sm font-medium text-neutral-700 shadow-sm hover:bg-neutral-50"
          >
            + Account
          </button>
          <button
            onClick={() => setShowImport(true)}
            disabled={accounts.length === 0}
            className="rounded-md bg-primary-600 px-4 py-2 text-sm font-medium text-white hover:bg-primary-700 disabled:opacity-50"
          >
            Import Statement
          </button>
        </div>
      </div>

      {/* ── Alerts ─────────────────────────────────────────────── */}
      {importMsg && (
        <AlertBanner level="success" message={importMsg} />
      )}
      {breached.map((b) => (
        <AlertBanner
          key={b.categoryId}
          level="danger"
          message={`Budget exceeded: ${b.categoryName} — ${fmt(b.spent)} / ${fmt(b.monthlyLimit)} (${b.pct}%)`}
        />
      ))}
      {warnings.map((b) => (
        <AlertBanner
          key={b.categoryId}
          level="warning"
          message={`Budget warning: ${b.categoryName} — ${fmt(b.spent)} / ${fmt(b.monthlyLimit)} (${b.pct}%)`}
        />
      ))}
      {!loading && accounts.length === 0 && (
        <AlertBanner
          level="info"
          message="No accounts yet. Click '+ Account' to add one before importing a statement."
        />
      )}
      {noData && accounts.length > 0 && (
        <AlertBanner
          level="info"
          message={
            viewMode === "all"
              ? "No transactions yet. Click 'Import Statement' to get started."
              : `No transactions in ${selectedMonth}. Try a different month, or click 'Import Statement' to get started.`
          }
        />
      )}

      {/* ── Summary tiles ──────────────────────────────────────── */}
      <div className="grid grid-cols-1 gap-6 sm:grid-cols-2 lg:grid-cols-4">
        <SummaryTile
          label="Total Spent"
          value={summary ? fmt(summary.totalSpent) : "—"}
          sub={viewMode === "month" && summary && summary.deltaVsPrevMonth !== 0
            ? `${summary.deltaVsPrevMonth > 0 ? "+" : ""}${fmt(summary.deltaVsPrevMonth)} vs last month`
            : undefined}
        />
        <SummaryTile label="Total Income"  value={summary ? fmt(summary.totalIncome)  : "—"} />
        <SummaryTile label="Net Cashflow"  value={summary ? fmt(summary.netCashflow)  : "—"} />
        <SummaryTile label="Accounts"      value={String(accounts.length)}               />
      </div>

      {/* ── Charts ─────────────────────────────────────────────── */}
      <div className="grid grid-cols-1 gap-6 lg:grid-cols-2">
        <DataCard title={`Spending by Category — ${periodLabel}`}>
          {summary && summary.spendingByCategory.length > 0 ? (
            <ResponsiveContainer width="100%" height={260}>
              <PieChart>
                <Pie
                  data={summary.spendingByCategory}
                  dataKey="amount"
                  nameKey="categoryName"
                  cx="50%"
                  cy="50%"
                  outerRadius={90}
                  label={({ categoryName, icon, percent }) =>
                    `${icon ? icon + " " : ""}${categoryName} ${(percent * 100).toFixed(0)}%`
                  }
                >
                  {summary.spendingByCategory.map((entry) => (
                    <Cell key={entry.categoryId} fill={entry.color} />
                  ))}
                </Pie>
                <PieTooltip formatter={(v: number) => fmt(v)} />
              </PieChart>
            </ResponsiveContainer>
          ) : (
            <p className="text-sm text-neutral-500">No data yet.</p>
          )}
        </DataCard>

        <DataCard title="12-Month Trend">
          {trend.length > 0 ? (
            <ResponsiveContainer width="100%" height={260}>
              <BarChart data={trend} margin={{ top: 4, right: 8, bottom: 4, left: 8 }}>
                <XAxis dataKey="month" tick={{ fontSize: 11 }} />
                <YAxis tick={{ fontSize: 11 }} />
                <BarTooltip formatter={(v: number) => fmt(v)} />
                <Bar dataKey="totalSpent"  fill="#6366f1" name="Spent"  />
                <Bar dataKey="totalIncome" fill="#16a34a" name="Income" />
              </BarChart>
            </ResponsiveContainer>
          ) : (
            <p className="text-sm text-neutral-500">No data yet.</p>
          )}
        </DataCard>
      </div>

      {/* ── Top transactions ───────────────────────────────────── */}
      <DataCard title={`Top Expenses — ${periodLabel}`}>
        {topTx.length > 0 ? (
          <div className="overflow-x-auto">
            <table className="w-full text-sm">
              <thead>
                <tr className="border-b border-neutral-200 text-left text-xs font-semibold uppercase tracking-wide text-neutral-500">
                  <th className="py-2 pr-4">Date</th>
                  <th className="py-2 pr-4">Title</th>
                  <th className="py-2 pr-4">Account</th>
                  <th className="py-2 pr-4">Category</th>
                  <th className="py-2 text-right">Amount</th>
                </tr>
              </thead>
              <tbody>
                {topTx.map((tx) => (
                  <tr key={tx.id} className="border-b border-neutral-100 last:border-0">
                    <td className="py-2 pr-4 text-neutral-500">{tx.date}</td>
                    <td className="py-2 pr-4 text-neutral-900 max-w-xs truncate" title={tx.title}>{tx.title}</td>
                    <td className="py-2 pr-4 text-neutral-500">{tx.accountName}</td>
                    <td className="py-2 pr-4">
                      {tx.categoryName ? (
                        <span
                          className="inline-flex items-center gap-1 rounded-full px-2 py-0.5 text-xs font-medium text-white"
                          style={{ backgroundColor: tx.categoryColor ?? "#94a3b8" }}
                        >
                          {tx.categoryIcon && <span>{tx.categoryIcon}</span>}
                          {tx.categoryName}
                        </span>
                      ) : (
                        <span className="text-neutral-400">—</span>
                      )}
                    </td>
                    <td className="py-2 text-right font-medium text-red-600">
                      {fmt(Math.abs(tx.amount))}
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        ) : (
          <p className="text-sm text-neutral-500">No transactions yet.</p>
        )}
      </DataCard>

      {/* ── Import modal ───────────────────────────────────────── */}
      {showImport && (
        <ImportModal
          accounts={accounts}
          onClose={() => setShowImport(false)}
          onImported={handleImported}
        />
      )}

      {/* ── Add account modal ──────────────────────────────────── */}
      {showAddAccount && (
        <AddAccountModal
          onClose={() => setShowAddAccount(false)}
          onSaved={() => loadData()}
        />
      )}
    </div>
  );
}

function SummaryTile({ label, value, sub }: { label: string; value: string; sub?: string }) {
  return (
    <div className="rounded-lg border border-neutral-200 bg-white px-5 py-4 shadow-sm">
      <p className="text-xs font-semibold uppercase tracking-wide text-neutral-500">{label}</p>
      <p className="mt-1 text-2xl font-bold text-neutral-900">{value}</p>
      {sub && <p className="mt-1 text-xs text-neutral-500">{sub}</p>}
    </div>
  );
}
