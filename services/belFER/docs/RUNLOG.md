# belFER — run log

The record of every significant generation run: what went in, what came out,
whether it was better than before, what changed in the app or the data
between runs, and what is in the way next.

It exists because the runs before it were not kept. On 2026-10-29 the school's
week completed in about a minute. By 2026-11-01 it stalled at 432 lessons. Nobody
can now say exactly what changed in between, because neither the files nor the
settings were saved.

---

## How to record a run

- **One entry per significant run**, appended at the bottom, plus one row in
  the summary table. Not every run belongs here, only ones that answer a
  question or set a new best or worst.
- **The file goes in `testdata/private/runs/<run id>/`.** That means
  `school.fet` exactly as sent to FET, and FET's `out/` directory when it is
  worth keeping. That directory is **gitignored and must stay so**: the file
  and FET's logs carry real staff names. This log names classes and subjects,
  never people.
- **The first run keeps the whole file. Later runs record what changed**:
  settings, data, exporter. Keep the whole file anyway when it is cheap to do
  so (about 2 MB).
- **Every entry states:** rules version, app commit, what changed since the
  previous run, input size and hash, settings, result, verdict, and the next
  blocker.

### Producing and running a file by hand

```sh
# write the school out as FET sees it, without solving
set -a && . infra/.env && set +a && export BELFER_DATABASE_PASSWORD="$POSTGRES_PASSWORD"
bazel run //services/belFER/backend:solve -- <schoolId> --fet=$PWD/services/belFER/testdata/private/runs/<id>/school.fet

# run FET on it (add --timelimitseconds=N for a bounded check)
docker run --rm -v <run dir>:/data belfer-fet:local --inputfile=/data/school.fet --outputdir=/data/out
```

How far it got is in `out/logs/max_placed_activities.txt`, and where it stuck
is in `out/logs/difficult_activities.txt`.

### Reading a result (from FEASIBILITY.md, learned the hard way)

- **Only a completed run is a result.** "Placed N" is comparable between two
  runs **only when the input is the same file**: a bigger instance explores
  more slowly, so a lower count is not a worse instance.
- **A 15-second check is a check, not a run.** It shows that the file loads
  and where the search gets stuck early. It says nothing about whether the
  week can be finished.
- **Check one case by hand** before believing an aggregate.

---

## Summary

| Run | Date | Lessons | What was different | Result | Better? |
|---|---|---|---|---|---|
| H1 | 2026-10-22 | 1131 | no rooms; no tuition | **complete**, 2 s | — |
| H2 | 2026-10-29 | 1191 | + rooms | **complete**, ~65 s | — |
| H3 | 2026-11-01 | 1331 | + tuition data (probably: same count as today); teacher gaps ≤3/day, ≤3/week | stuck at 432 (5 seeds), 350 (1 seed); 15 h | worse |
| H4 | 2026-11-01 | 1331 | religia/etyka "begins the day" instead of "ends" | 1089, not complete | — |
| H5 | 2026-11-01 → 11-21 | 1331 | attendance groups, made-up numbers | best 1115, never complete | — |
| H6 | ≤ 2026-11-21 | 1331 | H5 + a ninth slot | **complete**, < 40 min; 16–18 groups with a window | not legal |
| R1 | 2026-11-21 | 1331 | data fixes (PE rooms, shortest day off) | 15 s check: stuck at 433 | same as H3 |
| R2 | 2026-11-21 | 1128 | R1 without individual tuition | 15 s check: stuck at 323 | no |
| R3 | 2026-11-21 | 1331 | R1's file on FET 7.12.1 instead of 6.8.5 | stuck at 433 in 2 s (6.8.5: 34 s) | same wall, reached faster |
| R4 | 2026-11-21 | 1331 | religia/etyka "first or last" (FET 7); G10 fix | **complete**, 46 s and 47 s, 2 of 2 seeds | **yes — first complete 8-slot week since 10-29** |
| R5 | 2026-11-21 | 1331 | + S4's whole-class link, S5's pairs | **complete**, 52 s and 63 s, 2 of 2 seeds; 16 of 17 rules | **yes — only teacher run length left** |

---

## History before this log (reconstructed)

From the decision records, FEASIBILITY.md and commit messages. **No files or
full settings survive**: anything not stated in those sources is unknown, and
is marked so.

