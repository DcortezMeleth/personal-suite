import { useEffect, useState } from "react";
import {
  PieChart, Pie, Cell, Tooltip, ResponsiveContainer,
  LineChart, Line, XAxis, YAxis, CartesianGrid, Legend,
} from "recharts";
import { DataCard, AlertBanner } from "@delfin/ui";
import { api } from "../api/client";
import type {
  PortfolioSummary, PositionDetail, BondWithValue, DepositWithValue,
  InflationPoint, AccountOwner,
} from "../api/client";
import { AddBondModal } from "../components/AddBondModal";
import { AddDepositModal } from "../components/AddDepositModal";

const PLN = (n: number) =>
  n.toLocaleString("pl-PL", { style: "currency", currency: "PLN", maximumFractionDigits: 2 });

const PCT = (n: number) => `${n >= 0 ? "+" : ""}${n.toFixed(2)}%`;

const COLORS = ["#3b82f6", "#10b981", "#f59e0b", "#ef4444", "#8b5cf6", "#06b6d4"];

type OwnerFilter = "ALL" | AccountOwner;

export function InvestmentDashboard() {
  const [owner, setOwner] = useState<OwnerFilter>("ALL");
  const [summary, setSummary] = useState<PortfolioSummary | null>(null);
  const [positions, setPositions] = useState<PositionDetail[]>([]);
  const [bonds, setBonds] = useState<BondWithValue[]>([]);
  const [deposits, setDeposits] = useState<DepositWithValue[]>([]);
  const [inflation, setInflation] = useState<InflationPoint[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [showBondModal, setShowBondModal] = useState(false);
  const [showDepositModal, setShowDepositModal] = useState(false);

  const ownerParam = owner === "ALL" ? "" : `?owner=${owner}`;

  async function load() {
    setLoading(true);
    setError(null);
    try {
      const [sum, pos, bnd, dep, inf] = await Promise.all([
        api.get<PortfolioSummary>(`/portfolio/summary${ownerParam}`),
        api.get<PositionDetail[]>(`/portfolio/positions${ownerParam}`),
        api.get<BondWithValue[]>(`/treasury-bonds${ownerParam}`),
        api.get<DepositWithValue[]>(`/deposits${ownerParam}`),
        api.get<InflationPoint[]>("/portfolio/inflation?months=36"),
      ]);
      setSummary(sum);
      setPositions(pos);
      setBonds(bnd);
      setDeposits(dep);
      setInflation(inf);
    } catch (e) {
      setError(e instanceof Error ? e.message : "Failed to load portfolio");
    } finally {
      setLoading(false);
    }
  }

  useEffect(() => { load(); }, [owner]);

  const gain = summary?.totalGainLoss ?? 0;
  const gainPct = summary?.totalGainLossPct ?? 0;

  return (
    <div className="space-y-6">
      {/* Header */}
      <div className="flex flex-wrap items-center justify-between gap-3">
        <h1 className="text-2xl font-bold text-neutral-900">Portfolio</h1>
        <div className="flex items-center gap-3">
          <select
            value={owner}
            onChange={e => setOwner(e.target.value as OwnerFilter)}
            className="rounded-md border border-neutral-300 bg-white px-3 py-1.5 text-sm shadow-sm focus:outline-none focus:ring-2 focus:ring-primary-500"
          >
            <option value="ALL">All owners</option>
            <option value="SELF">Self</option>
            <option value="WIFE">Wife</option>
            <option value="JOINT">Joint</option>
          </select>
          <button
            onClick={() => setShowBondModal(true)}
            className="rounded-md border border-neutral-300 bg-white px-3 py-1.5 text-sm font-medium text-neutral-700 shadow-sm hover:bg-neutral-50"
          >
            + Bond
          </button>
          <button
            onClick={() => setShowDepositModal(true)}
            className="rounded-md bg-primary-600 px-4 py-1.5 text-sm font-medium text-white hover:bg-primary-700"
          >
            + Deposit
          </button>
        </div>
      </div>

      {error && <AlertBanner level="danger" message={error} />}

      {/* Summary tiles */}
      <div className="grid grid-cols-1 gap-4 sm:grid-cols-2 lg:grid-cols-4">
        <SummaryTile
          label="Current Value"
          value={summary ? PLN(summary.totalCurrentValue) : "—"}
          sub={loading ? "loading…" : "market + accrued"}
        />
        <SummaryTile
          label="Total Invested"
          value={summary ? PLN(summary.totalInvested) : "—"}
          sub="cost basis"
        />
        <SummaryTile
          label="Total Return"
          value={summary ? PLN(gain) : "—"}
          sub={summary ? PCT(gainPct) : ""}
          highlight={gain > 0 ? "green" : gain < 0 ? "red" : undefined}
        />
        <SummaryTile
          label="Positions"
          value={positions.length > 0 ? String(positions.length) : "—"}
          sub={`${bonds.length} bonds · ${deposits.length} deposits`}
        />
      </div>

      {/* Charts row */}
      <div className="grid grid-cols-1 gap-6 lg:grid-cols-2">
        {/* Allocation pie */}
        <DataCard title="Allocation by Type">
          {summary && summary.byType.length > 0 ? (
            <ResponsiveContainer width="100%" height={220}>
              <PieChart>
                <Pie
                  data={summary.byType}
                  dataKey="currentValue"
                  nameKey="typeName"
                  cx="50%"
                  cy="50%"
                  outerRadius={80}
                  label={({ typeName, pct }: { typeName: string; pct: number }) =>
                    `${typeName} ${pct.toFixed(1)}%`
                  }
                >
                  {summary.byType.map((_, i) => (
                    <Cell key={i} fill={COLORS[i % COLORS.length]} />
                  ))}
                </Pie>
                <Tooltip formatter={(v: number) => PLN(v)} />
              </PieChart>
            </ResponsiveContainer>
          ) : (
            <p className="py-10 text-center text-sm text-neutral-500">No data yet.</p>
          )}
        </DataCard>

        {/* Inflation overlay */}
        <DataCard title="CPI Index (Poland, last 36 months)">
          {inflation.length > 0 ? (
            <ResponsiveContainer width="100%" height={220}>
              <LineChart data={inflation} margin={{ top: 5, right: 10, left: 0, bottom: 5 }}>
                <CartesianGrid strokeDasharray="3 3" stroke="#e5e7eb" />
                <XAxis
                  dataKey="yearMonth"
                  tick={{ fontSize: 11 }}
                  tickFormatter={ym => ym.slice(2)}
                  interval={5}
                />
                <YAxis tick={{ fontSize: 11 }} domain={["auto", "auto"]} />
                <Tooltip />
                <Legend />
                <Line
                  type="monotone"
                  dataKey="cpiIndex"
                  name="CPI Index"
                  stroke="#ef4444"
                  dot={false}
                  strokeWidth={2}
                />
              </LineChart>
            </ResponsiveContainer>
          ) : (
            <div className="flex flex-col items-center gap-3 py-10">
              <p className="text-sm text-neutral-500">No inflation data cached.</p>
              <button
                onClick={async () => {
                  await api.post("/portfolio/inflation/fetch", {});
                  await load();
                }}
                className="rounded-md bg-primary-600 px-4 py-1.5 text-sm font-medium text-white hover:bg-primary-700"
              >
                Fetch from Eurostat
              </button>
            </div>
          )}
        </DataCard>
      </div>

      {/* Positions table */}
      <DataCard title="Positions (Stocks / ETFs / Funds)">
        {positions.length === 0 ? (
          <p className="py-6 text-center text-sm text-neutral-500">
            No open positions. Import from XTB or add manually.
          </p>
        ) : (
          <div className="overflow-x-auto">
            <table className="w-full text-sm">
              <thead>
                <tr className="border-b border-neutral-200 text-left text-xs uppercase tracking-wide text-neutral-500">
                  <th className="pb-2 pr-4">Symbol</th>
                  <th className="pb-2 pr-4">Name</th>
                  <th className="pb-2 pr-4">Account</th>
                  <th className="pb-2 pr-4 text-right">Qty</th>
                  <th className="pb-2 pr-4 text-right">Avg Buy</th>
                  <th className="pb-2 pr-4 text-right">Cost Basis</th>
                  <th className="pb-2 pr-4 text-right">Gain/Loss</th>
                  <th className="pb-2 text-right">%</th>
                </tr>
              </thead>
              <tbody className="divide-y divide-neutral-100">
                {positions.map(p => {
                  const gl = p.gainLoss ?? 0;
                  const glPct = p.gainLossPct ?? 0;
                  const color = gl > 0 ? "text-green-600" : gl < 0 ? "text-red-600" : "text-neutral-700";
                  return (
                    <tr key={p.instrumentId + p.accountId} className="hover:bg-neutral-50">
                      <td className="py-2 pr-4 font-mono font-semibold">{p.symbol ?? "—"}</td>
                      <td className="py-2 pr-4 text-neutral-700">{p.name}</td>
                      <td className="py-2 pr-4 text-neutral-500">{p.accountName}</td>
                      <td className="py-2 pr-4 text-right font-mono">{Number(p.quantity).toFixed(4)}</td>
                      <td className="py-2 pr-4 text-right font-mono">
                        {p.avgBuyPrice != null ? PLN(p.avgBuyPrice) : "—"}
                      </td>
                      <td className="py-2 pr-4 text-right font-mono">
                        {p.costBasis != null ? PLN(p.costBasis) : "—"}
                      </td>
                      <td className={`py-2 pr-4 text-right font-mono ${color}`}>
                        {p.gainLoss != null ? PLN(gl) : "—"}
                      </td>
                      <td className={`py-2 text-right font-mono ${color}`}>
                        {p.gainLossPct != null ? PCT(glPct) : "—"}
                      </td>
                    </tr>
                  );
                })}
              </tbody>
            </table>
          </div>
        )}
      </DataCard>

      {/* Treasury bonds */}
      <DataCard title="Treasury Bonds">
        {bonds.length === 0 ? (
          <p className="py-6 text-center text-sm text-neutral-500">
            No bonds. Click &ldquo;+ Bond&rdquo; to add one.
          </p>
        ) : (
          <div className="overflow-x-auto">
            <table className="w-full text-sm">
              <thead>
                <tr className="border-b border-neutral-200 text-left text-xs uppercase tracking-wide text-neutral-500">
                  <th className="pb-2 pr-4">Series</th>
                  <th className="pb-2 pr-4">Account</th>
                  <th className="pb-2 pr-4 text-right">Qty</th>
                  <th className="pb-2 pr-4 text-right">Invested</th>
                  <th className="pb-2 pr-4 text-right">Accrued</th>
                  <th className="pb-2 pr-4 text-right">Current</th>
                  <th className="pb-2 pr-4 text-right">Rate</th>
                  <th className="pb-2 pr-4">Maturity</th>
                  <th className="pb-2 text-right">Return</th>
                </tr>
              </thead>
              <tbody className="divide-y divide-neutral-100">
                {bonds.map(b => (
                  <tr key={b.bond.id} className="hover:bg-neutral-50">
                    <td className="py-2 pr-4 font-mono font-semibold">{b.bond.series}</td>
                    <td className="py-2 pr-4 text-neutral-500">{b.accountName}</td>
                    <td className="py-2 pr-4 text-right font-mono">{b.bond.quantity}</td>
                    <td className="py-2 pr-4 text-right font-mono">{PLN(b.invested)}</td>
                    <td className="py-2 pr-4 text-right font-mono text-green-600">{PLN(b.accruedInterest)}</td>
                    <td className="py-2 pr-4 text-right font-mono">{PLN(b.currentValue)}</td>
                    <td className="py-2 pr-4 text-right font-mono">{b.bond.annualRatePct}%</td>
                    <td className="py-2 pr-4 text-neutral-500">{b.bond.maturityDate}</td>
                    <td className="py-2 text-right font-mono text-green-600">{PCT(b.gainLossPct)}</td>
                  </tr>
                ))}
              </tbody>
              <tfoot>
                <tr className="border-t-2 border-neutral-300 font-semibold">
                  <td colSpan={3} className="pt-2 text-neutral-700">Total</td>
                  <td className="pt-2 text-right font-mono">{PLN(bonds.reduce((s, b) => s + b.invested, 0))}</td>
                  <td className="pt-2 text-right font-mono text-green-600">{PLN(bonds.reduce((s, b) => s + b.accruedInterest, 0))}</td>
                  <td className="pt-2 text-right font-mono">{PLN(bonds.reduce((s, b) => s + b.currentValue, 0))}</td>
                  <td colSpan={3} />
                </tr>
              </tfoot>
            </table>
          </div>
        )}
      </DataCard>

      {/* Deposits */}
      <DataCard title="Deposits">
        {deposits.length === 0 ? (
          <p className="py-6 text-center text-sm text-neutral-500">
            No deposits. Click &ldquo;+ Deposit&rdquo; to add one.
          </p>
        ) : (
          <div className="overflow-x-auto">
            <table className="w-full text-sm">
              <thead>
                <tr className="border-b border-neutral-200 text-left text-xs uppercase tracking-wide text-neutral-500">
                  <th className="pb-2 pr-4">Account</th>
                  <th className="pb-2 pr-4 text-right">Amount</th>
                  <th className="pb-2 pr-4 text-right">Rate</th>
                  <th className="pb-2 pr-4">Start</th>
                  <th className="pb-2 pr-4">End</th>
                  <th className="pb-2 pr-4 text-right">Accrued</th>
                  <th className="pb-2 pr-4 text-right">Current</th>
                  <th className="pb-2 pr-4">Status</th>
                  <th className="pb-2 text-right">Return</th>
                </tr>
              </thead>
              <tbody className="divide-y divide-neutral-100">
                {deposits.map(d => (
                  <tr key={d.deposit.id} className="hover:bg-neutral-50">
                    <td className="py-2 pr-4 text-neutral-500">{d.accountName}</td>
                    <td className="py-2 pr-4 text-right font-mono">{PLN(d.deposit.amount)}</td>
                    <td className="py-2 pr-4 text-right font-mono">{d.deposit.interestRate}%</td>
                    <td className="py-2 pr-4 text-neutral-500">{d.deposit.startDate}</td>
                    <td className="py-2 pr-4 text-neutral-500">{d.deposit.endDate}</td>
                    <td className="py-2 pr-4 text-right font-mono text-green-600">{PLN(d.accruedInterest)}</td>
                    <td className="py-2 pr-4 text-right font-mono">{PLN(d.currentValue)}</td>
                    <td className="py-2 pr-4">
                      <StatusBadge status={d.deposit.status} />
                    </td>
                    <td className="py-2 text-right font-mono text-green-600">{PCT(d.gainLossPct)}</td>
                  </tr>
                ))}
              </tbody>
              <tfoot>
                <tr className="border-t-2 border-neutral-300 font-semibold">
                  <td className="pt-2 text-neutral-700">Total</td>
                  <td className="pt-2 text-right font-mono">{PLN(deposits.reduce((s, d) => s + d.deposit.amount, 0))}</td>
                  <td colSpan={3} />
                  <td className="pt-2 text-right font-mono text-green-600">{PLN(deposits.reduce((s, d) => s + d.accruedInterest, 0))}</td>
                  <td className="pt-2 text-right font-mono">{PLN(deposits.reduce((s, d) => s + d.currentValue, 0))}</td>
                  <td colSpan={2} />
                </tr>
              </tfoot>
            </table>
          </div>
        )}
      </DataCard>

      {showBondModal && (
        <AddBondModal onClose={() => setShowBondModal(false)} onSaved={load} />
      )}
      {showDepositModal && (
        <AddDepositModal onClose={() => setShowDepositModal(false)} onSaved={load} />
      )}
    </div>
  );
}

function SummaryTile({
  label, value, sub, highlight,
}: {
  label: string;
  value: string;
  sub: string;
  highlight?: "green" | "red";
}) {
  const valueColor =
    highlight === "green" ? "text-green-600" :
    highlight === "red"   ? "text-red-600"   :
    "text-neutral-900";
  return (
    <div className="rounded-lg border border-neutral-200 bg-white px-5 py-4 shadow-sm">
      <p className="text-xs font-semibold uppercase tracking-wide text-neutral-500">{label}</p>
      <p className={`mt-1 text-xl font-bold ${valueColor}`}>{value}</p>
      <p className="mt-0.5 text-xs text-neutral-400">{sub}</p>
    </div>
  );
}

function StatusBadge({ status }: { status: string }) {
  const cls =
    status === "ACTIVE"   ? "bg-green-100 text-green-700" :
    status === "MATURED"  ? "bg-neutral-100 text-neutral-600" :
    "bg-red-100 text-red-700";
  return (
    <span className={`rounded-full px-2 py-0.5 text-xs font-medium ${cls}`}>
      {status}
    </span>
  );
}
