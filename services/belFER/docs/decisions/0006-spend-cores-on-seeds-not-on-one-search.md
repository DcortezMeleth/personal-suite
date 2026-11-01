# 0006 — Spend cores on seeds, not on one search

**Status:** Accepted (2026-11-01)
**Builds on** [0004](0004-generate-with-fet-verify-with-our-own-rules.md).

## Context

A run of the real school was left going overnight with teacher gaps limited
to three a day and three a week. Fifteen hours later it was still searching:
one core at 99%, fifteen megabytes of memory, nothing written. The obvious
question was whether FET could be given more of the machine.

It cannot. `fet-cl --help` lists input, output, HTML formatting, CSV
formatting, warning toggles, six random-seed components and a time limit.
There is no flag for threads, cores, processes or memory, because the
generation algorithm is single-threaded — which is exactly what the 99% of
one core was saying.

Three further things turned up while establishing that, and they change the
answer more than the original question did.

**The run was not slow, it was wedged.** FET writes
`logs/max_placed_activities.txt` as it searches, one line per new furthest
point. On this instance it reached activity 432 of 1331 within three seconds
and never moved again. Fifteen hours bought nothing after the third second.

**FET will say what defeated it, if asked to stop.** Its help states that
SIGTERM makes it write the current timetable, the highest-stage timetable and
exit; SIGINT stops it dead with nothing written. Measured: it exits cleanly in
about two seconds and writes `logs/difficult_activities.txt`, naming the
activity it could not get past — a teacher, a subject and a class. We had been
killing the process and deleting all of it.

**The seed decides the wall.** FET's own documentation promises that identical
input, version and seeds give an identical timetable; the seed fixes the order
activities are tried in, and so fixes where the search gets stuck.

## Decision

**Run several searches at once on different dice, and keep the first good
week.** Four by default, bounded at sixteen, `BELFER_FET_WORKERS`. The first
sound and complete result wins; the losers are cancelled, which kills their
processes.

**Stop a run by asking rather than by killing.** A flag the search reads once
a second, then SIGTERM, then wait for FET to finish writing. The run settles
carrying the diagnosis.

**Report progress from `max_placed_activities.txt`, not from `--verbose`.**

**Size the container runtime to match.** On a laptop FET runs under colima,
which defaults to 2 CPUs on a 10-core machine. Raised to 8.

## Consequences

**Parallel seeds should beat extra cores on one search by a wide margin, when
one search can use only one core anyway.** Randomised backtracking has a
heavy-tailed runtime distribution, so the expected gain from racing *n* seeds
is not *n*× but the chance of avoiding the bad tail entirely — which, on an
instance that wedges in three seconds and then never recovers, is most of the
distribution. This is a claim about a known property of this class of search,
not something we have measured here; what has been measured is that this
instance wedges, and that where it wedges is a function of the seed.

**A stopped run is a diagnosis, not a plan.** This is a real departure from
UC-30, which asks to "keep the best plan found". That use case was written
against Timefold, which optimises a complete solution and so always has a
worse-but-whole timetable to hand back. FET constructs a week one activity at
a time, so what it holds when interrupted is a partial assignment — four
hundred of thirteen hundred lessons. A timetable missing two thirds of its
lessons is not a worse timetable; it is not one. What is worth keeping is the
account of where it stuck, and that is what a stopped run now returns. UC-30
should be re-read as UC-32.

**`--verbose` is a trap and is recorded as one.** It reports the same progress
in the output stream at about four kilobytes a second on this instance — two
hundred megabytes over a night, unbounded after that. The log file carries the
same information and cannot have more lines than there are activities.

**Four workers is a guess, not a measurement.** It wants to be the number of
cores available to whatever runs FET, and on a laptop that is the container
runtime's allowance rather than the host's — the two were 2 and 10 here. A
wrong value is wasteful rather than incorrect: too few leaves cores idle, too
many makes each search slower without changing which of them is lucky.

**Memory is not a constraint and should stop being discussed as one.** FET
held 14.8 MiB across a fifteen-hour search.

**The ambiguity this exposed in reading FET's answer is now fixed.** A
finished run writes `timetables/school/`; an interrupted one writes
`school-current/` and `school-highest/` beside it. Taking the first
`*_activities.xml` from a directory walk was choosing between them by
filesystem order. They are ranked explicitly now.

---

## Postscript, 2026-11-01 — what was actually blocking the school

The diagnosis this ADR built was used the same day, and it found something
this record should not lose.

The run was never blocked by the gap limits. Removing them entirely leaves the
wall at the same activity, 432 of 1331. What moves it is the rule in §2.5 that
optional subjects sit at the edge of a day, which the exporter writes as
`ConstraintActivityEndsStudentsDay` on all 26 religia and etyka activities.

