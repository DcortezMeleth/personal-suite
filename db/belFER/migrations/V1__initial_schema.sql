-- belFER is built for one school but the model is multi-tenant from the start,
-- so that supporting a second school later is configuration rather than a
-- migration of every table. Every subsequent table hangs off school.
CREATE TABLE school (
    id         UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    name       TEXT        NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- The bell schedule. Slot times do not affect scheduling at all — the solver
-- only cares how many slots a day has — but they are needed to print the plan,
-- which is why they live here rather than being derived.
CREATE TABLE time_slot (
    id         UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    school_id  UUID NOT NULL REFERENCES school (id) ON DELETE CASCADE,
    position   INT  NOT NULL,
    starts_at  TIME NOT NULL,
    ends_at    TIME NOT NULL,
    UNIQUE (school_id, position),
    CHECK (position >= 1),
    CHECK (ends_at > starts_at)
);
