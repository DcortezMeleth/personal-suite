-- Free-text, per-transaction annotation (e.g. an invoice number or what was
-- actually purchased) to help categorize otherwise-ambiguous transactions.
-- Fully independent of raw_description/title/counterparty: never touched by
-- the backfill, never used for rule matching (it's inherently one-off, not
-- a generalizable pattern) — purely a human-authored memory aid, searchable
-- alongside title/counterparty.
ALTER TABLE transactions ADD COLUMN notes TEXT;
