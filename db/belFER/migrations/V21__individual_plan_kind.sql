-- What kind of individual pupil a plan is for, and for a mixed pupil which
-- half of their class they are in (RULES S9, INDIVIDUAL_TUITION.md).
--
-- Neither is in the arkusz, so both start empty and the planner sets them on
-- the Nauczanie indywidualne page. They describe the provision, not the pupil:
-- the plan is still only a class and an ordinal, and nothing here says who.
--
--   REMOTE   only individual lessons; windows in the pupil's day allowed
--   ON_SITE  only individual lessons; no windows
--   MIXED    some lessons with the class, in `half`; no windows
ALTER TABLE individual_plan
    ADD COLUMN kind TEXT,
    ADD COLUMN half INT;

ALTER TABLE individual_plan
    ADD CONSTRAINT individual_plan_kind_known
        CHECK (kind IS NULL OR kind IN ('REMOTE', 'ON_SITE', 'MIXED')),
    ADD CONSTRAINT individual_plan_half_is_a_half
        CHECK (half IS NULL OR half IN (1, 2)),
    -- A half means something only for a pupil who attends their class.
    ADD CONSTRAINT individual_plan_half_only_when_mixed
        CHECK (half IS NULL OR kind = 'MIXED');
