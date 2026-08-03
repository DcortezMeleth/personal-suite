import { useState } from "react";
import { api } from "../api/client";
import type { Account, AccountOwner, AccountType } from "../api/client";

interface Props {
  onClose: () => void;
  onSaved: (account: Account) => void;
}

interface CreateAccount {
  name: string;
  accountType: AccountType;
  institution: string;
  currency: string;
  owner: AccountOwner;
}

export function AddAccountModal({ onClose, onSaved }: Props) {
  const [form, setForm] = useState<CreateAccount>({
    name: "",
    accountType: "CHECKING",
    institution: "",
    currency: "PLN",
    owner: "SELF",
  });
  const [saving, setSaving] = useState(false);
  const [error, setError] = useState<string | null>(null);

  function set<K extends keyof CreateAccount>(k: K, v: CreateAccount[K]) {
    setForm((f) => ({ ...f, [k]: v }));
  }

  async function handleSubmit(e: React.FormEvent) {
    e.preventDefault();
    setSaving(true);
    setError(null);
    try {
      const account = await api.post<Account>("/accounts", form);
      onSaved(account);
      onClose();
    } catch (e) {
      setError(e instanceof Error ? e.message : "Failed to save account");
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
        <h2 className="mb-4 text-lg font-semibold text-neutral-900">Add Account</h2>
        {error && (
          <p className="mb-3 rounded-md bg-red-50 px-3 py-2 text-sm text-red-700">{error}</p>
        )}
        <form onSubmit={handleSubmit} className="space-y-3">
          <Field label="Name">
            <input
              required
              type="text"
              value={form.name}
              onChange={(e) => set("name", e.target.value)}
              className={inputCls}
              placeholder="e.g. Alior Personal"
            />
          </Field>

          <Field label="Institution">
            <input
              required
              type="text"
              value={form.institution}
              onChange={(e) => set("institution", e.target.value)}
              className={inputCls}
              placeholder="e.g. Alior Bank"
            />
          </Field>

          <div className="grid grid-cols-2 gap-3">
            <Field label="Account type">
              <select
                value={form.accountType}
                onChange={(e) => set("accountType", e.target.value as AccountType)}
                className={inputCls}
              >
                <option value="CHECKING">Checking</option>
                <option value="SAVINGS">Savings</option>
                <option value="INVESTMENT">Investment</option>
                <option value="IKE">IKE</option>
                <option value="IKZE">IKZE</option>
                <option value="COMPANY">Company</option>
              </select>
            </Field>
            <Field label="Currency">
              <select
                value={form.currency}
                onChange={(e) => set("currency", e.target.value)}
                className={inputCls}
              >
                <option value="PLN">PLN</option>
                <option value="EUR">EUR</option>
                <option value="USD">USD</option>
              </select>
            </Field>
          </div>

          <Field label="Owner">
            <select
              value={form.owner}
              onChange={(e) => set("owner", e.target.value as AccountOwner)}
              className={inputCls}
            >
              <option value="SELF">Self</option>
              <option value="WIFE">Wife</option>
              <option value="JOINT">Joint</option>
            </select>
          </Field>

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
              {saving ? "Saving…" : "Add Account"}
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
