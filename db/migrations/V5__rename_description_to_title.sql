-- `description` never had a clean single meaning: it mixed the transaction's
-- purpose/title with the counterparty name depending on import format. Now
-- that `counterparty` is a distinct column, this column is unambiguously a
-- title (payment purpose / merchant name), so name it that.
ALTER TABLE transactions RENAME COLUMN description TO title;
