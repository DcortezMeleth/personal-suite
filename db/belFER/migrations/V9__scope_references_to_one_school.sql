-- Make every cross-entity reference school-scoped.
--
-- Teacher references were already composite — a class could not take a
-- wychowawca from another school — but nothing else was, so a lesson line
-- could name another school's class or subject, and a subject could require
-- another school's room kind. The model is meant to survive a second school,
-- and a rule the database enforces is worth more than one the UI merely avoids
-- offering. Doing this with one school's worth of data is as cheap as it gets.
--
-- Join tables carry school_id for the same reason: it is the only way to state
-- "both sides belong to the same school" as a constraint rather than a hope.

ALTER TABLE school_class ADD CONSTRAINT school_class_id_school_unique UNIQUE (id, school_id);
ALTER TABLE subject      ADD CONSTRAINT subject_id_school_unique      UNIQUE (id, school_id);
ALTER TABLE room         ADD CONSTRAINT room_id_school_unique         UNIQUE (id, school_id);
ALTER TABLE room_kind    ADD CONSTRAINT room_kind_id_school_unique    UNIQUE (id, school_id);

-- ── Simple references ───────────────────────────────────────────────────────

ALTER TABLE teacher DROP CONSTRAINT teacher_home_room_id_fkey;
ALTER TABLE teacher ADD CONSTRAINT teacher_home_room_same_school
    FOREIGN KEY (home_room_id, school_id) REFERENCES room (id, school_id) ON DELETE SET NULL;

ALTER TABLE subject DROP CONSTRAINT subject_required_room_kind_id_fkey;
ALTER TABLE subject ADD CONSTRAINT subject_room_kind_same_school
    FOREIGN KEY (required_room_kind_id, school_id) REFERENCES room_kind (id, school_id) ON DELETE SET NULL;

ALTER TABLE lesson_line DROP CONSTRAINT lesson_line_class_id_fkey;
ALTER TABLE lesson_line ADD CONSTRAINT lesson_line_class_same_school
    FOREIGN KEY (class_id, school_id) REFERENCES school_class (id, school_id) ON DELETE CASCADE;

ALTER TABLE lesson_line DROP CONSTRAINT lesson_line_subject_id_fkey;
ALTER TABLE lesson_line ADD CONSTRAINT lesson_line_subject_same_school
    FOREIGN KEY (subject_id, school_id) REFERENCES subject (id, school_id) ON DELETE CASCADE;

ALTER TABLE cross_class_unit DROP CONSTRAINT pe_unit_subject_id_fkey;
ALTER TABLE cross_class_unit ADD CONSTRAINT cross_class_unit_subject_same_school
    FOREIGN KEY (subject_id, school_id) REFERENCES subject (id, school_id) ON DELETE CASCADE;

ALTER TABLE cross_class_unit DROP CONSTRAINT pe_unit_required_room_kind_id_fkey;
ALTER TABLE cross_class_unit ADD CONSTRAINT cross_class_unit_room_kind_same_school
    FOREIGN KEY (required_room_kind_id, school_id) REFERENCES room_kind (id, school_id) ON DELETE SET NULL;

-- ── Join tables ─────────────────────────────────────────────────────────────

ALTER TABLE teacher_subject ADD COLUMN school_id UUID;
UPDATE teacher_subject ts SET school_id = t.school_id FROM teacher t WHERE t.id = ts.teacher_id;
ALTER TABLE teacher_subject ALTER COLUMN school_id SET NOT NULL;
ALTER TABLE teacher_subject DROP CONSTRAINT teacher_subject_teacher_id_fkey;
ALTER TABLE teacher_subject DROP CONSTRAINT teacher_subject_subject_id_fkey;
ALTER TABLE teacher_subject ADD CONSTRAINT teacher_subject_teacher_same_school
    FOREIGN KEY (teacher_id, school_id) REFERENCES teacher (id, school_id) ON DELETE CASCADE;
ALTER TABLE teacher_subject ADD CONSTRAINT teacher_subject_subject_same_school
    FOREIGN KEY (subject_id, school_id) REFERENCES subject (id, school_id) ON DELETE CASCADE;

ALTER TABLE room_kind_assignment ADD COLUMN school_id UUID;
UPDATE room_kind_assignment a SET school_id = r.school_id FROM room r WHERE r.id = a.room_id;
ALTER TABLE room_kind_assignment ALTER COLUMN school_id SET NOT NULL;
ALTER TABLE room_kind_assignment DROP CONSTRAINT room_kind_assignment_room_id_fkey;
ALTER TABLE room_kind_assignment DROP CONSTRAINT room_kind_assignment_room_kind_id_fkey;
ALTER TABLE room_kind_assignment ADD CONSTRAINT room_kind_assignment_room_same_school
    FOREIGN KEY (room_id, school_id) REFERENCES room (id, school_id) ON DELETE CASCADE;
ALTER TABLE room_kind_assignment ADD CONSTRAINT room_kind_assignment_kind_same_school
    FOREIGN KEY (room_kind_id, school_id) REFERENCES room_kind (id, school_id) ON DELETE CASCADE;

ALTER TABLE cross_class_group_class ADD COLUMN school_id UUID;
UPDATE cross_class_group_class gc SET school_id = g.school_id
    FROM cross_class_group g WHERE g.id = gc.group_id;
ALTER TABLE cross_class_group_class ALTER COLUMN school_id SET NOT NULL;
ALTER TABLE cross_class_group_class DROP CONSTRAINT pe_group_class_class_id_fkey;
ALTER TABLE cross_class_group_class ADD CONSTRAINT cross_class_group_class_same_school
    FOREIGN KEY (class_id, school_id) REFERENCES school_class (id, school_id) ON DELETE CASCADE;

-- Renaming a table does not rename its constraints, so the cross-class tables
-- were still carrying pe_* constraint names from before V8. Harmless until
-- someone reads an error message and goes looking for a table that no longer
-- exists.
ALTER TABLE cross_class_unit       RENAME CONSTRAINT pe_unit_school_id_fkey            TO cross_class_unit_school_fkey;
ALTER TABLE cross_class_group      RENAME CONSTRAINT pe_group_school_id_fkey           TO cross_class_group_school_fkey;
ALTER TABLE cross_class_group      RENAME CONSTRAINT pe_group_unit_id_fkey             TO cross_class_group_unit_fkey;
ALTER TABLE cross_class_group      RENAME CONSTRAINT pe_group_teacher_id_school_id_fkey TO cross_class_group_teacher_same_school;
ALTER TABLE cross_class_group_class RENAME CONSTRAINT pe_group_class_group_id_fkey     TO cross_class_group_class_group_fkey;
