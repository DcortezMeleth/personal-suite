-- Deleting a teacher, room or room kind that anything still points to failed,
-- every time (REVIEW_2026-11-21 A1).
--
-- These references are two-column keys, (x_id, school_id), so that nothing
-- can point across schools (V9). Their delete action was SET NULL, and on
-- PostgreSQL before 15 SET NULL clears every column of the key, school_id
-- included, which is NOT NULL. So "delete this teacher" became "set this
-- lesson line's school to null", which the database refuses. After an import
-- nearly every teacher is on some line, so the delete button never worked.
--
-- PostgreSQL 15 can say ON DELETE SET NULL (teacher_id). Development runs 14,
-- so instead the keys go back to the default, which refuses to delete a row
-- that is still referenced, and the repositories clear the references
-- themselves inside the same transaction as the delete (TeacherRepository,
-- RoomRepository). A path that forgets to clear them now fails loudly rather
-- than doing something else.
--
-- Single-column keys with SET NULL (individual_lesson.teacher_id,
-- plan_placement.room_id) were never affected and stay as they are.

ALTER TABLE school_class
    DROP CONSTRAINT school_class_homeroom_teacher_id_school_id_fkey,
    ADD CONSTRAINT school_class_homeroom_teacher_id_school_id_fkey
        FOREIGN KEY (homeroom_teacher_id, school_id) REFERENCES teacher (id, school_id);

ALTER TABLE lesson_line
    DROP CONSTRAINT lesson_line_teacher_id_school_id_fkey,
    ADD CONSTRAINT lesson_line_teacher_id_school_id_fkey
        FOREIGN KEY (teacher_id, school_id) REFERENCES teacher (id, school_id),
    DROP CONSTRAINT lesson_line_support_teacher_id_school_id_fkey,
    ADD CONSTRAINT lesson_line_support_teacher_id_school_id_fkey
        FOREIGN KEY (support_teacher_id, school_id) REFERENCES teacher (id, school_id);

ALTER TABLE cross_class_group
    DROP CONSTRAINT cross_class_group_teacher_same_school,
    ADD CONSTRAINT cross_class_group_teacher_same_school
        FOREIGN KEY (teacher_id, school_id) REFERENCES teacher (id, school_id);

ALTER TABLE teacher
    DROP CONSTRAINT teacher_home_room_same_school,
    ADD CONSTRAINT teacher_home_room_same_school
        FOREIGN KEY (home_room_id, school_id) REFERENCES room (id, school_id);

ALTER TABLE subject
    DROP CONSTRAINT subject_room_kind_same_school,
    ADD CONSTRAINT subject_room_kind_same_school
        FOREIGN KEY (required_room_kind_id, school_id) REFERENCES room_kind (id, school_id);

ALTER TABLE cross_class_unit
    DROP CONSTRAINT cross_class_unit_room_kind_same_school,
    ADD CONSTRAINT cross_class_unit_room_kind_same_school
        FOREIGN KEY (required_room_kind_id, school_id) REFERENCES room_kind (id, school_id);
