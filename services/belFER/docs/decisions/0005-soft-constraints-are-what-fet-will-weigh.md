# 0005 — Soft constraints are whatever FET will weigh

**Status:** Accepted (2026-10-30)
**Supersedes the mechanism of** [0002](0002-soft-constraints-as-weighted-penalties.md)
for the FET path.

## Context

Phase 3.3 asks for the thing Vulcan does not have: a planner who can say what
a *good* timetable is, not merely a legal one. REQUIREMENTS §9.2 lists four
preferences — fewer teacher windows, evener class days, a teacher's own room,
a subject's specialist room — and §9.2 says their weights are the user's to
set.

ADR 0002 decided how: every soft constraint as a weighted sum of per-unit
penalties, and the solver always given an objective. That was written for
CP-SAT, which we no longer use. ADR 0004 put FET in its place and the roadmap
has carried "revisit 0002 before 3.3" ever since.

FET does not take an objective function. It takes a `Weight_Percentage` per
constraint, between 0 and 100. The obvious reading — that every constraint can
therefore be dialled down from "must" to "prefer" — is wrong, and the way it is
wrong decides the shape of the whole feature.

## What was measured

Each constraint was added to the school's real export at weight 60 and handed
to `fet-cl` to load.

| Preference | FET constraint | Weight below 100 |
|---|---|---|
| Fewer teacher windows, per day | `ConstraintTeachersMaxGapsPerDay` | **refused** |
| Fewer teacher windows, per week | `ConstraintTeachersMaxGapsPerWeek` | **refused** |
| Class day no longer than | `ConstraintStudentsMaxHoursDaily` | accepted |
| Class day no shorter than | `ConstraintStudentsMinHoursDaily` | **refused** |
| Teacher's own room | `ConstraintTeacherHomeRoom` | accepted |
| Subject's specialist room | `ConstraintActivityPreferredRooms` | accepted |

What FET says when it refuses, verbatim:

> Cannot optimize, because you have constraint teachers max gaps per week with
> weight (percentage) below 100. Please make weight 100% and try again

It then aborts. This is not a warning to be ignored.

## Decision

**There are two kinds of knob, and the difference is visible to the planner.**

**Bounds** are hard. They are a number, FET is told to satisfy it absolutely,
and a bound set too tight makes the week impossible. These are the gap
constraints and the minimum class day.

**Preferences** are weighted. They are a number *and* a strength between 0 and
100, FET tries and gives up when it must, and no setting of them can make a
week impossible. These are the maximum class day, the teacher's own room, and
a subject's specialist room.

They are presented apart rather than in one list, because the question a
planner must be able to answer about each is different: "could this stop a
plan being made?" The answer is yes for one group and no for the other, and a
single list of sliders would hide that.

**Fewer teacher windows is a bound, not a preference.** This is the awkward
one, because windows are the largest quality problem the school has — 331
window-hours in the current week — and it is exactly the thing FET will not
let us ask for gently. So it is asked for firmly: the planner sets a maximum,
and tightening it is how the number comes down.

**Two numbers, for three ideas.** The ideas are distinct and worth naming,
because the export already quietly approximates one with another:

- **the longest run** of free hours a teacher may sit through — REQUIREMENTS
  §9.1 rule 16, and what `PlanCheck` R15 verifies.
- **how many free hours in a day**, which is what FET actually counts.
- **how many in a week**, the one with leverage: two a day across five days
  permits ten, and the school averages four and a half.

FET cannot express a run length at all, so the first is sent as the second —
"at most two in the day" standing in for "at most two in a row", which is
stricter and therefore safe. That approximation stays, and deliberately does
*not* become its own setting. Giving the per-day count a value of its own
would leave the run length with nothing representing it in the search at all:
FET would be told only about daily totals, and R15 would start failing on
weeks FET thought were fine. One setting that is honest about standing for
two beats two settings of which one does nothing.

So the planner sets a longest run, and separately a weekly total.

**A bound that proves impossible keeps the current plan and says so.** The run
fails, nothing is saved, the week the school is running stays the week the
school is running, and the planner is told which setting to loosen. Not an
automatic retry at a looser bound: each attempt costs a minute or two, and
quietly delivering a plan that does not meet what was asked for is worse than
saying it could not be done.

## Consequences

**ADR 0002's mechanism does not survive, but its instinct does.** "Express a
preference as a per-unit cost, never as an extremal quantity over a set" has
no counterpart in FET's vocabulary — there are no units to cost. What remains
true is the narrower point its CT-20 example was making: a spread between the
longest and shortest day is not expressible directly, and has to be said as
two separate bounds on the two ends. Which is exactly how FET offers it, one
weighted and one not.

**Soft room requirements become real.** Subjects carry a `roomRequirementHard`
flag and the interface promises the requirement is soft by default, falling
back to an ordinary room — and the exporter has been ignoring it and writing
every room requirement at 100. That is a weight now, which is both the feature
and a bug fixed.

**It may also make generation faster.** Those 1146 preferred-room constraints
at full strength are most of why a run takes a minute and a half rather than
four seconds (postscript to ADR 0004). A soft one is cheaper to satisfy. Not
the reason for doing this, but worth measuring afterwards.

**The honest limit, written down so nobody re-discovers it.** We cannot ask
FET to *minimise* windows. We can only forbid more than N and let it find any
answer within that. Driving the number down means tightening N until the week
stops being possible, which is a search a person performs by hand, one run at
a time. If that becomes tiresome, automating it is a loop around generation
rather than anything new in the model.