- **H1 — 2026-10-22** (ADR 0004)
  - 25 classes, 1131 activities, complete in 2 seconds.
  - The export had days, hours, subjects, teachers, student sets and
    activities. It had **no rooms**, and no individual tuition.
  - Teacher limits: unknown.
- **H2 — 2026-10-29** (ADR 0004, postscript)
  - 1191 activities, complete in 64–68 s; the same file without rooms took
    4–5 s.
  - Rooms were added (1146 room constraints).
  - "The school's data has no individual plans at all right now."
  - Religia and etyka were exported as "ends the day". Whether the subjects
    were flagged optional in the data at that point is unknown.
- **H3 — 2026-11-01** (ADR 0006, ROADMAP)
  - 1331 activities. Teacher gaps at most 3 a day and 3 a week.
  - Stuck at 432 within 3 seconds; five seeds stopped at 432 and one at 350.
    An overnight run made no progress in 15 hours.
  - Removing the gap limits left it at 432.
  - "4 gaps a week has produced a plan before, 3 has not." Which plan, and on
    what data, is unknown.
- **H4 — 2026-11-01** (ADR 0006), two minutes per variant:

  | Religia/etyka rule | Placed |
  |---|---|
  | All "ends the day" | 432 |
  | All "begins the day" | 1089 |
  | Mixed per activity | 432–1079 |
  | Rule removed | 1052–1127 |

- **H5 — 2026-11-01 → 11-21** (commit 5a79896, FEASIBILITY)
  - Attendance groups with made-up numbers (82/10/8), deleted afterwards: 1090.
  - The best ever on 8 slots is 1115, never complete, on any seed, encoding or
    setting.
  - Removing the class no-gaps rule completes the week, with 252 class
    gap-hours.
- **H6 — before 2026-11-21** (FEASIBILITY)
  - Ninth slot: complete on both seeds in under 40 minutes.
  - **Not legal**: 16–18 of the 46 attendance groups get a one-hour window.
  - Probably 9 lessons a day were allowed too. The exporter writes the class
    cap as "slots per day", and FEASIBILITY counts the capacity as 45. How the
    ninth slot was introduced was not recorded.
  - The ninth slot was ruled out on 2026-11-21.

**The open question H1–H3 leave:** the same school completed in about a minute
on 2026-10-29 and has never completed on 8 slots since. R2 below rules out
individual tuition as the sole cause.

---

## R1 — 2026-11-21-baseline

The first run recorded under this log. Today's data after the fixes below.

- **Rules:** RULES.md 1.0 · **App:** `e28a4e1`
- **Changed since the last run (data):**

  | What | Before | After |
  |---|---|---|
  | Wychowanie fizyczne room requirement | none, soft | Sala gimnastyczna, **hard** |
  | 14 PE cross-class units, room type | none | Sala gimnastyczna |
  | "Pracownia chemiczna" room type | existed (no rooms, subjects or units) | deleted |
  | Shortest class day (`min_class_hours_daily`) | 4, hard | off |

  The PE fix closes RULES G6. Before it, FET put PE groups in classrooms.
  Clearing the shortest day closes G8.
- **Input:** `school.fet`, sha256 `59f85f4f692bb382…`, **1331 activities**:

  | Part | Activities |
  |---|---|
  | Class lesson blocks | 942 (922 single, 20 double) |
  | Cross-class occupancy | 45 |
  | Cross-class group lessons | 141 (138 PE in PE spaces, 3 PE as tuition) |
  | Individual tuition | 203 |

- **Constraints written:**

  | Constraint | Count |
  |---|---|
  | `ActivityPreferredRooms` | 1286 |
  | `MinDaysBetweenActivities` | 275 |
  | `ActivitiesSameStartingTime` | 45 |
  | `ActivityEndsStudentsDay` | 26 |
  | `StudentsSetMaxGapsPerDay` | 25 |
  | `StudentsSetMaxHoursDaily` | 25 |
  | `TeacherNotAvailableTimes` | 6 |
  | `TeachersMaxGapsPerDay` | 1 |
  | `TeachersMaxGapsPerWeek` | 1 |
  | `TeachersMaxHoursDaily` | 1 |

