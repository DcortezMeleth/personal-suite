INSERT INTO categories (name, color, icon) VALUES
    ('Taxes', '#57534e', '🧾');

-- EXPENSE-scoped: a tax payment going out is unambiguous; not scoping it this
-- way would also risk catching an unrelated incoming transfer that happens to
-- mention the same counterparty text.
-- Note: PIT/VAT are tax *types* within payments to the same "Urzad Skarbowy"
-- counterparty (see the raw statement's /SFP/PIT-36, /SFP/VAT-7 reference
-- codes), not separate merchants — already covered by that one rule, so no
-- separate (and riskier, being short/generic) "PIT"/"VAT" patterns are added.
INSERT INTO category_rules (category_id, pattern, match_type, priority, direction)
SELECT c.id, r.pattern, 'CONTAINS'::rule_match_type, 10, 'EXPENSE'::rule_direction
FROM (VALUES
    ('ZUS'),
    ('Urzad Skarbowy')
) AS r(pattern)
JOIN categories c ON c.name = 'Taxes';

-- Apply immediately to already-imported transactions matching these rules,
-- mirroring what POST /category-rules/:id/reapply (scope=ALL) would do.
UPDATE transactions
SET category_id = (SELECT id FROM categories WHERE name = 'Taxes')
WHERE amount < 0
  AND (
    title ILIKE '%ZUS%' OR counterparty ILIKE '%ZUS%' OR raw_description ILIKE '%ZUS%'
    OR title ILIKE '%Urzad Skarbowy%' OR counterparty ILIKE '%Urzad Skarbowy%' OR raw_description ILIKE '%Urzad Skarbowy%'
  );
