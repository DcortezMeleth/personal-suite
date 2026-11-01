-- How many of a class actually attend each optional subject.
--
-- Not bookkeeping: it is what makes the week legal. A pupil who attends
-- neither religia nor etyka must not be left with a free hour in the middle of
-- the day, and the only way we had to promise that was to force every optional
-- lesson to end its class's day. That reading is stricter than the school's
-- own rule — "first or last, whichever suits" — and it is what made the
-- timetable impossible: measured on the real school it placed 432 activities
-- of 1331 and then never moved again, for fifteen hours.
--
-- Told who attends, FET works it out itself. It sees the pupils who are not in
-- the lesson, applies the no-gaps rule it already has to them, and arrives at
-- an edge placement wherever one is needed — and only where one is needed, so
-- an hour that suits everybody stays available. Measured the same day, on the
-- same data: 1079 of 1331.
--
-- Keyed by subject rather than kept as a religia column and an etyka column,
-- so that nothing has to recognise a subject by its name. A school that
-- renames "Religia" keeps working; a school with a third optional subject
-- needs no migration.
--
-- Counts, never identities. The school keeps the register; the solver needs
-- only the shape.
CREATE TABLE belfer.optional_subject_attendance (
  class_id   uuid    NOT NULL REFERENCES belfer.school_class(id) ON DELETE CASCADE,
  subject_id uuid    NOT NULL REFERENCES belfer.subject(id)      ON DELETE CASCADE,
  attending  integer NOT NULL CHECK (attending >= 0),
  PRIMARY KEY (class_id, subject_id)
);

CREATE INDEX optional_subject_attendance_class ON belfer.optional_subject_attendance (class_id);
