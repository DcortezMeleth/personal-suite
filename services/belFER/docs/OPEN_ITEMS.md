# belFER — open items

**Everything still to do, in one place.** Started 2026-11-21. Each item says
where its detail lives; this file is the list, not the explanation. When an
item is done, strike it through with the date and the commit or run, and
leave it for a while before deleting it.

Rules are in [RULES.md](RULES.md) (1.3), runs in [RUNLOG.md](RUNLOG.md), the
product phases in [ROADMAP.md](ROADMAP.md).

---

## Where things stand

A complete eight-slot week is generated in about 2–3 minutes and keeps all 18
rules our checker covers (RUNLOG R11). Settings: teacher free periods at most
1 a day and 2 a week, class days at most 3 lessons apart. **The data it runs
on is incomplete** (section 1), so those runs are easier than the real
school.

---

## 1. Data the school has to supply or enter

These make the runs realistic. Each one can make the week harder, so add one
at a time and log a run after each.

1. **Teacher availability.** 6 of 77 teachers have unavailable hours entered;
   REQUIREMENTS expects about 15.
2. **Teachers' own limits.** Nobody has a per-teacher limit on working days or
   lessons a day; REQUIREMENTS expects about 15 working fewer than 5 days.
3. **Religia / etyka attendance.** None entered, so every class is treated as
   "first or last" (safe, stricter than needed). Entering it creates the
   attendance groups, and with them RULES G2 (below).
4. **Support teachers (*nauczyciel wspomagający*).** None in the data. The
   arkusz rows came in as 30 hours of phantom tuition and were deleted. Ask
   the school how they are assigned; if there are any, their hours are missing
   from every generated week. RULES §7.
5. **Teacher home rooms.** 0 of 77. The placeholders seeded on 2026-10-30 are
   gone. Needed before the preference (P1) can be switched on. ROADMAP's line
   "the 28 in the database are placeholders" is out of date.
6. **Block shapes.** The arkusz gives hours, never doubles: 922 single and 20
   double class blocks today. Which subjects the school teaches as doubles is
   to be entered by hand (FEASIBILITY).
7. **One pupil taught with another class.** Not modelled, handled by hand.
   Ask the school how many lessons and whether it recurs (ROADMAP).

## 2. Rules and model

1. **Individual sessions and the pupil's own class** (*rewalidacja*,
   *zajęcia wyrównawcze*). Tuition books its teacher and a tuition room, not
   the pupil, so it can clash with the pupil's class lessons. RULES §7.
2. **G2: attendance-group windows.** 16–18 of 46 groups had a one-hour window
   on FET 6.8.5 despite the no-gaps rule. Re-check on FET 7 once attendance is
   entered. `PlanCheck` checks halves only, so it cannot see this (nor R17 for
   attendance groups). RULES G2, FEASIBILITY.
3. **Attendance-group student counts.** A cohort group is written with the
   number of subgroups (2) rather than the pupils. Harmless while room
   capacity is not modelled (every room is written as 10000). FEASIBILITY.
4. **Teacher free-period counts are not checked.** FET enforces the daily and
   weekly caps; `PlanCheck` checks only the run length (R15). A count check
   would make the checker independent of FET here too.
5. **G5 returns if the daily teacher setting goes above 1.** FET cannot say
   "never 2 in a row". RULES G5.
6. **G6 remainder.** A re-import loses the PE room requirement unless the
   importer sets it; R3c could not see the old fault. RULES G6.
7. **G7: teacher defaults.** The school default for working days is never
   sent, and a per-teacher daily cap above the default is overridden. Harmless
   at today's defaults (5 and 8). RULES G7.
8. **S3 is checked only indirectly** (cross-class units at the same hours,
   through R1). RULES §6.
9. **Specialist rooms beyond PE,** if the school needs them. RULES §4.
10. **P4: difficult subjects in the morning.** Not built.

## 3. Application

From ROADMAP "Open work", in its order:

1. **5.1 Move and swap** (UC-41–43): say immediately what a hand edit breaks.
2. **7 Re-plan without upheaval** (UC-45): seed FET from the current plan.
3. **4.5 Plan list and history** in the UI.
4. **4.6 Week-segment view.**
5. **2.3 Re-import with a diff** (UC-26).
6. **3.5 Infeasibility explanation,** redesigned for FET.
7. **Comparing two settings fairly:** same seed, two configurations. Done by
   hand in RUNLOG; nothing in the app does it.
8. **Import faults found on the first real import:**
   - *zajęcia z wychowawcą* with nobody assigned is not reported;
   - tuition cannot be edited in the UI;
   - the importer recreates the phantom support-teacher tuition.

## 4. Engine

1. **CP-SAT is removed** (2026-11-21), with the solver spike
   (`services/belFER/solver-poc`) and OR-Tools in the build. The last commit
   with them is `2a9a1a4`; retrieve it from there to reconsider it. RULES
   keeps the exact CP-SAT formulation of S5, and G9 lists what it lacked.
2. **Optional experiment:** teacher free periods at most 1 a week, to find
   where the limit is. Not to be adopted without asking the school.

## 5. Infrastructure

1. **Home-lab image.** `infra/belfer-backend-fet.Dockerfile` (uncommitted)
   cannot compile FET 7 for amd64 on the arm64 laptop. Build natively on an
   x86-64 host, or use upstream's prebuilt binary. **Do not redeploy the home
   lab until this is settled:** the image there has FET 6.8.5, which cannot
   read today's files.
2. **The local server on 8081** runs code from 2026-11-01. Restart it to use
   anything since.
3. **Frontend production build in this shell:** Node is an x64 build and only
   the arm64 rollup binary is installed, so `vite build` fails here. The
   type-check passes. Environment, not code.

## 6. Code review findings

The full review of 2026-11-21 is in
[REVIEW_2026-11-21.md](REVIEW_2026-11-21.md), with its own "Fix first" list.
In short:

- deleting any teacher, room or room kind in use fails;
- edits and re-imports delete lessons from saved plans;
- saving one row on four pages discards edits in the others;
- the checker both rejects some legal weeks and misses some broken ones;
- errors are invisible;
- the importer has week-range and reference faults;
- two privacy questions sit in the anonymiser.

Items from it move here as they are scheduled.
