-- "Car" (added in V6) has no transactions or rules attached yet, so it's safe
-- to replace outright with per-vehicle categories rather than migrate data.
DELETE FROM categories WHERE name = 'Car';

INSERT INTO categories (name, color, icon) VALUES
    ('Car (VW)',   '#1d4ed8', '🚗'),
    ('Car (Audi)', '#b91c1c', '🚙');
