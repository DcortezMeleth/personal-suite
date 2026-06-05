import { useEffect, useState } from "react";
import { api } from "../api/client";
import type { Account, CreateTreasuryBond } from "../api/client";

interface Props {
  onClose: () => void;
  onSaved: () => void;
}

export function AddBondModal({ onClose, onSaved }: Props) {
  const [accounts, setAccounts] = useState<Account[]>([]);
  const [form, setForm] = useState<CreateTreasuryBond>({
    accountId: "",
    series: "",
    nominalValue: 100,
    quantity: 1,
    purchaseDate: "",
    maturityDate: "",
    annualRatePct: 0,
    interestType: "FIXED",
  });
  const [saving, setSaving] = useState(false);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    api.get<Account[]>("/accounts").then(setAccounts).catch(() => {});
  }, []);

  function set<K extends keyof CreateTreasuryBond>(k: K, v: CreateTreasuryBond[K]) {
    setForm(f => ({ ...f, [k]: v }));
  }

  async function handleSubmit(e: React.FormEvent) {
    e.preventDefault();
    setSaving(true);
    setError(null);
    try {
      await api.post("/treasury-bonds", form);
      onSaved();
      onClose();
    } catch (e) {
      setError(e instanceof Error ? e.message : "Failed to save bond");
    } finally {
      setSaving(false);
    }
  }

  return (
    <div className="fixed inset-0 z-50 flex items-center justify-center bg-black/40">
      <div className="w-full max-w-md rounded-xl bg-white p-6 shadow-xl">
        <h2 className="mb-4 text-lg font-semibold text-neutral-900">Add Treasury Bond</h2>
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

          <Field label="Series (e.g. OTS0226)">
            <input
              required
              type="text"
              value={form.series}
              onChange={e => set("series", e.target.value)}
              className={inputCls}
              placeholder="OTS0226"
            />
          </Field>

          <div className="grid grid-cols-2 gap-3">
            <Field label="Nominal value (PLN)">
              <input
                required
                type="number"
                min="1"
                step="0.01"
                value={form.nominalValue}
                onChange={e => set("nominalValue", Number(e.target.value))}
                className={inputCls}
              />
            </Field>
            <Field label="Quantity">
              <input
                required
                type="number"
                min="1"
                step="1"
                value={form.quantity}
                onChange={e => set("quantity", Number(e.target.value))}
                className={inputCls}
              />
            </Field>
          </div>

          <div className="grid grid-cols-2 gap-3">
            <Field label="Purchase date">
              <input
                required
                type="date"
                value={form.purchaseDate}
                onChange={e => set("purchaseDate", e.target.value)}
                className={inputCls}
              />
            </Field>
            <Field label="Maturity date">
              <input
                required
                type="date"
                value={form.maturityDate}
                onChange={e => set("maturityDate", e.target.value)}
                className={inputCls}
              />
            </Field>
          </div>

          <div className="grid grid-cols-2 gap-3">
            <Field label="Annual rate (%)">
              <input
                required
                type="number"
                min="0"
                step="0.01"
                value={form.annualRatePct}
                onChange={e => set("annualRatePct", Number(e.target.value))}
                className={inputCls}
              />
            </Field>
            <Field label="Interest type">
              <select
                value={form.interestType}
                onChange={e => set("interestType", e.target.value)}
                className={inputCls}
              >
                <option value="FIXED">Fixed</option>
                <option value="VARIABLE">Variable</option>
                <option value="INDEXED">Indexed</option>
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
              {saving ? "Saving…" : "Add Bond"}
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
