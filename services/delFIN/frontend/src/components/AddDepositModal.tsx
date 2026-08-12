import { useEffect, useState } from "react";
import { DatePicker } from "@delfin/ui";
import { api } from "../api/client";
import type { Account, CreateDeposit, DepositStatus } from "../api/client";

interface Props {
  onClose: () => void;
  onSaved: () => void;
}

export function AddDepositModal({ onClose, onSaved }: Props) {
  const [accounts, setAccounts] = useState<Account[]>([]);
  const [form, setForm] = useState<CreateDeposit>({
    accountId: "",
    amount: 0,
    currency: "PLN",
    startDate: "",
    endDate: "",
    interestRate: 0,
    status: "ACTIVE",
  });
  const [saving, setSaving] = useState(false);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    api.get<Account[]>("/accounts").then(setAccounts).catch(() => {});
  }, []);

  function set<K extends keyof CreateDeposit>(k: K, v: CreateDeposit[K]) {
    setForm(f => ({ ...f, [k]: v }));
  }

  async function handleSubmit(e: React.FormEvent) {
    e.preventDefault();
    if (!form.startDate || !form.endDate) {
      setError("Start date and end date are required.");
      return;
    }
    setSaving(true);
    setError(null);
    try {
      await api.post("/deposits", form);
      onSaved();
      onClose();
    } catch (e) {
      setError(e instanceof Error ? e.message : "Failed to save deposit");
    } finally {
      setSaving(false);
    }
  }

  return (
    <div
      className="fixed inset-0 z-50 flex items-center justify-center bg-black/40"
      onClick={onClose}
    >
      <div
        className="w-full max-w-md rounded-xl bg-white p-6 shadow-xl"
        onClick={(e) => e.stopPropagation()}
      >
        <h2 className="mb-4 text-lg font-semibold text-neutral-900">Add Bank Deposit</h2>
        {error && (
          <p className="mb-3 rounded-md bg-red-50 px-3 py-2 text-sm text-red-700">{error}</p>
        )}
        <form onSubmit={handleSubmit} className="space-y-3">
          <Field label="Account">
            <select
              required
              value={form.accountId}
              onChange={e => set("accountId", e.target.value)}
              className={inputCls}
            >
              <option value="">Select account…</option>
              {accounts.map(a => (
                <option key={a.id} value={a.id}>{a.name}</option>
              ))}
            </select>
          </Field>

          <div className="grid grid-cols-2 gap-3">
            <Field label="Amount">
              <input
                required
                type="number"
                min="0.01"
                step="0.01"
                value={form.amount}
                onChange={e => set("amount", Number(e.target.value))}
                className={inputCls}
              />
            </Field>
            <Field label="Currency">
              <select
                value={form.currency}
                onChange={e => set("currency", e.target.value)}
                className={inputCls}
              >
                <option value="PLN">PLN</option>
                <option value="EUR">EUR</option>
                <option value="USD">USD</option>
              </select>
            </Field>
          </div>

          <div className="grid grid-cols-2 gap-3">
            <Field label="Start date">
              <DatePicker value={form.startDate} onChange={v => set("startDate", v)} />
            </Field>
            <Field label="End date">
              <DatePicker value={form.endDate} onChange={v => set("endDate", v)} align="right" />
            </Field>
          </div>

          <div className="grid grid-cols-2 gap-3">
            <Field label="Annual interest rate (%)">
              <input
                required
                type="number"
                min="0"
                step="0.01"
                value={form.interestRate}
                onChange={e => set("interestRate", Number(e.target.value))}
                className={inputCls}
              />
            </Field>
            <Field label="Status">
              <select
                value={form.status}
                onChange={e => set("status", e.target.value as DepositStatus)}
                className={inputCls}
              >
                <option value="ACTIVE">Active</option>
                <option value="MATURED">Matured</option>
                <option value="BROKEN">Broken</option>
              </select>
            </Field>
          </div>

          <div className="flex justify-end gap-3 pt-2">
            <button
              type="button"
              onClick={onClose}
              className="rounded-md border border-neutral-300 px-4 py-2 text-sm font-medium text-neutral-700 hover:bg-neutral-50"
            >
              Cancel
            </button>
            <button
              type="submit"
              disabled={saving}
              className="rounded-md bg-primary-600 px-4 py-2 text-sm font-medium text-white hover:bg-primary-700 disabled:opacity-50"
            >
              {saving ? "Saving…" : "Add Deposit"}
            </button>
          </div>
        </form>
      </div>
    </div>
  );
}

const inputCls = "w-full rounded-md border border-neutral-300 px-3 py-1.5 text-sm focus:outline-none focus:ring-2 focus:ring-primary-500";

function Field({ label, children }: { label: string; children: React.ReactNode }) {
  return (
    <div>
      <label className="mb-1 block text-xs font-medium text-neutral-600">{label}</label>
      {children}
    </div>
  );
}
