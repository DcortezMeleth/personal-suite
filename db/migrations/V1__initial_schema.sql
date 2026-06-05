-- Accounts: bank accounts + investment accounts
CREATE TYPE account_type AS ENUM (
    'CHECKING', 'SAVINGS', 'INVESTMENT', 'IKE', 'IKZE', 'COMPANY'
);

CREATE TYPE account_owner AS ENUM ('SELF', 'WIFE', 'JOINT');

CREATE TABLE accounts (
    id          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    name        TEXT NOT NULL,
    type        account_type NOT NULL,
    institution TEXT NOT NULL,
    currency    CHAR(3) NOT NULL DEFAULT 'PLN',
    owner       account_owner NOT NULL DEFAULT 'SELF',
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- Transaction categories
CREATE TABLE categories (
    id        UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    name      TEXT NOT NULL,
    color     CHAR(7) NOT NULL DEFAULT '#64748b',
    icon      TEXT,
    parent_id UUID REFERENCES categories(id)
);

INSERT INTO categories (name, color, icon) VALUES
    ('Groceries',    '#16a34a', '🛒'),
    ('Fuel',         '#d97706', '⛽'),
    ('Dining',       '#f97316', '🍽'),
    ('Hobbies',      '#8b5cf6', '🎮'),
    ('Utilities',    '#0ea5e9', '💡'),
    ('Healthcare',   '#ec4899', '💊'),
    ('Transport',    '#6366f1', '🚌'),
    ('Shopping',     '#ef4444', '🛍'),
    ('Income',       '#16a34a', '💰'),
    ('Other',        '#94a3b8', '📦');

-- Auto-categorisation rules
CREATE TYPE rule_match_type AS ENUM ('CONTAINS', 'REGEX', 'EXACT');

CREATE TABLE category_rules (
    id          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    category_id UUID NOT NULL REFERENCES categories(id),
    pattern     TEXT NOT NULL,
    match_type  rule_match_type NOT NULL DEFAULT 'CONTAINS',
    priority    INT NOT NULL DEFAULT 100,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- Bank transactions
CREATE TABLE transactions (
    id                  UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    account_id          UUID NOT NULL REFERENCES accounts(id),
    date                DATE NOT NULL,
    amount              NUMERIC(15, 2) NOT NULL,
    currency            CHAR(3) NOT NULL DEFAULT 'PLN',
    description         TEXT NOT NULL,
    raw_description     TEXT NOT NULL,
    category_id         UUID REFERENCES categories(id),
    is_internal_transfer BOOLEAN NOT NULL DEFAULT FALSE,
    transfer_peer_id    UUID REFERENCES transactions(id),
    imported_at         TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_transactions_account_date ON transactions(account_id, date DESC);
CREATE INDEX idx_transactions_category     ON transactions(category_id);
CREATE INDEX idx_transactions_date         ON transactions(date DESC);

-- Monthly budget limits per category
CREATE TABLE budgets (
    id                   UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    category_id          UUID NOT NULL REFERENCES categories(id),
    monthly_limit        NUMERIC(15, 2) NOT NULL,
    alert_threshold_pct  INT NOT NULL DEFAULT 80,
    UNIQUE (category_id)
);

-- Investment instruments (ETFs, stocks, bonds, deposits, funds)
CREATE TYPE instrument_type AS ENUM (
    'ETF', 'STOCK', 'TREASURY_BOND', 'DEPOSIT', 'FUND'
);

CREATE TABLE instruments (
    id       UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    symbol   TEXT,
    name     TEXT NOT NULL,
    type     instrument_type NOT NULL,
    currency CHAR(3) NOT NULL DEFAULT 'PLN'
);

-- Current investment positions (snapshot updated on each import)
CREATE TABLE investment_positions (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    account_id      UUID NOT NULL REFERENCES accounts(id),
    instrument_id   UUID NOT NULL REFERENCES instruments(id),
    quantity        NUMERIC(20, 8) NOT NULL DEFAULT 0,
    avg_buy_price   NUMERIC(15, 4),
    current_price   NUMERIC(15, 4),
    last_updated    TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (account_id, instrument_id)
);

-- Investment transaction history
CREATE TYPE investment_tx_type AS ENUM (
    'BUY', 'SELL', 'DIVIDEND', 'INTEREST', 'COUPON', 'MATURITY'
);

CREATE TABLE investment_transactions (
    id            UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    account_id    UUID NOT NULL REFERENCES accounts(id),
    instrument_id UUID NOT NULL REFERENCES instruments(id),
    date          DATE NOT NULL,
    type          investment_tx_type NOT NULL,
    quantity      NUMERIC(20, 8),
    price         NUMERIC(15, 4),
    fees          NUMERIC(15, 2) NOT NULL DEFAULT 0,
    amount_pln    NUMERIC(15, 2) NOT NULL,
    notes         TEXT,
    created_at    TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_inv_tx_account_date ON investment_transactions(account_id, date DESC);

-- Treasury bonds (Polish: obligacje skarbowe)
CREATE TABLE treasury_bonds (
    id            UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    account_id    UUID NOT NULL REFERENCES accounts(id),
    series        TEXT NOT NULL,
    nominal_value NUMERIC(15, 2) NOT NULL,
    quantity      INT NOT NULL,
    purchase_date DATE NOT NULL,
    maturity_date DATE NOT NULL,
    interest_type TEXT NOT NULL
);

-- Bank deposits (lokaty)
CREATE TYPE deposit_status AS ENUM ('ACTIVE', 'MATURED', 'BROKEN');

CREATE TABLE deposits (
    id            UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    account_id    UUID NOT NULL REFERENCES accounts(id),
    amount        NUMERIC(15, 2) NOT NULL,
    currency      CHAR(3) NOT NULL DEFAULT 'PLN',
    start_date    DATE NOT NULL,
    end_date      DATE NOT NULL,
    interest_rate NUMERIC(7, 4) NOT NULL,
    status        deposit_status NOT NULL DEFAULT 'ACTIVE'
);

-- Inflation reference data (fetched from Eurostat monthly)
CREATE TABLE inflation_data (
    id      UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    year    INT NOT NULL,
    month   INT NOT NULL CHECK (month BETWEEN 1 AND 12),
    cpi_pct NUMERIC(7, 4) NOT NULL,
    source  TEXT NOT NULL DEFAULT 'eurostat',
    UNIQUE (year, month)
);
