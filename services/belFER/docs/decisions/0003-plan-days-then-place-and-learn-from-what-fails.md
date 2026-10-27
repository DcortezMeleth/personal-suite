# 0003 — Plan the days, place them, and learn from the ones that fail

**Status:** Accepted (2026-10-14)

## Context

The whole week posed as one model never produced a timetable for this school.
Not slowly — at all, at any budget, including an hour. Fifteen classes came
out; eighteen did not; and the growth between them was a wall rather than a
curve, which is the shape of a search that has stopped making progress rather
than one that needs longer.

Reading the high school timetabling literature (recorded in
`SOLVER_EVALUATION.md`) settled two things we had been deriving from scratch.
Our school is **mid-sized** by the standard XHSTT benchmark — smaller than
several instances solved to proven optimality — so the wall was in how we were
asking rather than in what we were asking about. And **nobody solves the week
at once**: Valouxis et al. take a day at a time and then re-solve pairs of
days; Fonseca and Santos decompose by class, by teacher and by day.

A day is the right unit for us in particular. The rule that costs us most is
that a class's day has no gaps in it, and that is a rule *about one day*. Posed
over a week it couples every placement to every other through forty slots;
posed over Monday it constrains eight. Measured: a single day, for all
twenty-five classes, solves to proven optimality in **one to three seconds**.

But days cannot simply be filled in order. A class takes at most one block of a
subject a day, so a day spends *subjects* as well as hours, and a Monday chosen
on its own merits is still a Monday that can make Friday impossible. At
twenty-five classes the first three days go in cleanly and then no arrangement
of the remaining four can finish the week — which the solver proves in six
seconds, long after the choice that caused it.

## Decision

Three stages, each deciding strictly less than the one before, with the last
one teaching the first.

1. **The shape** — how many hours each class has on each day.
2. **The spread** (`DaySpread`) — which day every lesson falls on. No
   positions, no rooms, no contiguity: a lesson is on a day or it is not. What
   remains is counting — a class's hours a day, one block of a subject a day,
   what a teacher can take in a day and how many days they come in, a
   cross-class block running everywhere at once, two day edges for optional
   subjects, and the extra hour a class spends when one teacher takes both
   halves of a split.
3. **The placement** (`TimetableSolver`, one day at a time) — where each of
   that day's lessons actually goes.

The third stage **explains itself to the second**. Every lesson is placed under
an assumption the solver may blame, so an impossible day returns the handful of
lessons that cannot share it rather than only that it failed. That becomes a
rule the planner may not break again, and it plans afresh.

This is **logic-based Benders decomposition**. The planner sees the whole week
but cannot see what makes a day impossible to lay out; the placement sees
exactly that but only ever one day.

## Consequences

**It produces timetables.** Eight classes in forty to seventy plans, around two
minutes. Fifteen classes in eighteen. Neither half gets near this alone.

**Four things decide whether it learns or grinds**, all measured:

  - *Pare the blame.* The solver's own account of a failure is sufficient and
    not small — it will blame all eighty lessons of a day. A day costs a fifth
    of a second, so it is cheaper to keep asking: whole classes first, then a
    lesson at a time. Eighty come down to about seven.
  - *Name what a lesson is, not which block it happened to be.* A class's four
    maths blocks make four arrangements that fail for one reason.
  - *Carry a cut across the week* when no teacher involved keeps different
    hours on different days.
  - *Learn only from proved failures.* A probe that runs out of time has shown
    nothing, and treating it as proof mints a rule against a week that works.

**The cuts are also a diagnostic.** Printing what the conflicts were made of
rather than counting them showed that sixty per cent of every failure was one
shape — an hour of each half of a split and three whole-class hours — which is
one teacher taking both halves and being unable to teach them at once.
Modelling that directly took fifteen classes from 375 plans to 18. One cut is a
failure; a hundred cuts are a pattern; and the pattern was a rule about the
school nobody had written down.

**It does not yet reach twenty-five.** Twenty gets to the fourth day before the
planner runs out of arrangements it is allowed to make. The planner's idea of a
week is narrower than the truth in several places that are approximations of
things the placement knows exactly, and that is where the remaining work is.

**The shape is nearly subsumed.** Stage 2 largely determines stage 1, and the
placement is handed no day length at all — pinning one would assert how a
split's halves line up, which only the placement can know. Stage 1 survives as
the hours each class owes per day, and may not survive much longer.

This supersedes nothing in ADR 0001: the engine is still CP-SAT, and all three
stages are CP-SAT models. It is about what we ask it, not what we ask.
