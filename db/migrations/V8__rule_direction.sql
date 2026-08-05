-- Restricts a rule to one cash-flow direction: the same counterparty can mean
-- different things depending on which way money moved (e.g. a merchant refund
-- arriving vs. paying that merchant). Existing rules default to ANY (unchanged
-- behaviour) since none of the seeded/auto-generated rules were direction-aware.
CREATE TYPE rule_direction AS ENUM ('ANY', 'INCOME', 'EXPENSE');

ALTER TABLE category_rules
    ADD COLUMN direction rule_direction NOT NULL DEFAULT 'ANY';
