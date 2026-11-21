# belFER — scheduling rules

**Version 1.0 · 2026-11-21**

The rules a generated timetable must obey, and the preferences it should try to
meet. **This file is the source of truth for rules.** REQUIREMENTS §9 is where
they were first gathered and stays as history; where the two disagree, this
file wins.

Every rule states where it came from. §6 then says, rule by rule, how FET is
told about it and how our own checker (`PlanCheck`) verifies it. A rule FET is
not told about is listed there as a gap rather than left to be rediscovered.

**Changing this file.** Any change to a rule (added, removed, or reworded so
that it means something different) bumps the version and gets an entry in the
changelog (§8) saying what changed and why. Typo fixes don't.

**IDs.** S = students and classes, T = teachers, RM = rooms, P = preferences.
These are not the checker's codes: `PlanCheck` numbers its checks R1–R15, a
separate list that §6 maps onto this one.

---

## 1. The week

- **5 days × 8 lesson slots.** There is no ninth slot: the school day does not
  run past eight lessons.
- **A plan covers one stretch of weeks** over which the allocation is constant
  (REQUIREMENTS §4.1). Every rule is evaluated within that stretch only.
- **A class's day may start at any slot.** It need not start at the first one.
- **Any day lengths that add up are fine.** A 38-hour class can run
  8/8/8/8/6 or 8/8/8/7/7, in any order of days.

---

## 2. Hard rules — students and classes

**S1. Nobody is in two places.** A class, a half, an attendance group (§2 S2) or
a cross-class group is never in two lessons at once.

**S2. No student ever has a window.** Every pupil's day is unbroken from their
first lesson to their last. It holds per pupil, not per class, which is what
gives it its two consequences:

- **Halves.** A lesson for one half is either matched by a lesson for the other
  half at the same time, or sits at the start or end of the day, so that the
  other half comes in later or leaves earlier.
- **Religia and etyka.** Pupils who do not attend must not wait, so the lesson
  sits at the edge of *their* day. This also holds when the lesson is shared by
  several classes: each class's non-attenders are protected.

The pupils of a class therefore fall into **attendance groups**: those who
attend religia, those who attend etyka, and those who attend neither. The rule
applies to each group within each half.

> *Source:* school. "No windows for students" is confirmed. Wording the
> religia/etyka case as "non-attenders never wait" is ours: it came out of the
> FET cohort work. The school's own wording was "first or last", and the two
> are accepted as equivalent. They differ in one case only: everybody in a
> class attends one of the two subjects, and both run at the same time
> mid-day. "Non-attenders never wait" allows that case; "first or last" does
> not.

**S3. Cross-class units move together.** Every class contributing pupils to a
cross-class unit (all PE, and some religia and etyka) has that unit at the same
hours.

**S4. One block of a subject a day, per group.**

- Counted per audience. Both halves may have the same subject on the same day,
  e.g. 1A/1 angielski and 1A/2 angielski on Monday.
- Basic and extended hours of one subject count together as one subject.
- **A whole-class block and a half's block of the same subject never fall on
  the same day.** Otherwise that half has the subject twice.
- A cross-class unit has at most one block a day.

> In today's data 13 classes mix whole-class and half lessons of one subject:
> Biologia, Chemia, Fizyka. Where the whole-class part is large this is tight.
> 2C biologia is 3 whole-class hours + 2 per half, so it lands on all five
> days, whole-class on three of them and halves on the other two.

**S5. Halves stay level.** When a subject has both whole-class and half
lessons, the halves' lessons are matched in pairs: the first lesson of half 1
in the week (Monday to Friday) with the first of half 2, the second with the
second, and so on. **No whole-class lesson of that subject may fall between
the two lessons of a pair.** Otherwise the whole class meets while one half has
covered material the other has not.

> *Source:* school, 2026-11-21. It was already in REQUIREMENTS §6 (the
> language-swap example) but never made the rule list. All 30 current cases
> have the same number of lessons in both halves.

**S6. A multi-hour block uses consecutive slots on one day.** It may span the
long break.

**S7. Every block is placed exactly once.**

**S8. A class has at most 8 lessons a day.** This follows from §1.

---

## 3. Hard rules — teachers

**T1. Nobody is in two places.** This includes a teacher acting as a support
teacher (*nauczyciel wspomagający*) and a teacher giving individual tuition.

**T2. Unavailable hours are respected.**

**T3. Maximum working days a week.** The default is 5, and it can be overridden
per teacher.

