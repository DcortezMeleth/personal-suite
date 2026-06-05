import { useCallback, useEffect, useState } from "react";
import {
  PieChart, Pie, Cell, Tooltip as PieTooltip,
  BarChart, Bar, XAxis, YAxis, Tooltip as BarTooltip, ResponsiveContainer,
} from "recharts";
import { AlertBanner, DataCard } from "@delfin/ui";
import { ImportModal } from "../components/ImportModal";
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

function currentYearMonth() {
  const now = new Date();
  return { year: now.getFullYear(), month: now.getMonth() + 1 };
}

function ym() {
  const { year, month } = currentYearMonth();
  return `${year}-${String(month).padStart(2, "0")}`;
}

export function SpendingDashboard() {
  const [accounts, setAccounts] = useState<Account[]>([]);
  const [summary, setSummary] = useState<MonthlySummary | null>(null);
  const [trend, setTrend] = useState<MonthlyTrend[]>([]);
  const [topTx, setTopTx] = useState<TransactionRow[]>([]);
  const [budgets, setBudgets] = useState<BudgetStatus[]>([]);
  const [showImport, setShowImport] = useState(false);
  const [loading, setLoading] = useState(true);
  const [importMsg, setImportMsg] = useState<string | null>(null);

  const { year, month } = currentYearMonth();

  const loadData = useCallback(async () => {
    setLoading(true);
    try {
      const [accs, sum, tr, top, bdg] = await Promise.all([
        api.get<Account[]>("/accounts"),
        api.get<MonthlySummary>(`/spending/summary?month=${ym()}`),
        api.get<MonthlyTrend[]>("/spending/trend?months=12"),
        api.get<TransactionRow[]>(`/transactions/top?year=${year}&month=${month}&limit=10`),
        api.get<BudgetStatus[]>(`/budgets/status?month=${ym()}`),
      ]);
      setAccounts(accs);
      setSummary(sum);
      setTrend(tr);
      setTopTx(top);
      setBudgets(bdg);
    } catch (_) {
      // backend not reachable yet — leave state empty
    } finally {
      setLoading(false);
    }
  }, [year, month]);

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

  return (
    <div className="space-y-6">
      {/* ── Header ─────────────────────────────────────────────── */}
      <div className="flex items-center justify-between">
        <h1 className="text-2xl font-bold text-neutral-900">Spending — {ym()}</h1>
        <button
          onClick={() => setShowImport(true)}
          className="rounded-md bg-primary-600 px-4 py-2 text-sm font-medium text-white hover:bg-primary-700"
        >
          Import Statement
        </button>
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
      {noData && (
        <AlertBanner
          level="info"
          message="No transactions this month. Click 'Import Statement' to get started."
        />
      )}

      {/* ── Summary tiles ──────────────────────────────────────── */}
      <div className="grid grid-cols-1 gap-6 sm:grid-cols-2 lg:grid-cols-4">
        <SummaryTile
          label="Total Spent"
          value={summary ? fmt(summary.totalSpent) : "—"}
          sub={summary && summary.deltaVsPrevMonth !== 0
            ? `${summary.deltaVsPrevMonth > 0 ? "+" : ""}${fmt(summary.deltaVsPrevMonth)} vs last month`
            : undefined}
        />
        <SummaryTile label="Total Income"  value={summary ? fmt(summary.totalIncome)  : "—"} />
        <SummaryTile label="Net Cashflow"  value={summary ? fmt(summary.netCashflow)  : "—"} />
        <SummaryTile label="Accounts"      value={String(accounts.length)}               />
      </div>

      {/* ── Charts ─────────────────────────────────────────────── */}
      <div className="grid grid-cols-1 gap-6 lg:grid-cols-2">
        <DataCard title="Spending by Category">
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
                  label={({ categoryName, percent }) =>
                    `${categoryName} ${(percent * 100).toFixed(0)}%`
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
      <DataCard title="Top Expenses This Month">
        {topTx.length > 0 ? (
          <div className="overflow-x-auto">
            <table className="w-full text-sm">
              <thead>
                <tr className="border-b border-neutral-200 text-left text-xs font-semibold uppercase tracking-wide text-neutral-500">
                  <th className="py-2 pr-4">Date</th>
                  <th className="py-2 pr-4">Description</th>
                  <th className="py-2 pr-4">Account</th>
                  <th className="py-2 pr-4">Category</th>
                  <th className="py-2 text-right">Amount</th>
                </tr>
              </thead>
              <tbody>
                {topTx.map((tx) => (
                  <tr key={tx.id} className="border-b border-neutral-100 last:border-0">
                    <td className="py-2 pr-4 text-neutral-500">{tx.date}</td>
                    <td className="py-2 pr-4 text-neutral-900 max-w-xs truncate">{tx.description}</td>
                    <td className="py-2 pr-4 text-neutral-500">{tx.accountName}</td>
                    <td className="py-2 pr-4">
                      {tx.categoryName ? (
                        <span
                          className="inline-block rounded-full px-2 py-0.5 text-xs font-medium text-white"
                          style={{ backgroundColor: tx.categoryColor ?? "#94a3b8" }}
                        >
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
