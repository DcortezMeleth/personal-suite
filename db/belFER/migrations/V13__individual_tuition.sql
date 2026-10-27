-- Nauczanie indywidualne: one student, taught alone.
--
-- The arkusz carries these as pseudo-classes whose code is the student's class
-- followed by their initials, and whose name is their full name beside their
-- provision. The import has skipped them from the start, which kept the data
-- out but did not keep the hours out: they are taught by the same people who
-- teach the classes, so a teacher with five hours of tuition has five fewer
-- for everything else and the timetable has not known it.
--
-- Nothing here identifies the student. Which class they are in is needed —
-- their tuition belongs to that class's year and the planner has to find it —
-- but their initials are not, so a plan is numbered within its class instead.
-- The anonymiser rebuilds these records for exactly this reason; the database
-- should not need anonymising.
CREATE TABLE individual_plan (
    id         UUID  PRIMARY KEY DEFAULT gen_random_uuid(),
    school_id  UUID  NOT NULL REFERENCES school (id) ON DELETE CASCADE,
    class_id   UUID  NOT NULL REFERENCES school_class (id) ON DELETE CASCADE,
    -- First, second, third such plan in that class. Enough to tell two apart,
    -- which is all the planner needs and all we are willing to keep.
    ordinal    INTEGER NOT NULL,

    UNIQUE (class_id, ordinal)
);

-- What that student is taught, by whom, and how much of it.
CREATE TABLE individual_lesson (
    id         UUID  PRIMARY KEY DEFAULT gen_random_uuid(),
    plan_id    UUID  NOT NULL REFERENCES individual_plan (id) ON DELETE CASCADE,
    subject_id UUID  NOT NULL REFERENCES subject (id) ON DELETE CASCADE,
    teacher_id UUID  REFERENCES teacher (id) ON DELETE SET NULL,
    -- As lesson lines do: {1,1,2} is four hours as two singles and a double.
    blocks     INTEGER[] NOT NULL,

    CONSTRAINT individual_lesson_has_hours CHECK (cardinality(blocks) > 0)
);

CREATE INDEX individual_lesson_by_plan ON individual_lesson (plan_id);

-- A placement can now be a block of one of these too.
ALTER TABLE plan_placement
    ADD COLUMN individual_lesson_id UUID REFERENCES individual_lesson (id) ON DELETE CASCADE;

-- Exactly one of the three, as before.
ALTER TABLE plan_placement DROP CONSTRAINT placement_is_one_thing;
ALTER TABLE plan_placement ADD CONSTRAINT placement_is_one_thing CHECK (
    (lesson_line_id IS NOT NULL)::int
  + (cross_class_group_id IS NOT NULL)::int
  + (individual_lesson_id IS NOT NULL)::int = 1
);

CREATE UNIQUE INDEX plan_placement_individual_block
    ON plan_placement (plan_id, individual_lesson_id, block_index)
    WHERE individual_lesson_id IS NOT NULL;

-- The rooms the school keeps for it. A kind rather than a flag, so that the
-- six of them are offered to this and to nothing else, exactly as the sports
-- hall is.
INSERT INTO room_kind (school_id, name)
SELECT id, 'Nauczanie indywidualne' FROM school
ON CONFLICT DO NOTHING;
