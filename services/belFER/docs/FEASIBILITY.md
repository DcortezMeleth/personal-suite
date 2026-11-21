# Making the week actually fit

**Living document.** Where the solver stands against the real school, what has
been tried, what it measured, and what to do next. Updated as things are
learned; the decisions it led to are in `decisions/`, which stay immutable.

Last updated 2026-11-21.

---

## Where we are

The school's instance **can be laid out** — but only with a ninth lesson slot,
and the result is not yet correct for the pupils who skip religia.

| | |
|---|---|
| Activities to place | 1331 |
| Best with the shipped 8-slot week | **1115 of 1331** — never completes |
| With a ninth slot | **1331 of 1331 — complete, both seeds, under 40 min** |
| Still wrong in that complete week | 16–18 of 46 religia/etyka opt-out cohorts get a 1-hour window |

So the state is: **a timetable exists, and we cannot yet promise it is legal.**

---

## The instance

- 25 classes, ~80 teachers, 1331 activities, 5 days × 8 slots = 40
- Second years carry **37–38 occupied hours of 40** — 95% full
- At 38 hours with no gaps and 8 lessons a day there is exactly one shape:
  **8/8/8/8/6**, four days filled to the last slot, before PE alignment,
  optional-subject edges, one-subject-per-day and ~15 teachers' restricted
  availability get a say
- `REQUIREMENTS` predicted this before any code existed, under "Secondary risk
  — feasibility, not optimality", and was right

Counting that load is easy to get wrong. A class belonging to two groups of one
PE unit must be charged **once per unit, not once per group** — the mistake the
comment above the cross-class export warns about, and which produced a false
reading of 44 hours before it was caught.

---

## What was tried

### Worked

| Change | Effect | Status |
|---|---|---|
| **Attendance cohorts** instead of "optional lesson ends the day" | 432 → 1090 | shipped |
| **Ninth slot** (40 → 45 capacity) | 1115 → **complete** | not shipped; needs the school's agreement |
| Progress from `max_placed_activities.txt` | a wedged run now looks wedged | shipped |
| Stop by SIGTERM, keep FET's diagnosis | names the blocking lesson | shipped |
| Racing seeds | no gain *here*, see below | shipped |
| colima 2 → 8 CPUs | makes racing possible at all | laptop only |

### Didn't work, and why

- **Relaxing the class no-gaps rule.** `max_gaps = 1` completes the week, but
  `Max_Gaps` is a ceiling with no cost attached, so FET spends the whole
  allowance everywhere rather than only where needed: every one of the 46
  opt-out cohorts ends up with 5–6 windows. It buys completion by inflicting
  exactly the harm the rule exists to prevent.
- **The same rule as a preference.** FET refuses: *"can only optimize with not
  existing constraint max gaps or existing with 100% weight"*. Same family as
  the teacher gap rules. There is no middle ground — gaps are forbidden
  absolutely or allowed freely.
- **Racing seeds, on this instance.** Six seeds all stopped within a few
  activities of each other. FET's difficulty ordering is computed from the data
  rather than from the seed, so every search meets the same wall. Racing still
  cuts the bad tail off instances whose difficulty is luck-dependent, and costs
  only idle cores, but it is not what makes this school's week finish.
- **"First" instead of "last" for optional subjects.** `BeginsStudentsDay`
  placed 1089 against `EndsStudentsDay`'s 432 and looked like the answer. It is
  superseded by the cohort model, which is both better and actually correct.
  Worth keeping on record that the exporter's comment claiming FET could not
  say "begins" was simply false — the constraint is in the 6.8.5 binary and
  always was.
- **Pairing singles into doubles, alone.** Does not reduce hours, only how many
  independent decisions make them up. Did not complete on its own.

### Ruled out as causes

Ablated one at a time against the real export; none moved the wall: room
preferences (1286 constraints), one-block-per-day (275), the cross-class
same-time coupling (45), the class daily hour cap (25), teacher gap limits (3),
the class minimum day length (1).

---

## The open defect

