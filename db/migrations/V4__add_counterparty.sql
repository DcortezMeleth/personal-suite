-- Counterparty (payee/payer) name, distinct from `description` (transaction title/purpose).
-- Populated at import time from structured statement fields; NULL when the source
-- format has no separate counterparty concept (e.g. a card purchase's merchant is
-- already the whole story, so it stays in `description` only).
ALTER TABLE transactions
    ADD COLUMN counterparty TEXT;
