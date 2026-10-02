-- PE, which does not fit the class-shaped model the rest of the plan uses.
--
-- Groups are formed from students, not classes: a class's boys may train alone
-- while its girls join another class, and one class's girls can be split across
-- two partner classes. So a group names the classes it draws from, and a unit
-- is the set of groups that must run at the same time — because every class
-- taking part has all of its students in one group or another, and none of them
-- can be anywhere else during those hours.
--
-- The school decides who is in which group; belFER never needs to know. Which
-- classes take part and how many groups there are is enough to schedule it,
-- which is what keeps this tractable.
CREATE TABLE pe_unit (
    id                    UUID  PRIMARY KEY DEFAULT gen_random_uuid(),
    school_id             UUID  NOT NULL REFERENCES school (id) ON DELETE CASCADE,
    subject_id            UUID  NOT NULL REFERENCES subject (id) ON DELETE CASCADE,
    -- What the school calls this arrangement: "WF rocznik 1", "WF 2C+2D".
    name                  TEXT  NOT NULL,
    -- Every group needs a room of this kind at once, so the number of rooms
    -- carrying it is a hard ceiling on how many groups can run together.
    required_room_kind_id UUID  REFERENCES room_kind (id) ON DELETE SET NULL,
    blocks                INT[] NOT NULL,

    CONSTRAINT pe_unit_blocks_present  CHECK (array_length(blocks, 1) >= 1),
    CONSTRAINT pe_unit_blocks_positive CHECK (blocks <@ ARRAY[1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12])
);

CREATE TABLE pe_group (
    id         UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    unit_id    UUID NOT NULL REFERENCES pe_unit (id) ON DELETE CASCADE,
    school_id  UUID NOT NULL REFERENCES school (id) ON DELETE CASCADE,
    -- "chłopcy 1A", "dziewczęta 1A+1B". Shown on the printed plan; the split's
    -- meaning does not otherwise matter to the solver.
    label      TEXT NOT NULL,
    teacher_id UUID,

    FOREIGN KEY (teacher_id, school_id)
        REFERENCES teacher (id, school_id) ON DELETE SET NULL
);

-- Which classes a group draws from. One row per class, so a group spanning two
-- classes and a group inside one class are the same shape.
CREATE TABLE pe_group_class (
    group_id UUID NOT NULL REFERENCES pe_group (id) ON DELETE CASCADE,
    class_id UUID NOT NULL REFERENCES school_class (id) ON DELETE CASCADE,
    PRIMARY KEY (group_id, class_id)
);

CREATE INDEX pe_group_unit ON pe_group (unit_id);