**FET's `ConstraintStudentsSetMaxGapsPerDay` on a year does not protect that
year's subgroups.** Measured in the completed ninth-slot week: `2A/1s-ox` — the
pupils taking neither religia nor etyka — sits free at hour 2 on Monday between
Język polski and Historia, while the rest of 2A is in religia. 32 such
subgroup-days across 18 of the 46 cohorts, one hour each.

This matters because it is the whole premise of the cohort model. Describing
who attends makes FET *able* to see that those pupils are free; it does not by
itself make FET *protect* them.

An early test on a toy file appeared to prove subgroup-level enforcement, and
was over-read — the toy case was infeasible for reasons that did not separate
the two readings. The real data settles it the other way.

**The obvious fix was tried and did not work, and the reason is unresolved.**
`ConstraintStudentsSetMaxGapsPerDay` was emitted for each of the 46 attendance
subgroups as well as for the year. FET accepted all 71 of them — its own
constraint count reports none removed as wrong — and the week still lays out
completely, 1331 of 1331 on one of two seeds, so the extra constraints cost
nothing. But 16 of the 46 cohorts still measure a one-hour window.

That leaves two observations that cannot both be right:

- A toy file where a part-class lesson is pinned mid-day, with the gap rule on
  the year only, is **infeasible** for FET — which says the year-level rule
  does reach subgroups.
- The real completed week has opt-out cohorts sitting through a free hour,
  with the rule present both on the year *and* on the subgroup.

One of them is being misread. The measurement was checked for the obvious
hole — every student set named by an activity is declared in `Students_List`,
so nothing is being silently skipped — and the toy test was checked for the
obvious hole too, which is that it might be infeasible for an unrelated
reason. Neither check settled it.

**What to do next:** build the smallest file that reproduces the real
behaviour, rather than reasoning from either end. Take one real class with its
cohorts, strip everything else, and watch whether FET will place religia
mid-day. That distinguishes "FET does not enforce this" from "we are
describing the class to it wrongly", and those have completely different
fixes.

**A second defect found alongside it**, smaller: the cohort groups are written
with `Number_of_Students` equal to the number of subgroups (2) rather than the
attendance count. FET uses student counts for room capacity, so this
under-reports how many pupils a religia lesson needs to seat.

---

## Next steps, in order

1. **The opt-out window defect.** The correctness defect above, still open:
   the obvious fix was tried and did not take, and the two pieces of evidence
   contradict each other. Reproduce it in the smallest possible file first.
   Until it is settled the cohort model promises something it does not
   deliver — though it remains a large feasibility gain regardless.
2. **Ask the school about a ninth slot.** It is the only thing measured to
   complete the week. A question for the school, not a setting we should pick.
3. **Correct the cohort student counts** so room capacity is right.
4. **Block shapes.** The arkusz states hours and never their shape, so an
   import is almost all single lessons — 1331 activities where a week with its
   doubles paired is 1065. Being corrected by hand; worth re-measuring after.
5. **Real attendance figures.** The columns are on the Oddziały page and the
   table is empty. Everything above used synthetic numbers (82/10/8), which
   were deleted afterwards rather than left looking like data.
6. **Re-measure once 1–5 land**, before concluding anything about the 8-slot
   week. Several conclusions in this document would change.

---

## How to measure, without repeating our mistakes

Three measurement errors were made and caught during this investigation, each
in the direction of a more dramatic conclusion. The method matters as much as
the numbers:

- **Only completion counts.** Comparing "activities placed" between variants at
  a fixed time measures how fast a search explores, not whether a solution
  exists — a bigger instance explores more slowly. The ninth slot was briefly
  judged *worse* on exactly this error, when it is the only thing that works.
- **Derive every number twice.** The 38-hour class load was first measured at
  44 by double-counting PE groups. The gap counts were wrong twice, first by
  ignoring split halves and then by guessing the student-set mapping from name
  shapes instead of resolving it from the file.
- **Check a case by hand.** Both gap errors survived a plausible-looking
  aggregate and died the moment one subgroup's actual day was printed out.

The scripts used live in the scratch directory rather than the repository,
deliberately — they are throwaway, and `verify.py` is the only one whose
mapping was ever right. Re-derive rather than trust them.
