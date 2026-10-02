-- What each class is taught, by whom, and in what shaped blocks.
--
-- A "lesson line" is one row of the principal's allocation: this class, this
-- subject, this audience, this many hours arranged this way. Several lines can
-- share a class and subject, which is what makes two things expressible that
-- the school's current software cannot state at all:
--
--   * Whole-class and split teaching of the SAME subject at once — an extended
--     subject whose lab only holds half a class gets a WHOLE_CLASS line and a
--     pair of group lines, rather than being forced to pick one.
--
--   * Base and extension hours as ONE subject. The arkusz codes them as two
--     ("matematyka" and "r_matematyka") and the school says that is wrong; here
--     they are two lines on one subject, free to have different teachers.
--
-- `blocks` is the shape, not just the total: {1,1,2} means four hours as two
-- singles and one double. The solver places blocks, so this is the unit the
-- whole model is expressed in — and a subject's hours cannot be derived from a
-- count alone.
CREATE TYPE lesson_audience AS ENUM ('WHOLE_CLASS', 'GROUP_1', 'GROUP_2');
CREATE TYPE lesson_kind AS ENUM ('BASE', 'EXTENSION');

CREATE TABLE lesson_line (
    id                 UUID            PRIMARY KEY DEFAULT gen_random_uuid(),
    school_id          UUID            NOT NULL REFERENCES school (id) ON DELETE CASCADE,
    class_id           UUID            NOT NULL REFERENCES school_class (id) ON DELETE CASCADE,
    subject_id         UUID            NOT NULL REFERENCES subject (id) ON DELETE CASCADE,
    audience           lesson_audience NOT NULL,
    kind               lesson_kind     NOT NULL DEFAULT 'BASE',
    -- Nullable: the arkusz arrives with some allocations not yet assigned to
    -- anyone, and refusing to store them would mean refusing to import.
    teacher_id         UUID,
    -- The nauczyciel wspomagający, present for every hour of this line and
    -- therefore unable to teach anything else during them.
    support_teacher_id UUID,
    blocks             INT[]           NOT NULL,

    -- One line per audience and kind: the same class cannot have two separate
    -- base allocations of one subject for the same audience.
    UNIQUE (class_id, subject_id, audience, kind),

    CONSTRAINT lesson_line_blocks_present CHECK (array_length(blocks, 1) >= 1),
    -- A literal array rather than generate_series, which Postgres rejects as a
    -- subquery inside a check. This only catches nonsense like a zero or
    -- negative block; the real bound is the length of the school day, which the
    -- database cannot see and the application checks.
    CONSTRAINT lesson_line_blocks_positive
        CHECK (blocks <@ ARRAY[1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12]),

    -- Composite, so a teacher from another school cannot be allocated here.
    FOREIGN KEY (teacher_id, school_id)
        REFERENCES teacher (id, school_id) ON DELETE SET NULL,
    FOREIGN KEY (support_teacher_id, school_id)
        REFERENCES teacher (id, school_id) ON DELETE SET NULL
);

CREATE INDEX lesson_line_class ON lesson_line (class_id);
