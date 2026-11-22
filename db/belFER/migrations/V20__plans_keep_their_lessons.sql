-- A saved plan keeps every lesson it was saved with (REVIEW_2026-11-21 A2).
--
-- A placement named its lesson through a foreign key with ON DELETE CASCADE,
-- so anything that removed the lesson removed it from every plan, silently:
-- deleting a lesson line, re-importing the arkusz, and, until the repository
-- stopped re-creating groups, any save of a cross-class unit. The plan kept
-- its "rules kept" figure and showed a shorter week as though it were whole.
--
-- PlanLayout was written for the other outcome: a placement whose lesson the
-- school no longer has is counted as unresolved, and the plan says how many
-- of its lessons it can no longer show. That only works if the placement is
-- still there. So the three references become plain identifiers. The plan
-- itself still goes with its placements, and the checks that a placement is
-- exactly one thing and that a block is placed once stay.
ALTER TABLE plan_placement
    DROP CONSTRAINT plan_placement_lesson_line_id_fkey,
    DROP CONSTRAINT plan_placement_cross_class_group_id_fkey,
    DROP CONSTRAINT plan_placement_individual_lesson_id_fkey;
