import { DataCard, AlertBanner } from "@delfin/ui";

export function SpendingDashboard() {
  return (
    <div className="space-y-6">
      <div className="flex items-center justify-between">
        <h1 className="text-2xl font-bold text-neutral-900">Spending</h1>
        <button className="rounded-md bg-primary-600 px-4 py-2 text-sm font-medium text-white hover:bg-primary-700">
          Import Statement
        </button>
      </div>

      <AlertBanner
        level="info"
        message="No bank statements imported yet. Click 'Import Statement' to get started."
      />

      <div className="grid grid-cols-1 gap-6 sm:grid-cols-2 lg:grid-cols-4">
        <SummaryTile label="Total Spent" value="—" />
        <SummaryTile label="Total Income" value="—" />
        <SummaryTile label="Net" value="—" />
        <SummaryTile label="vs. Last Month" value="—" />
      </div>

      <div className="grid grid-cols-1 gap-6 lg:grid-cols-2">
        <DataCard title="Spending by Category">
          <p className="text-sm text-neutral-500">No data yet.</p>
        </DataCard>

        <DataCard title="Monthly Trend">
          <p className="text-sm text-neutral-500">No data yet.</p>
        </DataCard>
      </div>

      <DataCard title="Recent Transactions">
        <p className="text-sm text-neutral-500">No transactions yet.</p>
      </DataCard>
    </div>
  );
}

function SummaryTile({ label, value }: { label: string; value: string }) {
  return (
    <div className="rounded-lg border border-neutral-200 bg-white px-5 py-4 shadow-sm">
      <p className="text-xs font-semibold uppercase tracking-wide text-neutral-500">{label}</p>
      <p className="mt-1 text-2xl font-bold text-neutral-900">{value}</p>
    </div>
  );
}
