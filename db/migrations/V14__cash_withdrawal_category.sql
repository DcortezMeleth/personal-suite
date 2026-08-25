INSERT INTO categories (name, color, icon) VALUES
    ('Cash withdrawal', '#0d9488', '🏧');

-- Keyed off the statement itself rather than off the merchant: Mt940Parser
-- keeps the entry's own transaction-type label as the first line of
-- raw_description, and PKO's label for an ATM withdrawal is literally
-- "Wyplata w bankomacie" ("Wyplata w bankomacie BLIK" for the BLIK variant,
-- which CONTAINS covers too). That makes it independent of which ATM operator
-- the cash came out of — the <27/<32 operator subfields differ per machine
-- ("Bankomat Euronet", a bank's own, none at all for a plain card entry),
-- while the label does not.
--
-- EXPENSE-scoped: cash only ever leaves the account this way. Paying cash in
-- is labelled "Wplata w bankomacie" — no "y" — which this pattern already
-- misses, and the scope keeps it missed if a bank words it differently.
INSERT INTO category_rules (category_id, pattern, match_type, priority, direction)
SELECT c.id, 'Wyplata w bankomacie', 'CONTAINS'::rule_match_type, 10, 'EXPENSE'::rule_direction
FROM categories c WHERE c.name = 'Cash withdrawal';

-- Apply to already-imported withdrawals, mirroring what
-- POST /category-rules/:id/reapply does with scope=UNCATEGORIZED_ONLY.
-- Deliberately not scope=ALL: anything already filed by hand stays put.
UPDATE transactions
SET category_id = (SELECT id FROM categories WHERE name = 'Cash withdrawal')
WHERE category_id IS NULL
  AND amount < 0
  AND (title ILIKE '%Wyplata w bankomacie%'
       OR counterparty ILIKE '%Wyplata w bankomacie%'
       OR raw_description ILIKE '%Wyplata w bankomacie%');
