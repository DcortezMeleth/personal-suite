-- Two rules about free hours, told apart.
--
-- The school's rule is a run length: a teacher may sit through at most N free
-- hours *in a row*. FET cannot express that — it counts a day's gaps, not the
-- longest stretch of them — so the export has been sending the run length as
-- a daily count and calling it the safe direction.
--
-- It is the safe direction and it is also much stricter than the rule. At one,
-- it forbids a teacher teaching first, third and fifth period: two separate
-- single gaps, never adjacent, entirely legal by the rule it stands for. A
-- planner who tightens the run length to one is unknowingly asking for
-- something no human timetable satisfies either, and the search goes looking
-- for a week that does not exist.
--
-- So the daily count becomes its own setting. Null keeps the old behaviour —
-- the run length stands in, as before — and a school upgrading generates
-- exactly what it generated yesterday until somebody says otherwise. The run
-- length itself stays what PlanCheck R15 verifies afterwards, which is the
-- only place it can be checked honestly.
ALTER TABLE school ADD COLUMN max_teacher_gaps_per_day INT;

ALTER TABLE school ADD CONSTRAINT school_max_teacher_gaps_per_day_sane
    CHECK (max_teacher_gaps_per_day IS NULL OR max_teacher_gaps_per_day >= 0);