**`ConstraintActivityBeginsStudentsDay` exists, and we were not using it.** The
comment at the top of `FetExport` asserted that FET "has no way to say 'or
begins it'". That is false, and was false when written: the constraint is in
the 6.8.5 binary we already run, which can be read straight out of it —

```
grep -aoE "Constraint[A-Za-z]{3,60}" /usr/bin/fet-cl | sort -u   # 288 names
```

Measured on the real school, same data, same settings, 2 minutes each:

| Edge encoding | Activities placed |
|---|---|
| All *ends students day* — what we ship | **432** of 1331 |
| All *begins students day* | **1089** |
| Mixed per activity | 432 – 1079, erratic |
| Rule removed entirely | 1052 – 1127 |

Six seeds on *begins* converge around 1110 and differ from one another, which
is a search working rather than a search stuck.

Why the asymmetry is inference rather than measurement: we constrain students
to have no gaps but never force a class's day to start at the first hour, so
"begins the students' day" lets every class start at a different hour and one
religia teacher can open many days. "Ends the day" has to fit around each
class's total length instead.

**Neither encoding is the rule.** The school's rule is *first or last*, a
disjunction FET cannot state. "Always first" is a sound strict reading — a
pupil who skips simply arrives a lesson later — and is worth two and a half
times as much as the one we ship.

Two ways out, both open at the time of writing:

1. **Race the encodings.** The portfolio above already races seeds; the
   encoding is one more dimension to race. The disjunction FET will not state
   becomes something we search for.
2. **Model the cohorts, and delete the rule.** The FET manual's own
   recommendation, and the reason the edge constraint exists at all: it is for
   when "not all students of a group visit this activity". A pupil may attend
   religia, etyka, both or neither — four cohorts — and FET's student
   divisions are built to express exactly that. With them,
   `ConstraintStudentsSetMaxGapsPerDay`, which we already emit, enforces the
   real rule per cohort, and FET may put religia mid-day whenever the cohorts
   allow. It needs one new input from the school: cohort sizes per class.
   Counts, never identities.

Blocked on the second: whether the school holds those numbers.

And a caveat against reading the table too warmly. 1089 is not a timetable.
There is a second wall past the first and it has not been identified. *Begins*
turns impossible into hard.

---

## Second postscript, 2026-11-01 — the wall behind the first one

With attendance modelled the week reaches 1090 activities of 1331 and stops.
The same ablation method found what stops it, and this time the answer is not
a constraint we chose badly. It is the school's arithmetic.

Seven constraint families were removed one at a time from the real export.
Dropping room preferences, one-block-per-day, the cross-class same-time
coupling, the daily hour cap or the teacher gap limits changes nothing worth
reporting. Dropping **`ConstraintStudentsSetMaxGapsPerDay`** — "a class never
has a gap", hard constraint 13 — completes the timetable: 1331 of 1331.

It is not a fix. Measured on that completed week, the classes carry **252
gap-hours between them**, six to fifteen each, which is not a worse timetable
so much as one no school would hang in a corridor.

**The reason is capacity.** Counting the hours that actually occupy a class —
its own lessons, one of each pair of halves, and its share of every
cross-class unit — the second years carry 37 and 38 hours of a 40-slot week.
Verified twice, from the database and by counting occupied hours in the file
FET reads, because the first attempt said 44 and was wrong: it charged a class
once per group of a PE unit rather than once per unit, which is the exact
mistake the comment above the cross-class export warns about.

At 38 hours, with a cap of eight lessons a day and no gaps permitted, a class
has exactly one shape: 8/8/8/8/6. Four days with every slot filled and no
slack anywhere — and only then do PE alignment, the attendance cohorts,
one-subject-per-day and fifteen teachers' restricted availability get their
turn.

REQUIREMENTS called this before any code was written, under "Secondary risk —
feasibility, not optimality": *"38 weekly hours into 40 slots, with no class
gaps and every day starting at slot 1, leaves almost no slack … the first real
runs are likely to come back infeasible."* It was right, and it is worth
saying plainly that the remaining difficulty is the school's timetable being
95% full under a hard contiguity rule rather than anything the exporter is
doing wrong.

**Where slack can come from**, in the order they are worth trying:

1. **Block shapes.** The arkusz states hours and never their shape, so an
   import is almost all single lessons — 1331 activities where a week with its
   doubles paired is 1065. It does not reduce the hours, but it reduces how
   many independent decisions make them up, and days pack more cleanly. Worth
   32% → 58% on its own, measured while the attendance wall was still in
   place.
2. **A ninth slot.** Capacity 40 → 45, and the second years stop being forced
   into perfectly packed days. A question for the school rather than for us.
3. **Gaps where they are needed.** The fallback, and the one to reach for last:
   a class gap is a window for everybody in the class, which is the thing the
   rule exists to prevent.

A note against reaching for the first of those too confidently: the ablation
figures other than the completion were read while the runs were still going,
so they rank nothing against each other. Only "this one completes and the
others do not" is load-bearing.
