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

async function put<T>(path: string, body: unknown): Promise<T> {
  const res = await fetch(`${BASE}${path}`, {
    method: "PUT",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify(body),
  });
  if (!res.ok) throw new Error(`PUT ${path} → ${res.status}`);
  return res.json() as Promise<T>;
}

async function del(path: string): Promise<void> {
  const res = await fetch(`${BASE}${path}`, { method: "DELETE" });
  if (!res.ok) {
    const body = await res.json().catch(() => null) as { error?: string } | null;
    throw new Error(body?.error || `DELETE ${path} → ${res.status}`);
  }
}

async function upload<T>(path: string, formData: FormData): Promise<T> {
  const res = await fetch(`${BASE}${path}`, { method: "POST", body: formData });
  if (!res.ok) {
    const body = await res.text();
    throw new Error(body || `Upload ${path} → ${res.status}`);
  }
  return res.json() as Promise<T>;
}

export const api = { get, post, patch, put, del, upload };

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
  title: string;
  counterparty: string | null;
  notes: string | null;
  categoryId: string | null;
  categoryName: string | null;
  categoryColor: string | null;
  categoryIcon: string | null;
  isInternalTransfer: boolean;
}

export interface TransactionSearchResult {
  items: TransactionRow[];
  total: number;
}

export interface CreateRuleResult {
  ruleCreated: boolean;
  rulePattern: string | null;
  affected: number;
}

export type RuleMatchType = "CONTAINS" | "REGEX" | "EXACT";
export type RuleDirection = "ANY" | "INCOME" | "EXPENSE";
export type RecategorizeScope = "NONE" | "UNCATEGORIZED_ONLY" | "ALL";

export interface CategoryRule {
  id: string;
  categoryId: string;
  pattern: string;
  matchType: RuleMatchType;
  priority: number;
  direction: RuleDirection;
}

export interface CategoryRuleForm {
  categoryId: string;
  pattern: string;
  matchType: RuleMatchType;
  priority: number;
  direction: RuleDirection;
}

export interface ReapplyResult {
  affected: number;
}

export interface Category {
  id: string;
  name: string;
  color: string;
  icon: string | null;
  parentId: string | null;
}

export interface CategoryForm {
  name: string;
  color: string;
  icon: string | null;
  parentId: string | null;
}

export interface Tag {
  id: string;
  name: string;
  color: string;
}

export interface TagForm {
  name: string;
  color: string;
}

export interface CategorySpending {
  categoryId: string;
  categoryName: string;
  color: string;
  icon: string | null;
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

// ── Investment types ─────────────────────────────────────────────────────────

export type InstrumentType = "ETF" | "STOCK" | "TREASURY_BOND" | "DEPOSIT" | "FUND";
export type InvestmentTxType = "BUY" | "SELL" | "DIVIDEND" | "INTEREST" | "COUPON" | "MATURITY";
export type DepositStatus = "ACTIVE" | "MATURED" | "BROKEN";

export interface PositionDetail {
  instrumentId: string;
  symbol: string | null;
  name: string;
  instrumentType: InstrumentType;
  currency: string;
  accountId: string;
  accountName: string;
  quantity: number;
  avgBuyPrice: number | null;
  currentPrice: number | null;
  costBasis: number | null;
  currentValue: number | null;
  gainLoss: number | null;
  gainLossPct: number | null;
}

export interface TreasuryBond {
  id: string;
  accountId: string;
  series: string;
  nominalValue: number;
  quantity: number;
  purchaseDate: string;
  maturityDate: string;
  annualRatePct: number;
  interestType: string;
}

export interface BondWithValue {
  bond: TreasuryBond;
  accountName: string;
  invested: number;
  accruedInterest: number;
  currentValue: number;
  gainLossPct: number;
}

export interface Deposit {
  id: string;
  accountId: string;
  amount: number;
  currency: string;
  startDate: string;
  endDate: string;
  interestRate: number;
  status: DepositStatus;
}

export interface DepositWithValue {
  deposit: Deposit;
  accountName: string;
  accruedInterest: number;
  currentValue: number;
  gainLossPct: number;
}

export interface TypeAllocation {
  typeName: string;
  currentValue: number;
  invested: number;
  pct: number;
}

export interface PortfolioSummary {
  owner: string;
  totalCurrentValue: number;
  totalInvested: number;
  totalGainLoss: number;
  totalGainLossPct: number;
  byType: TypeAllocation[];
}

export interface InflationPoint {
  yearMonth: string;
  cpiIndex: number;
}

export interface CreateTreasuryBond {
  accountId: string;
  series: string;
  nominalValue: number;
  quantity: number;
  purchaseDate: string;
  maturityDate: string;
  annualRatePct: number;
  interestType: string;
}

export interface CreateDeposit {
  accountId: string;
  amount: number;
  currency: string;
  startDate: string;
  endDate: string;
  interestRate: number;
  status: DepositStatus;
}
