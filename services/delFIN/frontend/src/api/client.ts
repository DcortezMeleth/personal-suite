const BASE = "/api";

async function get<T>(path: string): Promise<T> {
  const res = await fetch(`${BASE}${path}`);
  if (!res.ok) throw new Error(`GET ${path} → ${res.status}`);
  return res.json() as Promise<T>;
}

async function post<T>(path: string, body: unknown): Promise<T> {
  const res = await fetch(`${BASE}${path}`, {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify(body),
  });
  if (!res.ok) throw new Error(`POST ${path} → ${res.status}`);
  return res.json() as Promise<T>;
}

async function patch<T>(path: string, body: unknown): Promise<T> {
  const res = await fetch(`${BASE}${path}`, {
    method: "PATCH",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify(body),
  });
  if (!res.ok) throw new Error(`PATCH ${path} → ${res.status}`);
  return res.json() as Promise<T>;
}

async function del(path: string): Promise<void> {
  const res = await fetch(`${BASE}${path}`, { method: "DELETE" });
  if (!res.ok) throw new Error(`DELETE ${path} → ${res.status}`);
}

async function upload<T>(path: string, formData: FormData): Promise<T> {
  const res = await fetch(`${BASE}${path}`, { method: "POST", body: formData });
  if (!res.ok) {
    const body = await res.text();
    throw new Error(body || `Upload ${path} → ${res.status}`);
  }
  return res.json() as Promise<T>;
}

export const api = { get, post, patch, del, upload };

// ── Types ────────────────────────────────────────────────────────────────────

export type AccountType = "CHECKING" | "SAVINGS" | "INVESTMENT" | "IKE" | "IKZE" | "COMPANY";
export type AccountOwner = "SELF" | "WIFE" | "JOINT";

export interface Account {
  id: string;
  name: string;
  accountType: AccountType;
  institution: string;
  currency: string;
  owner: AccountOwner;
}

export interface TransactionRow {
  id: string;
  accountId: string;
  accountName: string;
  date: string;
  amount: number;
  currency: string;
  description: string;
  categoryId: string | null;
  categoryName: string | null;
  categoryColor: string | null;
  isInternalTransfer: boolean;
}

export interface Category {
  id: string;
  name: string;
  color: string;
  icon: string | null;
  parentId: string | null;
}

export interface CategorySpending {
  categoryId: string;
  categoryName: string;
  color: string;
  amount: number;
}

export interface MonthlySummary {
  month: string;
  totalSpent: number;
  totalIncome: number;
  netCashflow: number;
  deltaVsPrevMonth: number;
  spendingByCategory: CategorySpending[];
}

export interface MonthlyTrend {
  month: string;
  totalSpent: number;
  totalIncome: number;
}

export interface BudgetStatus {
  categoryId: string;
  categoryName: string;
  monthlyLimit: number;
  alertThresholdPct: number;
  spent: number;
  pct: number;
  isWarning: boolean;
  isBreached: boolean;
}

export interface ImportResult {
  imported: number;
  skipped: number;
  transfersDetected: number;
}