- **Settings:**
  - 5 × 8.
  - Teacher windows: at most 1 in a row, 2 a day, 4 a week.
  - Teacher defaults: 5 days, 8 lessons.
  - Preferences: all off.
  - Attendance: **none entered**, so all 26 religia/etyka activities fall back
    to "ends the day".
- **Teacher data is thinner than REQUIREMENTS expects.** 6 of 77 teachers have
  unavailable hours, and none works fewer than 5 days. REQUIREMENTS expects
  about 15 of each. Until it is entered, these runs are *easier* than the real
  school.
- **Result:** 15-second check only. **433 of 1331**, stuck early.
  - FET's chain starts with the PE unit 2A + 2H and fails on a maths lesson
    of 1H.
- **Verdict:** the same wall as H3 (432), even with PE now confined to six
  spaces.
- **Next blocker:** the "ends the day" fallback. See R2's chain, and RULES G1.

## R2 — 2026-11-21-no-tuition

Tests the lead that individual tuition, absent on 10-29 and present since, is
what stalls the week.

- **Rules:** 1.0 · **App:** `e28a4e1`
- **Changed:** R1's file with the 203 individual-tuition activities and their
  203 room constraints removed, by a throwaway script. One
  `MinDaysBetweenActivities` was trimmed rather than dropped, because of the
  exporter bug G10 (below).
- **Input:** 1128 activities, sha256 `40ef94e405ab4a66…`
- **Result:** 15-second check. **323 of 1128**, reached in the first second;
  nothing after.
  - FET's chain **starts with the 7-class etyka block (1A, 1C–1H)**, then the
    PE units, and fails on a maths lesson of 2H.
- **Verdict:** **individual tuition is not, on its own, what stalls the
  week.** The count is not comparable with R1, which had a different input.
- **Next blocker:** the 7-class etyka block under "last only". It has to end
  the day of seven classes at the same hour. That is G1. Implementing the
  confirmed default (assume all three attendance groups, which gives first or
  last) is the next run.

### Found while preparing R1–R2

- **G10 — exporter off-by-one.** The first individual-tuition lesson (a
  rewalidacja hour) reuses the group number of the last cross-class unit (4F's
  PE). FET is therefore told to keep it on a different day from 4F's PE. Minor,
  but wrong. Fix: allocate the tuition group the same way as every other.
- **FET warns about our no-gaps rule.** On every load it says that
  `StudentsSetMaxGapsPerDay` is "not perfectly optimized for speed… long
  generation time or even impossible timetables". At zero, "no gaps per day"
  and "no gaps per week" are the same rule, and the per-week constraint is not
  on FET's list. That makes it worth one run.
- **Version mismatch.** The exporter stamps files as FET `7.12.0`; the binary
  is `6.8.5`. FET opens the file anyway. Worth ruling out before blaming FET
  for the attendance-group windows (G2): either write `6.8.5`, or move the
  image to a current FET.

---

## R3 — 2026-11-21-r3-engine

Does a newer engine alone move the wall?

- **Rules:** 1.2 · **App:** none; R1's file, exported at `e28a4e1`
- **Changed:** only the engine. FET **7.12.1**, built from upstream source in
  `services/belFER/fet/Dockerfile`, against **6.8.5** from Ubuntu, kept as
  `belfer-fet:6.8.5`.
- **Input:** R1's file, byte for byte (`59f85f4f692bb382…`)
- **Settings:** as R1. 120 s each, run side by side.
- **Result:** **both stuck at 433 of 1331.**
  - 7.12.1 got there in 2 s, 6.8.5 in 34 s.
  - 7.12.1 no longer prints the "students max gaps per day … not optimized"
    warning.
- **Verdict:** the engine is not what stalls the week. The newer one searches
  faster and is the one to keep: R4 below needs it, and 6.8.5 cannot read R4's
  file.

## R4 — 2026-11-21-r4-begins-or-ends

The first complete eight-slot week since 10-29.

- **Rules:** 1.2 · **App:** `453e9f0` · **Engine:** FET 7.12.1
- **Changed (exporter):**
  - **Religia/etyka:** with no attendance entered, the 26 activities are
    `ActivityBeginsOrEndsStudentsDay` (first or last, checked per subgroup)
    instead of `ActivityEndsStudentsDay`. This is RULES G1.
  - **The off-by-one:** the first tuition lesson no longer shares a PE unit's
    group (G10).
  - **The version stamp** in the file is now `7.12.1`.
