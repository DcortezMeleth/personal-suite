INSERT INTO category_rules (category_id, pattern, match_type, priority)
SELECT c.id, r.pattern, r.match_type::rule_match_type, r.priority
FROM (VALUES
    ('Groceries',  'BIEDRONKA',         'CONTAINS', 10),
    ('Groceries',  'LIDL',              'CONTAINS', 10),
    ('Groceries',  'KAUFLAND',          'CONTAINS', 10),
    ('Groceries',  'ZABKA',             'CONTAINS', 10),
    ('Groceries',  'CARREFOUR',         'CONTAINS', 10),
    ('Groceries',  'AUCHAN',            'CONTAINS', 10),
    ('Groceries',  'NETTO',             'CONTAINS', 10),
    ('Fuel',       'ORLEN',             'CONTAINS', 10),
    ('Fuel',       'SHELL',             'CONTAINS', 10),
    ('Fuel',       'LOTOS',             'CONTAINS', 10),
    ('Fuel',       'MOL ',              'CONTAINS', 10),
    ('Dining',     'MCDONALDS',         'CONTAINS', 10),
    ('Dining',     'KFC',               'CONTAINS', 10),
    ('Dining',     'BURGER KING',       'CONTAINS', 10),
    ('Dining',     'RESTAURACJA',       'CONTAINS', 10),
    ('Utilities',  'ENERGA',            'CONTAINS', 10),
    ('Utilities',  'TAURON',            'CONTAINS', 10),
    ('Utilities',  'PGE ',              'CONTAINS', 10),
    ('Utilities',  'VEOLIA',            'CONTAINS', 10),
    ('Utilities',  'ORANGE',            'CONTAINS', 10),
    ('Utilities',  'PLAY',              'CONTAINS', 10),
    ('Transport',  'MPK',               'CONTAINS', 10),
    ('Transport',  'ZTM',               'CONTAINS', 10),
    ('Transport',  'PKP',               'CONTAINS', 10),
    ('Transport',  'INTERCITY',         'CONTAINS', 10),
    ('Transport',  'UBER',              'CONTAINS', 10),
    ('Transport',  'BOLT',              'CONTAINS', 10),
    ('Shopping',   'ALLEGRO',           'CONTAINS', 10),
    ('Shopping',   'AMAZON',            'CONTAINS', 10),
    ('Shopping',   'MEDIA MARKT',       'CONTAINS', 10),
    ('Income',     'WYNAGRODZENIE',     'CONTAINS', 10),
    ('Income',     'PRZELEW PRZYCH',    'CONTAINS', 20),
    ('Income',     'ZWROT',             'CONTAINS', 30)
) AS r(category_name, pattern, match_type, priority)
JOIN categories c ON c.name = r.category_name
ON CONFLICT DO NOTHING;
