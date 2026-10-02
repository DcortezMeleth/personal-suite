-- School-level scheduling configuration.
--
-- Deliberately absent: a max-lessons-per-day setting. It is derived from the
-- number of configured time slots rather than stored, because two sources of
-- truth for the same number is how a plan ends up legal by one measure and
-- illegal by the other.
--
-- The teacher defaults live here rather than being repeated on every teacher.
-- A teacher row overrides them only when it differs, which for this school is
-- about fifteen of eighty.
ALTER TABLE school
    ADD COLUMN years                              INT     NOT NULL DEFAULT 4,
    ADD COLUMN allow_class_gaps                   BOOLEAN NOT NULL DEFAULT FALSE,
    ADD COLUMN max_consecutive_teacher_gaps       INT     NOT NULL DEFAULT 2,
    ADD COLUMN default_teacher_max_working_days   INT     NOT NULL DEFAULT 5,
    ADD COLUMN default_teacher_max_lessons_per_day INT    NOT NULL DEFAULT 8;

ALTER TABLE school
    ADD CONSTRAINT school_years_sane
        CHECK (years BETWEEN 1 AND 12),
    ADD CONSTRAINT school_teacher_gaps_sane
        CHECK (max_consecutive_teacher_gaps >= 0),
    ADD CONSTRAINT school_working_days_sane
        CHECK (default_teacher_max_working_days BETWEEN 1 AND 7),
    ADD CONSTRAINT school_teacher_lessons_sane
        CHECK (default_teacher_max_lessons_per_day >= 1);
