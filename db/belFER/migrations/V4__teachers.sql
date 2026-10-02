-- Teachers, what they teach, and when they cannot.
--
-- The per-teacher limits are nullable on purpose: NULL means "use the school
-- default". About fifteen of eighty teachers differ, so storing the default
-- eighty times would make changing it a migration rather than a setting.
--
-- `pensum` is the contracted weekly hours from the arkusz. It does not
-- constrain the solver; it is what the Phase 1.8 sanity check compares the
-- assigned hours against, and keeping it makes the import lossless.
CREATE TABLE teacher (
    id                  UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    school_id           UUID NOT NULL REFERENCES school (id) ON DELETE CASCADE,
    -- The short code the arkusz joins on ("KC", "SU"). Unique per school.
    code                TEXT NOT NULL,
    first_name          TEXT NOT NULL,
    last_name           TEXT NOT NULL,
    -- A teacher's own room is a preference the solver weighs, not a
    -- reservation: several teachers may name the same one, and some have none.
    home_room_id        UUID REFERENCES room (id) ON DELETE SET NULL,
    max_working_days    INT,
    max_lessons_per_day INT,
    pensum              INT,
    UNIQUE (school_id, code),
    CONSTRAINT teacher_working_days_sane
        CHECK (max_working_days IS NULL OR max_working_days BETWEEN 1 AND 7),
    CONSTRAINT teacher_lessons_sane
        CHECK (max_lessons_per_day IS NULL OR max_lessons_per_day >= 1),
    CONSTRAINT teacher_pensum_sane
        CHECK (pensum IS NULL OR pensum >= 0)
);

CREATE TABLE teacher_subject (
    teacher_id UUID NOT NULL REFERENCES teacher (id) ON DELETE CASCADE,
    subject_id UUID NOT NULL REFERENCES subject (id) ON DELETE CASCADE,
    PRIMARY KEY (teacher_id, subject_id)
);

-- Blocked slots repeat every week, because every week is identical as far as
-- the timetable is concerned. Stored as ranges rather than one row per slot:
-- "Tuesday, lessons 1-4" is how the school states it, and it survives the
-- bell schedule gaining a lesson at the end.
CREATE TABLE teacher_unavailability (
    id            UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    teacher_id    UUID NOT NULL REFERENCES teacher (id) ON DELETE CASCADE,
    day_of_week   INT  NOT NULL,
    from_position INT  NOT NULL,
    to_position   INT  NOT NULL,
    CONSTRAINT unavailability_day_sane  CHECK (day_of_week BETWEEN 1 AND 7),
    CONSTRAINT unavailability_range_sane CHECK (from_position >= 1 AND to_position >= from_position)
);

CREATE INDEX teacher_unavailability_teacher ON teacher_unavailability (teacher_id);
