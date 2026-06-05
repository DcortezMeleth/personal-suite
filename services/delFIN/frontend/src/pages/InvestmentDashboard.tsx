import { DataCard, AlertBanner } from "@delfin/ui";

export function InvestmentDashboard() {
  return (
    <div className="space-y-6">
      <div className="flex items-center justify-between">
        <h1 className="text-2xl font-bold text-neutral-900">Investments</h1>
        <button className="rounded-md bg-primary-600 px-4 py-2 text-sm font-medium text-white hover:bg-primary-700">
          Add Position
        </button>
      </div>

      <AlertBanner
        level="info"
        message="No investment data yet. Add positions manually or import from your broker."
      />

      <div className="grid grid-cols-1 gap-6 sm:grid-cols-2 lg:grid-cols-3">
        <SummaryTile label="Total Invested" value="—" sub="cost basis" />
        <SummaryTile label="Current Value" value="—" sub="market value" />
        <SummaryTile label="Total Return" value="—" sub="vs. inflation" />
      </div>

      <div className="grid grid-cols-1 gap-6 lg:grid-cols-2">
        <DataCard title="Portfolio Breakdown">
          <p className="text-sm text-neutral-500">No positions yet.</p>
        </DataCard>

        <DataCard title="Value Over Time">
          <p className="text-sm text-neutral-500">No data yet.</p>
        </DataCard>
      </div>

      <DataCard title="Positions">
        <p className="text-sm text-neutral-500">No positions yet.</p>
      </DataCard>
    </div>
  );
}

function SummaryTile({
  label,
  value,
  sub,
}: {
  label: string;
  value: string;
  sub: string;
}) {
  return (
    <div className="rounded-lg border border-neutral-200 bg-white px-5 py-4 shadow-sm">
      <p className="text-xs font-semibold uppercase tracking-wide text-neutral-500">{label}</p>
      <p className="mt-1 text-2xl font-bold text-neutral-900">{value}</p>
      <p className="mt-0.5 text-xs text-neutral-400">{sub}</p>
    </div>
  );
}