**T4. Maximum lessons a day.** The default is 8, and it can be overridden per
teacher.

**T5. Teacher windows:**

- **never 2 free periods in a row;**
- **at most 2 free periods a day;**
- **at most 4 free periods a week.**

> *Source:* school, 2026-11-21. "4–5 a week is our cap", "no 2 consecutive",
> "a 2-a-day cap is fine if it's easier". The long-term aim is 2–3 a week (P3).

---

## 4. Hard rules — rooms

Three sorts of room: **PE spaces** (Hala 1–3, bilard, fitness, gimn),
**individual-tuition rooms** (NI, NI2, nowa_port, 57z, 58z, 59z), and
**classrooms**, which are everything else. Some classrooms hold only half a
class.

**RM1. A room never holds two lessons at once.**

**RM2. PE groups use PE spaces only.**

**RM3. Individual tuition uses individual-tuition rooms only.**

**RM4. Every other lesson uses a classroom.** It never goes in a PE space or an
individual-tuition room. A whole-class lesson needs a classroom that holds a
whole class.

> Out for now: specialist rooms other than PE. The "Pracownia chemiczna" room
> type is to be removed, and the soft/hard room-requirement flag on a subject
> has no use until a second specialist room type exists.

---

## 5. Preferences

**All off in 1.0**, until a legal week can be generated at all.

**P1. A lesson in its teacher's own room.** Kept in the model: a teacher may
have a home room. Weight 0.

**P2. Even class days:** longest day minus shortest day at most 2–3. Off.

**P3. Fewer teacher windows**, towards 2–3 a week. FET cannot weigh this (ADR
0005). The only lever is tightening T5's weekly cap, one run at a time.

**P4. Difficult subjects in the morning.** Post-MVP, not built.

---

## 6. Coverage — what FET is told, and what we check

✅ covered · ⚠ partly · ❌ not at all. Gaps G1–G9 are described below the
table.

| Rule | What FET is told | `PlanCheck` | |
|---|---|---|---|
| S1 | Student structure: class = year, halves = groups, attendance groups = subgroups | R1, for classes and halves but not attendance groups | ✅ |
| S2 | `StudentsSetMaxGapsPerDay = 0` on every class, which FET applies to each subgroup | R9 per half; R10 still checks "first or last" | ⚠ G1 G2 |
| S3 | A class-occupancy activity plus `ActivitiesSameStartingTime` | Only indirectly, through R1 | ⚠ |
| S4 | `MinDaysBetweenActivities` per class + subject + audience | R5 per audience | ❌ G3 |
| S5 | Nothing | Nothing | ❌ G4 |
| S6 | Activity duration | Cannot be broken | ✅ |
| S7 | Built in | R7 | ✅ |
| S8 | 8 hours in the file, plus `StudentsSetMaxHoursDaily` | R12 | ✅ |
| T1 | Built in; support teacher and tuition are named on the activity | R2 | ✅ |
| T2 | `TeacherNotAvailableTimes` | R11 | ✅ |
| T3 | `TeacherMaxDaysPerWeek`, per-teacher overrides only | R13 | ⚠ G7 |
| T4 | `TeachersMaxHoursDaily` + `TeacherMaxHoursDaily` | R14 | ⚠ G7 |
| T5 | `TeachersMaxGapsPerDay = 2`, `TeachersMaxGapsPerWeek = 4` | R15, run length only | ⚠ G5 |
| RM1 | Built in | R3, R3d | ✅ |
| RM2 | Would be `ActivityPreferredRooms`, but PE has no room type in the data | R3c cannot see it | ❌ G6 |
| RM3 | `ActivityPreferredRooms`: rooms of the individual-tuition type | R3c | ✅ |
| RM4 | `ActivityPreferredRooms`: rooms with no type; whole-class lessons only those that fit | R3b, R3c | ✅ |
| P1–P3 | Off | — | — |

**G1. Religia and etyka without attendance numbers.** Attendance groups are
written only where the school has entered how many pupils attend each subject.
None are entered, so every optional lesson falls back to
`ActivityEndsStudentsDay`. That means *last only*, which is much stricter than
S2, and it is the configuration that stalled at 432 of 1331. *Proposed* (see
§7): when nothing is entered, assume all three attendance groups exist. That is
exactly "first or last" for every class, and it needs no numbers. Entering real
numbers then only relaxes it, where a group turns out to be empty.

