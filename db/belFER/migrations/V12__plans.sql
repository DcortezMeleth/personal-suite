-- Somewhere to keep a timetable.
--
-- Until now there was nowhere, because there was never a timetable to keep:
-- the solver was asked for one and did not finish, and nothing else produced
-- any. FET does, in seconds, so the question of where it lives has become real.
--
-- A plan is a whole week, kept as a thing in its own right rather than written
-- back over the data it came from. The school builds one twice a year and will
-- want to compare what it has with what it might have, and reverting means
-- marking a different plan current rather than undoing a thousand edits.
CREATE TABLE plan (
    id          UUID  PRIMARY KEY DEFAULT gen_random_uuid(),
    school_id   UUID  NOT NULL REFERENCES school (id) ON DELETE CASCADE,
    -- What the planner calls it: "2026/27 semestr 1", "wariant z późniejszymi
    -- startami". Free text because the distinctions that matter are the ones
    -- the person making them cares about.
    name        TEXT  NOT NULL,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    -- Which program laid it out. Worth recording: a plan from FET and a plan
    -- from our own solver are not interchangeable evidence when something
    -- looks wrong, and we expect to be running both for a while yet.
    made_by     TEXT  NOT NULL,
    -- How long it took, in milliseconds, and what the checker said about it.
    -- Kept with the plan because "eleven of eleven rules" is a property of a
    -- particular week rather than of the school.
    took_millis INTEGER,
    rules_kept  INTEGER,
    rules_total INTEGER
);

-- At most one plan is the one in use. Partial unique index rather than a flag
-- on the school, so that marking a new one current is a single statement and
-- cannot leave two.
CREATE TABLE plan_current (
    school_id UUID PRIMARY KEY REFERENCES school (id) ON DELETE CASCADE,
    plan_id   UUID NOT NULL REFERENCES plan (id) ON DELETE CASCADE
);

-- One lesson, at one hour.
--
-- A placement names what it is in the same two shapes the rest of the model
-- uses: a block of a lesson line, or a block of one group of a cross-class
-- unit. Exactly one of the two sides is filled, which the check below insists
-- on — a placement that is neither, or both, is a bug rather than a state.
CREATE TABLE plan_placement (
    id                   UUID  PRIMARY KEY DEFAULT gen_random_uuid(),
    plan_id              UUID  NOT NULL REFERENCES plan (id) ON DELETE CASCADE,

    lesson_line_id       UUID  REFERENCES lesson_line (id) ON DELETE CASCADE,
    cross_class_group_id UUID  REFERENCES cross_class_group (id) ON DELETE CASCADE,
    -- Which block of that line or unit: a subject taught four times a week is
    -- four blocks, and they are told apart by nothing else.
    block_index          INTEGER NOT NULL,

    day_of_week          INTEGER NOT NULL,
    -- One-based, as the bell schedule is, because this is read by people.
    position             INTEGER NOT NULL,
    room_id              UUID  REFERENCES room (id) ON DELETE SET NULL,

    CONSTRAINT placement_is_one_thing CHECK (
        (lesson_line_id IS NOT NULL) <> (cross_class_group_id IS NOT NULL)
    ),
    CONSTRAINT placement_day_in_week CHECK (day_of_week BETWEEN 1 AND 7),
    CONSTRAINT placement_position_positive CHECK (position >= 1)
);

-- A block is placed once. The database can say so, and saying so here means a
-- half-written plan fails loudly rather than becoming a timetable with a
-- lesson in two places.
CREATE UNIQUE INDEX plan_placement_line_block
    ON plan_placement (plan_id, lesson_line_id, block_index)
    WHERE lesson_line_id IS NOT NULL;

CREATE UNIQUE INDEX plan_placement_cross_block
    ON plan_placement (plan_id, cross_class_group_id, block_index)
    WHERE cross_class_group_id IS NOT NULL;

CREATE INDEX plan_placement_by_plan ON plan_placement (plan_id);
