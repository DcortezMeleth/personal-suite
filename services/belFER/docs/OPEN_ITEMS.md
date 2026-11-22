# belFER — open items

**Everything still to do, in one place.** Started 2026-11-21. Each item says
where its detail lives; this file is the list, not the explanation. When an
item is done, strike it through with the date and the commit or run, and
leave it for a while before deleting it.

Rules are in [RULES.md](RULES.md) (1.5), runs in [RUNLOG.md](RUNLOG.md), the
product phases in [ROADMAP.md](ROADMAP.md).

---

## Where things stand

A complete eight-slot week is generated through the app in about 2–3
minutes and keeps all 19 checks (RUNLOG R12, 2026-11-22; 21 since RULES 1.5); it is saved as the
school's current plan. Settings: teacher free periods at most 1 a day and 2 a
week, class days at most 3 lessons apart. **The data it runs on is
incomplete** (section 1), so those runs are easier than the real school.

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
   entered. Since RULES 1.4 `PlanCheck` checks each attendance group, so such a
   week is rejected rather than saved. RULES G2, FEASIBILITY.
3. **Attendance-group student counts.** A cohort group is written with the
   number of subgroups (2) rather than the pupils. Harmless while room
   capacity is not modelled (every room is written as 10000). FEASIBILITY.
4. ~~**Teacher free-period counts are not checked.**~~ Checked since RULES
   1.5 (R15b per day, R15c per week).
5. **G5 returns if the daily teacher setting goes above 1.** FET cannot say
   "never 2 in a row". RULES G5.
6. **G6 remainder.** A re-import loses the PE room requirement unless the
   importer sets it; R3c could not see the old fault. RULES G6.
7. **G7: teacher defaults.** The school default for working days is never
   sent, and a per-teacher daily cap above the default is overridden. Harmless
   at today's defaults (5 and 8). RULES G7.
8. ~~**S3 is checked only indirectly.**~~ Checked since RULES 1.4 (R4).
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
   - Fixed 2026-11-22 (REVIEW I2, I4–I7, I11): tuition by week range and
     merged codes, minimal doubles, unknown references reported, the upload
     cap enforced while reading.

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
2. ~~**The local server on 8081** runs code from 2026-11-01.~~ Restarted on
   2026-11-22 with current code; log in `~/.belfer/server.log`.
3. ~~**Frontend production build in this shell.**~~ Solved 2026-11-22: the
   shell's default `node` (`/usr/local/bin/node`) is an x64 build, and only
   the arm64 rollup binary is installed. Use the arm64 Node from nvm, e.g.
   `PATH=~/.nvm/versions/node/v22.22.3/bin:$PATH npm run build`; the dev
   server needs it too.

## 6. Code review findings

The full review of 2026-11-21 is in
[REVIEW_2026-11-21.md](REVIEW_2026-11-21.md). Its "Fix first" items 1–7 are
done (2026-11-22). Still open from item 2: a re-import replaces hand-entered
data, though it now says so first; upserting by code is ROADMAP 2.3.

What remains, grouped (IDs are the review's):

1. **Privacy.**
   - The anonymiser's roll "noise" can be reversed (I8).
   - Its `verify()` checks less than it says (I12).
   - A failed clean-up leaves `.fet` files with staff names in
     `~/.belfer/fet` (F10).
   - The API has no login and compose publishes 8081 directly (A13).
2. **Generation robustness.**
   - A stop hides complete weeks that broke a rule (F1).
   - `--read` maps by lesson order, which no query fixes (F2).
   - Edits during a run are not noticed (F3).
   - A stop or timeout loses FET's diagnosis (F5–F7).
   - FET can outlive the server (F8).
   - Stop is heard only while FET runs (F11).
   - A cancelled start can stick (F13).
3. **Validation before generating.**
   - Teacher load leaves out tuition (D8).
   - The block count ignores S4's whole/half link (D9).
   - S5's assumptions are unchecked (D10).
   - Unequal half hours are marked blocking against S2 (D11).
   - Settings can be relaxed past T5 (D12).
   - Room supply counts PE and tuition rooms (D13).
   - One overload gives three blockades (D15).
4. **API and data.**
   - Routes do not stay inside one school (A4).
   - Nothing is tested against the database or over HTTP (A6).
   - Smaller items: A7–A12.
5. **Frontend.**
   - Switching class quickly can show the wrong lessons (U2).
   - A failed plan load reads as "no plan" (U3).
   - Overloads are reported twice (U4).
   - Most inputs have no accessible name (U5).
   - The homeroom list is empty without the report (U6).
   - About a dozen small UX items (U8).
6. **Importer, low.**
   - Week windows are right only within a suggested segment (I9, I10).
   - Parsing edge cases (I11).
   - Missing tests (I14).
7. **Clean-up.** Dead code and comments that are now false: F18–F20, D14,
   D16–D18, A14, I13, U9.
