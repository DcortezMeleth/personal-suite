-- A class can be allocated the same subject more than once, with a different
-- teacher each time.
--
-- The old constraint assumed one teacher per class and subject, which holds for
-- ordinary lessons but not for "zajęcia rozwijające zainteresowania i
-- uzdolnienia": the real arkusz gives seven classes two to four of those each,
-- seventeen different teachers between them. Under the old rule the importer
-- had to merge them, which quietly threw away every teacher but the first.
--
-- The teacher is therefore part of what makes an allocation distinct. Two rows
-- that agree on all five still collide, so genuine double entry is still
-- caught — except where the teacher is unassigned, since Postgres 14 treats
-- NULLs as distinct and cannot be told otherwise.
ALTER TABLE lesson_line DROP CONSTRAINT lesson_line_class_id_subject_id_audience_kind_key;
ALTER TABLE lesson_line ADD CONSTRAINT lesson_line_allocation_unique
    UNIQUE (class_id, subject_id, audience, kind, teacher_id);
