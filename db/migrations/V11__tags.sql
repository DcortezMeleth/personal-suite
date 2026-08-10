-- Tags are a flat, many-to-many complement to categories: a transaction keeps
-- exactly one category, but can carry any number of tags (e.g. "Portugal
-- 2026") spanning transactions across several different categories — a trip
-- involves Dining, Fuel, and Holidays/Trips all at once, which no single
-- category could capture. Unlike categories, tags don't nest.
CREATE TABLE tags (
    id         UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    name       TEXT NOT NULL UNIQUE,
    color      CHAR(7) NOT NULL DEFAULT '#64748b',
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- Deleting a tag just untags whatever it was attached to — unlike a
-- category, a tag isn't load-bearing classification, so cascading here
-- (rather than blocking, as categories do) is safe.
CREATE TABLE transaction_tags (
    transaction_id UUID NOT NULL REFERENCES transactions(id) ON DELETE CASCADE,
    tag_id         UUID NOT NULL REFERENCES tags(id) ON DELETE CASCADE,
    PRIMARY KEY (transaction_id, tag_id)
);

CREATE INDEX idx_transaction_tags_tag ON transaction_tags(tag_id);
