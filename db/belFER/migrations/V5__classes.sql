-- Classes.
--
-- Named by year and letter ("1A", "3F"), but neither is as regular as it looks:
-- the real arkusz has no 1B, and the years hold 7, 7, 4 and 7 classes. So the
-- letters are stored rather than derived, and nothing assumes they are
-- contiguous or that years are the same size.
--
-- Student counts do not constrain the solver — it never needs to know who is in
-- a group, only which classes take part. They are kept so that importing the
-- arkusz loses nothing, and because the girls' count is what the school uses
-- when forming PE groups.

-- Lets a class's homeroom teacher be constrained to the same school, below.
ALTER TABLE teacher ADD CONSTRAINT teacher_id_school_unique UNIQUE (id, school_id);

CREATE TABLE school_class (
    id                  UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    school_id           UUID NOT NULL REFERENCES school (id) ON DELETE CASCADE,
    year                INT  NOT NULL,
    letter              TEXT NOT NULL,
    -- The profile the school advertises: "MAT-FIZ-INF", "BIOL-CHEM".
    specialisation      TEXT,
    homeroom_teacher_id UUID,
    student_count       INT,
    girl_count          INT,

    UNIQUE (school_id, year, letter),
    CONSTRAINT class_year_sane    CHECK (year >= 1),
    CONSTRAINT class_letter_sane  CHECK (letter <> ''),
    CONSTRAINT class_counts_sane  CHECK (
        (student_count IS NULL OR student_count >= 0) AND
        (girl_count IS NULL OR girl_count >= 0) AND
        (student_count IS NULL OR girl_count IS NULL OR girl_count <= student_count)
    ),

    -- Composite rather than a plain reference to teacher(id): a wychowawca from
    -- another school would otherwise be accepted, and the model is meant to
    -- survive a second school being added.
    FOREIGN KEY (homeroom_teacher_id, school_id)
        REFERENCES teacher (id, school_id) ON DELETE SET NULL
);
