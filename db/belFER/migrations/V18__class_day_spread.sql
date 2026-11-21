-- How uneven a class's week may be: its longest day minus its shortest, in
-- lessons (RULES P2).
--
-- A bound, not a preference. FET cannot speak about a spread at all, only
-- about the two ends of a day, so the export says it per class as a range of
-- day lengths around that class's average day: a 32-hour class at a spread of
-- three gets days of 5 to 8. The two school-wide ends from V14 could not say
-- this, because one range for every class is wrong for most of them: a 38-hour
-- class and a 28-hour class have no sensible shortest day in common.
--
-- Null means not asked for, and a school upgrading generates exactly what it
-- generated before. Measured on the real school (RUNLOG R8-R10): at three it
-- completes in about two minutes, at two in about four.
ALTER TABLE school ADD COLUMN max_class_day_spread INT;

ALTER TABLE school ADD CONSTRAINT school_max_class_day_spread_sane
    CHECK (max_class_day_spread IS NULL OR max_class_day_spread >= 0);
