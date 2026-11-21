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

## Queue

In order. Each is one change, so its effect can be read on its own.

1. **R3 — G1 + G10.** Exporter: with no attendance entered, assume all three
   groups (religia / etyka / neither); fix the group off-by-one. Expect "last
   only" to become "first or last".
2. **R4 — R3 with students' no-gaps stated per week rather than per day.** Same
   rule, FET's faster path.
3. **R5 — the FET version.** Match the stamp to the binary, or update the
   binary. Then re-check the attendance-group windows (G2) on whatever
   completes.
4. Then the S4 and S5 encodings (RULES G3 and G4). They make the problem
   harder, so they go in once there is a baseline that completes.
