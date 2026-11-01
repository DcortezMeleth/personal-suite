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
