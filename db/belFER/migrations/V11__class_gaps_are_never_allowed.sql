-- A student cannot have a free period in the middle of their day: there is
-- nowhere for them to be. It was a school-level setting on the assumption that
-- someone might want to relax it; the school says it is simply a rule, so it
-- stops being a knob that can be turned the wrong way.
--
-- The one case that looked like a gap — half a class arriving late or leaving
-- early because one teacher takes both halves of a split — is handled by
-- placing that lesson at the edge of the day, not by letting the day have
-- holes in it.
ALTER TABLE school DROP COLUMN allow_class_gaps;