**G2. The open defect.** In a completed week, 16–18 of the 46 attendance groups
had a one-hour window although FET had been told no windows (FEASIBILITY.md,
"The open defect"). This is unexplained. The checker cannot see it, because R9
looks at halves, not attendance groups. Until it is settled, S2 is not
guaranteed for attendance groups. Halves rest on the same FET mechanism, but R9
does check them.

**G3. S4's whole-class link.** Blocks are grouped per audience, so FET and R5
both allow a whole-class block and a half's block of one subject on the same
day. Fix: add the whole-class activities to each half's spreading group.

**G4. S5 is not encoded anywhere.** One possible encoding: put each matched
pair on the same day (`ActivitiesSameStartingDay`). Together with S4, that keeps
whole-class lessons off those days, so none can fall between. This is stricter
than S5, because it forbids a pair on Monday/Tuesday with the whole class on
Wednesday. It needs a decision before it is built.

**G5. Teacher windows.** FET cannot say "no 2 in a row". It counts gaps per day,
never the length of a run. At 2 a day it can still leave a 2-hour hole. R15
catches that afterwards, but nothing checks the daily (2) or weekly (4) counts.

**G6. PE in classrooms.** Neither Wychowanie fizyczne nor any of its 17 units
has a room type set, so FET puts PE groups in classrooms and leaves the PE
spaces empty. R3c only flags a lesson that needs a type and did not get it, so
it cannot see this. Fix: set the type in the data, and decide whether import
should set it.

**G7. Teacher defaults, minor.** The school-wide default for working days is
never sent; only per-teacher values are. A per-teacher daily cap *above* the
school default is overridden by the default. Both are harmless with today's
defaults (5 and 8).

**G8. A setting outside this spec is on.** The shortest class day is set to 4
(`min_class_hours_daily`) and is sent to FET as a hard rule. P2 is off, so this
should be cleared.

**G9. The legacy CP-SAT solver** (the `solve` CLI; the app does not use it).
`CLASS_DAY_STARTS_FIRST` is on by default. Its religia edge rule takes "first"
to mean slot 1. It has no attendance groups, no whole-class link for S4, and no
S5. It is to be brought in line with this file before CP-SAT is reconsidered.

---

## 7. Open questions and TODO

- **G1's proposed default** (assume all three attendance groups when no numbers
  are entered). Awaiting confirmation.
- **G4's encoding** of S5. Same-day pairs, or something exact.
- **TODO — individual sessions for a pupil who is also in a class**
  (*rewalidacja*, *zajęcia wyrównawcze*). Must they avoid that class's
  lessons? Today they book only the teacher and a room. Deferred 2026-11-21.
- **Reconsider CP-SAT against FET** once the rules here are settled. ADR 0004
  compared the two on different rule sets: CP-SAT was carrying "starts at slot
  1", which FET could not express. See SOLVER_EVALUATION's addendum.
- **Specialist rooms beyond PE**, if the school needs them later.

---

## 8. Changelog

### 1.0 — 2026-11-21

First version. Consolidated from REQUIREMENTS §9 and the rules review of
2026-11-21.

Where the old rules went (REQUIREMENTS §9.1 number → here):

| Old rule | Now |
|---|---|
| 1 | S1 |
| 2 | T1 |
| 3 | RM1 |
| 4 | T2 |
| 5 | T3 |
| 6 | T4 |
| 7 | S7 |
| 8 | S4 |
| 9 | S6 |
| 10 | S2 |
| 11 | S3 |
| 12 | withdrawn |
| 13 | S2 |
| 14 | S8 |
| 15 | S2 |
| 16 | T5 |

What changed:

- **Withdrawn:** "a class's day starts at slot 1" (old 12). It was never the
  school's rule; it came from our first requirements pass.
- **Merged into S2, "no student windows":**
  - old 10, both halves busy. It is no longer described as a cost.
  - old 13.
  - old 15, religia/etyka first or last.
- **Added:**
  - S4's whole-class/half link.
  - S5, halves stay level.
- **Confirmed:**
  - S4 is counted per group (closes the §6 caveat).
  - There is no ninth slot.
  - A class has at most 8 lessons a day.
- **Changed — teacher windows (T5).** Was: never more than 2 in a row. Now:
  never 2 in a row, at most 2 a day, at most 4 a week.
- **Rooms simplified** to three sorts. Specialist rooms other than PE are out.
- **Preferences:** all off. The teacher's own room stays in the model.