- **Input:** 1331 activities, sha256 `d0e3f38cf23e901c…`
  - Constraint counts as R1, except `ActivityEndsStudentsDay` 26 →
    `ActivityBeginsOrEndsStudentsDay` 26.
- **Settings:** as R1. Two fixed seeds (1,2,3,4,5,6 and 101,…,606), 120 s
  limit each.
- **Result:** **complete on both seeds: 1331 of 1331, in 46 s and 47 s.**
- **Our checker** (`solve --read`), 1286 lessons, both weeks:
  - **Kept:** R1, R2, R3, R3b, R3c, R3d, R5, R7, R9, R10, R11, R12, R13, R14.
    That covers every lesson placed once, nobody double-booked, no student
    window, religia/etyka first or last, PE in PE spaces, and rooms.
  - **Broken, R15:** teachers with two free periods in a row, on 20 and 18
    teacher-days. This is G5: FET is told "at most 2 a day", which allows a
    2-hour hole.
  - **Broken, new checks:** run against these weeks after they were added,
    whole class and half on the same day (R5b) 25 and 22 times, and halves not
    level (R16) at 23 and 15 whole-class lessons. FET was not yet told about
    S4's link or S5.
- **Verdict:** **the wall was "last only", our encoding, not the school's
  arithmetic.** FEASIBILITY.md's conclusion that the week needs a ninth slot
  was wrong. It was measured under a rule stricter than the school's.
- **Next blocker:** the halves rules (R5), then teacher windows (G5).

## R5 — 2026-11-21-r5-halves

The halves rules, measured against R4.

- **Rules:** 1.2 · **App:** `82f458b` · **Engine:** FET 7.12.1
- **Changed (exporter):**
  - **S4's whole-class link (G3).** For the 30 class–subject cases with both
    whole-class and half lessons, the whole-class blocks are spread together
    with each half's.
  - **S5 (G4).** Each half lesson is held at most a day from its counterpart,
    like for like.
- **Input:** 1331 activities, sha256 `c956e80e40830c6b…`
  - `MinDaysBetweenActivities` 275 → 290.
  - New: `MaxDaysBetweenActivities` 49 (one per pair).
  - Both checked against a hand count by shape: +20 +1 −3 −3 spreading
    constraints, and 10 + 26 + 1 + 6 + 6 pairs.
- **Settings:** as R4. Same two seeds, 120 s limit.
- **Result:** **complete on both seeds, in 52 s and 63 s.**
- **Our checker:** **16 of 17 kept on both weeks**, including the new R5b and
  R16. Broken: R15, teacher run length, on 25 and 29 teacher-days.
- **Verdict:** the halves rules cost little search time, since the week still
  completes in about a minute. R4 broke them 22–25 and 15–23 times.
- **Next blocker:** teacher windows, G5, left as it is for now by the
  planner's decision. Then real teacher availability, then real attendance
  numbers.

### Not done: the home-lab image

The home-lab backend image (`infra/belfer-backend-fet.Dockerfile`) needs FET
7.x too, because 6.8.5 cannot read R4's constraint. Compiling it for amd64 on
the arm64 laptop fails: FET's build tool (`qmake`) cannot read the compiler's
output under emulation, although the compiler itself works there. **Do not
redeploy the home lab until this is settled.**

---

## Queue

In order. Each is one change, so its effect can be read on its own.

1. **Home-lab image** (see R5). Build it natively on an x86-64 host, or switch
   it to upstream's prebuilt x86-64 binary.
2. **Teacher windows (G5).** Try "at most 1 free period a day", which is
   stricter than "never 2 in a row" but expressible. See whether it still
   completes and how much it costs.
3. **The students' no-gaps rule per week rather than per day.** It no longer
   looks needed: 7.12.1 does not warn, and R4 completes in under a minute.
   Keep it in mind if a later rule makes runs slow.
4. **G2, the attendance-group windows.** Re-check them on 7.12.1 once real
   attendance numbers are entered. Until then no attendance groups are
   written.
5. **Real teacher availability** (about 15 teachers expected, 6 entered). These
   runs stay optimistic until it is in.
