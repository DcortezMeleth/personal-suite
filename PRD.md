# delFIN — Product Requirements Document

## Overview

delFIN is a single-user personal financial management system self-hosted on a home lab. It covers two primary domains — spending analysis and investment portfolio tracking — with a third AI-assisted strategy domain planned for a later phase.

The system is built exclusively for one household (the owner and his wife). There are no other users, no multitenancy, no public access, and no authentication requirements for the initial version.

---

## Constraints & Non-goals

| Constraint | Detail |
|---|---|
| Single user | No multi-user support, no accounts/roles |
| LAN only | Hosted inside home network; never exposed to the internet |
| No auth | No login, no HTTPS required for initial version |
| No mobile app | Web UI only; responsive layout is nice-to-have |
| No real-time trading | Read-only view of investments; no order execution |

---

## Module 1 — Spending Analysis

### Data ingestion

- **PKO BP** personal account: manual export from iPKO in MT940 format, uploaded via UI
- **Alior Bank** company account: manual export (CSV), uploaded via UI
- Both bank accounts belong to the same household; transfers between them must be excluded from income/spending totals

### Inter-account transfer detection

- When the same amount appears as a debit in one account and a credit in another within ±2 days, both transactions are automatically flagged as an internal transfer (`is_internal_transfer = true`)
- Both sides of the transfer are excluded from all budget and spending calculations
- The user can confirm or break the link manually

### Categorisation

- Each transaction is assigned to exactly one category (e.g. Groceries, Fuel, Dining, Hobbies, Utilities, Healthcare, Transport, Shopping, Income, Other)
- Categories are hierarchical: a parent category can have subcategories
- Auto-categorisation uses a rule engine: rules match `raw_description` by CONTAINS, REGEX, or EXACT; first matching rule wins
- Users can override any category; overrides can optionally create or update a rule for future imports
- Default rules are seeded on first run

### Dashboard

- Monthly spending by category — pie chart + bar chart
- Monthly trend per category — line chart over the last 12 months
- Month-over-month comparison table
- Top transactions list (sortable by amount)
- Summary tiles: total spent, total income, net cashflow, delta vs previous month

### Budgets & alerts

- Users can set a monthly spending limit per category
- Each budget has a warning threshold (default 80 % of limit)
- An alert banner appears in the UI when any category has exceeded its warning threshold or its hard limit
- Alerts are evaluated on each import and on a daily background check

---

## Module 2 — Investment Portfolio

### Accounts tracked

| Account type | Owner | Data source |
|---|---|---|
| XTB investment account | Self | CSV export from xStation |
| BOS (Dom Maklerski BOŚ) | Self | bossaAPI or CSV export |
| IKE (retirement) | Self | Manual entry |
| IKZE (retirement) | Self | Manual entry |
| IKE (retirement) | Wife | Manual entry |
| IKZE (retirement) | Wife | Manual entry |
| Polish treasury bonds | Self + Wife | Manual entry; rates from dane.gov.pl |
| Bank deposits (lokaty) | Self + Wife | Manual entry |

*Note: XTB's xStation API was discontinued in March 2025. Only file exports are available.*

### Portfolio views

- **Owner filter**: show Self only / Wife only / Combined family view
- **Aggregated view**: total current value, total invested (cost basis), total gain/loss in PLN and %
- **By account**: breakdown of value per account
- **By instrument type**: ETFs, stocks, bonds, deposits, funds
- **Per instrument detail**: name, quantity, avg buy price, current price, P&L

### Investment transaction history

For each instrument, record: date, type (BUY / SELL / DIVIDEND / INTEREST / COUPON / MATURITY), quantity, price, fees, total amount in PLN.

### Interest & accrual

- Treasury bonds: interest calculated server-side at query time using the series-specific formula (COI, EDO, ROS, OTS, etc.); current series rates fetched from `dane.gov.pl`
- Bank deposits: interest accrued linearly from start date to today (or maturity date if passed)
- Both are computed on the fly — no snapshot storage needed

### Inflation comparison

- Monthly CPI data (Poland) fetched from the Eurostat REST API (no auth required)
- Portfolio total return chart overlays cumulative CPI as a reference line
- Goal: show whether investments are beating inflation

---

## Module 3 — Investment Strategy (Future Phase)

*Not in scope for initial build. Defined here for awareness.*

- User defines a target asset allocation (% per asset class or instrument)
- System compares current allocation vs target and generates a rebalancing action list
- A Claude API conversational agent lets the user review and refine the strategy in natural language, especially when market conditions change

---

## Data Model Summary

See `db/migrations/V1__initial_schema.sql` for the full schema. Key tables:

`accounts` · `transactions` · `categories` · `category_rules` · `budgets` · `instruments` · `investment_positions` · `investment_transactions` · `treasury_bonds` · `deposits` · `inflation_data`

---

## Verification Checklist (Phase 1)

- [ ] `bazel build //...` passes
- [ ] `docker compose up postgres -d` starts postgres with schema applied
- [ ] Backend `/health` returns `{"status":"ok","service":"delFIN"}`
- [ ] Frontend dev server starts at `localhost:3000` and renders spending + investment pages

## Verification Checklist (Phase 2 — Spending)

- [ ] Upload a PKO MT940 file → transactions imported and displayed
- [ ] Inter-account transfer between PKO and Alior detected and excluded
- [ ] Transactions auto-categorised by rules; manual override persists
- [ ] Creating a category rule re-categorises matching existing transactions
- [ ] Budget limit set; breach triggers alert banner on next import

## Verification Checklist (Phase 3 — Investments)

- [ ] XTB CSV import creates positions and transaction history
- [ ] Treasury bond position shows correct accrued value
- [ ] Bank deposit shows correct accrued interest
- [ ] Portfolio dashboard shows total value, cost basis, P&L
- [ ] Inflation overlay appears on the portfolio chart
