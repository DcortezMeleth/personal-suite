ALTER TABLE categories ADD COLUMN is_internal BOOLEAN NOT NULL DEFAULT FALSE;

-- One-time backfill: the existing "Internal" category (seeded in V6) was
-- purely cosmetic until now — assigning a transaction to it never actually
-- excluded it from income/spending totals, only the separate auto-detected
-- transfer-pairing flag on transactions did. Flip the flag on the row that
-- already carries that meaning; going forward this is toggled per-category
-- from the category form, not tied to this specific name.
UPDATE categories SET is_internal = true WHERE name = 'Internal';
