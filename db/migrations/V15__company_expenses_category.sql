-- Flat on purpose: expected to stay low-volume, so no children — the
-- parent/child rollup is there for the Cars/Travels-sized categories.
--
-- No auto-categorisation rule either, unlike V14's withdrawals: company
-- spending isn't identifiable from statement text (the same shops and fuel
-- stations appear in private spending), it's identifiable by the account it
-- lands on, which the text-only rule engine can't key on. Filed by hand, or
-- by filtering the transaction list to the company account.
INSERT INTO categories (name, color, icon) VALUES
    ('Company expenses', '#334155', '🏢');
