-- Placeholder home rooms, until the school sends the real ones.
--
-- A teacher's own room is a soft preference the planner is meant to honour
-- (REQUIREMENTS §5), and nothing can be weighted against an empty column.
-- The school has the real mapping; this stands in until it arrives.
--
-- Deliberately NOT derived from the current plan, which would have been the
-- obvious shortcut. Rooms in it were assigned by FET from whichever ordinary
-- rooms fit, with no preference expressed either way, so "the room this
-- teacher is mostly in" is noise. Seeding from it would turn that noise into
-- a stated preference and then spend search time optimising towards it.
--
-- So: busiest teachers first, plain classrooms only, which at least puts the
-- rooms where the hours are. It is arbitrary and it is meant to be replaced.
--
-- Skipped, on purpose:
--   * staff with no lessons — REQUIREMENTS §8.1, they are never placed
--   * teachers of PE and cross-class blocks — those run in the halls, and the
--     school assigns those by timetable rather than by person
--   * rooms that carry a kind — the gym sections and the tuition rooms are
--     spoken for
--
-- Re-runnable: it clears what it set last time before setting it again.
--   psql -h localhost -U delfin -d delfin -f services/belFER/testdata/seed-home-rooms.sql

BEGIN;

UPDATE belfer.teacher SET home_room_id = NULL;

WITH teaching AS (
  SELECT teacher_id FROM belfer.lesson_line WHERE teacher_id IS NOT NULL
  UNION SELECT support_teacher_id FROM belfer.lesson_line WHERE support_teacher_id IS NOT NULL
  UNION SELECT teacher_id FROM belfer.cross_class_group WHERE teacher_id IS NOT NULL
  UNION SELECT teacher_id FROM belfer.individual_lesson WHERE teacher_id IS NOT NULL
),
in_halls AS (
  SELECT teacher_id FROM belfer.cross_class_group WHERE teacher_id IS NOT NULL
  UNION
  SELECT l.teacher_id FROM belfer.lesson_line l
  JOIN belfer.subject s ON s.id = l.subject_id
  WHERE s.name ILIKE '%wychowanie fizyczne%' AND l.teacher_id IS NOT NULL
),
load AS (
  SELECT t.id,
         coalesce(sum((SELECT sum(b) FROM unnest(l.blocks) AS b)), 0) AS hours
  FROM belfer.teacher t
  LEFT JOIN belfer.lesson_line l ON l.teacher_id = t.id
  WHERE t.id IN (SELECT teacher_id FROM teaching)
    AND t.id NOT IN (SELECT teacher_id FROM in_halls)
  GROUP BY t.id
),
ranked_teachers AS (
  SELECT id, row_number() OVER (ORDER BY hours DESC, id) AS rn FROM load
),
-- Whole-class rooms first: a teacher who only ever teaches halves is the
-- exception, and the busiest teachers are the least likely to be one.
ranked_rooms AS (
  SELECT r.id, row_number() OVER (ORDER BY r.fits_whole_class DESC, r.number) AS rn
  FROM belfer.room r
  WHERE NOT EXISTS (SELECT 1 FROM belfer.room_kind_assignment a WHERE a.room_id = r.id)
)
UPDATE belfer.teacher t
SET home_room_id = rr.id
FROM ranked_teachers rt JOIN ranked_rooms rr ON rr.rn = rt.rn
WHERE t.id = rt.id;

COMMIT;
