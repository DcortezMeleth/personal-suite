# belFER — scheduling rules

**Version 1.5 · 2026-11-22**

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
These are not the checker's codes: `PlanCheck` numbers its checks R1–R17
(with R3b–R3e and R5b; R10 was folded into R9 in 1.4, and R6 is not
checked), a separate list that §6 maps onto this one.

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
lessons in one class, **every whole-class lesson starts with both halves having
had the same number of their own lessons of that subject earlier in the
week.** Otherwise the whole class meets while one half has covered material the
other has not.

> *Source:* school, 2026-11-21. It was already in REQUIREMENTS §6 (the
> language-swap example) but never made the rule list. FET cannot state it
> exactly, so it is written out in full here. That way a solver that *can*
> state it (CP-SAT) has the complete definition rather than FET's
> approximation of it.

#### S5 in full

**Scope.** For one class *c* and one subject *s*:

- *W* is the set of whole-class lessons of *s* in *c*.
- *H₁* is the set of lessons of *s* that only half 1 attends; *H₂* the same for
  half 2.
- Basic and extended lines count alike: a half's lessons are all its lessons of
  the subject, whatever line they come from.
- Lessons for one half only (a cross-class unit, an attendance group) are not
  in scope. Neither is a subject with no whole-class lessons (S4 alone covers
  that) or with no half lessons.

**Order.** Lesson *x* is *earlier than* lesson *y* when it is on an earlier day
(Monday to Friday), or on the same day and starts at an earlier hour. A
multi-hour lesson counts as one lesson, ordered by its start.

**The condition.** For every whole-class lesson *w* in *W*:

  |{ h ∈ H₁ : h earlier than w }| = |{ h ∈ H₂ : h earlier than w }|

**Why "earlier in the week" and not "since the last whole-class lesson".**
Material accumulates from the first week of the term, when both halves start
at zero. Each half has the same number of lessons every week, so the two
halves are level at a whole-class lesson exactly when they are level counting
from Monday of that week.

Counting from the previous whole-class lesson, wrapping round the weekend,
gives a different and wrong answer. Take half 1 on Friday, half 2 on Monday,
and the whole class on Wednesday. Counting back from Wednesday, each half has
had one lesson since last Wednesday, which looks level. In fact half 2 meets
the whole class one lesson ahead every week of the term.

**Two equivalent ways to say it.** Both are useful when encoding.

- *Segments.* The whole-class lessons cut the week into segments: before the
  first, between consecutive ones, and after the last. Every segment holds as
  many half-1 lessons as half-2 lessons.
- *Pairs.* Pair the *i*-th half-1 lesson of the week with the *i*-th half-2
  lesson. No whole-class lesson falls strictly between the two lessons of a
  pair.

**Assumptions.** Both are true of all 30 current cases. Validation should flag
any case that breaks them.

- **Both halves have the same number of lessons of the subject a week.** If
  they did not, the halves would drift apart every week whatever the timetable
  did, and the rule would have no answer. That is a question for the school,
  not a scheduling case.
- **Both halves have the same block shapes.** Lessons are counted, not hours.
  If one half had a double where the other had two singles, "level" would need
  defining in hours, and the rule would need restating.

**Worked examples.** 1C biologia: 1 whole-class lesson, 2 per half; days only,
because S4 already keeps the whole class off the halves' days.

| Mon | Tue | Wed | Thu | Fri | | Why |
|---|---|---|---|---|---|---|
| H₁ H₂ | W | | H₁ | H₂ | ✅ | before W: one each |
| H₁ | W | H₂ | H₁ H₂ | | ❌ | before W: half 1 has 1, half 2 has 0 |
| W | H₁ H₂ | | H₁ | H₂ | ✅ | before W: none for either |
| H₁ | H₂ | W | H₁ | H₂ | ✅ | before W: one each; the pair Mon/Tue is on different days |
| H₁ H₂ | H₁ | W | H₂ | | ❌ | before W: half 1 has 2, half 2 has 1 |

**Where it applies today.** 30 class–subject cases. The numbers in the shape
column are whole-class lessons + lessons per half; "valid day patterns" counts
the day layouts S4 allows that also satisfy S5.

| Shape | Cases | Valid day patterns |
|---|---|---|
| 1 + 1/1 | 2A, 2F, 2G, 2H biologia and chemia; 3F, 4G fizyka | 60 of the 80 S4 allows |
| 1 + 2/2 | 1C, 1D, 1E, 4C, 4D, 4E biologia and chemia; 2G fizyka | 126 of 180 |
| 2 + 1/1 | 1G fizyka | 60 of 90 |
| 2 + 2/2 | 2C, 2D, 2E chemia | 60 of 90 |
| 3 + 2/2 | 2C, 2D, 2E biologia | 10 of 10: S4 alone already guarantees S5 |

#### How S5 is given to each solver

**FET (decided 1.2): each pair at most one day apart, together with S4.**

- *Pairing.* A half-1 lesson is paired with a half-2 lesson of the same line
  kind (basic with basic, extended with extended), then in block order.
  Lessons FET cannot tell apart are interchangeable, so the fixed pairing costs
  nothing among them.
- *Constraint.* `MaxDaysBetweenActivities` with MaxDays = 1 on each pair.
- *Why it is enough.* S4 keeps every whole-class lesson off the halves' days.
  A pair on the same day or on adjacent days leaves no whole day strictly
  between its two lessons, so no whole-class lesson can fall between them.
  Every pair therefore stays inside one segment.
- *What it gives up.* It allows only a subset of the valid patterns, and never
  lets a violation through:

  | Shape | Kept |
  |---|---|
  | 1 + 1/1 | 44 of 60 |
  | 1 + 2/2 | 102 of 126 |
  | 2 + 1/1 | 54 of 60 |
  | 2 + 2/2 | 60 of 60 |
  | 3 + 2/2 | 10 of 10 |

  What it forbids is a pair two or more days apart with no whole-class lesson
  between them, for example half 1 on Monday, half 2 on Thursday, whole class
  on Friday.
- *Rejected alternatives,* counted the same way, leaving out 3 + 2/2, where
  anything S4 allows is valid:
  - pairs on the same day keeps 24–50%;
  - all half lessons before (or all after) the whole-class ones keeps 18–50%;
  - whole-class only on Monday or Friday keeps 15–57%.
- *Why not exact.* FET has no disjunction that depends on where another
  activity lands. Its "pair of mutually exclusive sets of time slots" takes
  fixed sets of slots, whereas S5's dividing line is wherever the whole-class
  lesson falls.

**CP-SAT (not built): exact.** Per class–subject case and whole-class lesson
*w*, for each half lesson *h*:

- `before[h][w]` is a Boolean reified on `start(h) < start(w)`, where start is
  the week-absolute slot, day × slots + hour;
- add `Σ_{h∈H₁} before[h][w] = Σ_{h∈H₂} before[h][w]`.

That is linear, about |W| × (|H₁| + |H₂|) Booleans per case, a few hundred in
total. With S4 in the model, day-level start variables are enough.

**Our checker: exact.** `PlanCheck` verifies the condition as written above,
whichever solver produced the week.

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
>
> **The school's settings are stricter than this rule**, and that is allowed:
> since 2026-11-21 they are **at most 1 a day and at most 2 a week** (RUNLOG
> R6–R10). At 1 a day, two in a row cannot happen. T5 stays the limit the
> settings may be relaxed to.

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

All were off in 1.0, until a legal week could be generated at all. P2 and P3
are on since 1.3, as bounds the school sets.

**P1. A lesson in its teacher's own room.** Kept in the model: a teacher may
have a home room. Weight 0. **No teacher has one in today's data** (0 of
77). The placeholder rooms seeded on 2026-10-30
(`testdata/seed-home-rooms.sql`, written for 75 teachers) are gone, probably
with a re-import, and no real list has been entered.

**P2. Even class days: a class's longest day minus its shortest is at most
N lessons**, counted per group of pupils who share a timetable (per half;
per attendance group within a half once the school has entered attendance),
an empty day counting as zero. **N is a school
setting** (`max_class_day_spread`, Konfiguracja); unset, the rule is off.
**Set to 3 on 2026-11-21** ("fine for now"). When set, it is hard: FET must
keep it.

> *Source:* school. Was "at most 2–3", off, until 1.3. Measured: at 2 the week
> completes in about 4 minutes, at 3 in about 2 (RUNLOG R8–R11).

**P3. Fewer teacher windows**, towards 2–3 a week. FET cannot weigh this (ADR
0005). The only lever is tightening T5's weekly cap, one run at a time. Now at
2 a week (see T5).

**P4. Difficult subjects in the morning.** Post-MVP, not built.

---

## 6. Coverage — what FET is told, and what we check

✅ covered · ⚠ partly · ❌ not at all. Gaps G1–G10 are described below the
table.

| Rule | What FET is told | `PlanCheck` | |
|---|---|---|---|
| S1 | Student structure: class = year, halves = groups, attendance groups = subgroups | R1, per attendance group within each half | ✅ |
| S2 | `StudentsSetMaxGapsPerDay = 0` on every class, applied per subgroup; religia/etyka with no attendance entered: `ActivityBeginsOrEndsStudentsDay` | R9, per attendance group within each half; groups not entered are assumed to exist (G1), which makes it "first or last". Was R9 per half plus R10 per class | ✅; G2 is now visible to R9 |
| S3 | A class-occupancy activity plus `ActivitiesSameStartingTime` | R4: all groups of a unit's block at one hour. Before 1.4 nothing checked it; "indirectly, through R1" was wrong | ✅ |
| S4 | `MinDaysBetweenActivities` per class + subject + audience; whole-class blocks spread with each half's | R5 per audience; R5b the whole-class link | ✅ |
| S5 | `MaxDaysBetweenActivities` ≤ 1 per pair of half lessons, which is stronger than S5 | R16, exact | ✅ stronger than the rule |
| S6 | Activity duration | Contiguous by construction; R12 checks it ends within the day | ✅ |
| S7 | Built in | R7: counted and matched block by block | ✅ |
| S8 | 8 hours in the file, plus `StudentsSetMaxHoursDaily` | R12 (every lesson within the week's days and hours) with R1 | ✅ |
| T1 | Built in; support teacher and tuition are named on the activity | R2 | ✅ |
| T2 | `TeacherNotAvailableTimes` | R11 | ✅ |
| T3 | `TeacherMaxDaysPerWeek`, per-teacher overrides only | R13 | ⚠ G7 |
| T4 | `TeachersMaxHoursDaily` + `TeacherMaxHoursDaily` | R14 | ⚠ G7 |
| T5 | `TeachersMaxGapsPerDay` and `TeachersMaxGapsPerWeek` from the school's settings, now 1 and 2, stricter than T5 | R15 run length, R15b per day, R15c per week (when set); an hour the teacher is unavailable is not a free period, as FET counts | ✅ |
| RM1 | Built in | R3, R3d | ✅ |
| RM2 | `ActivityPreferredRooms`: the six PE spaces, hard (data fixed 2026-11-21) | R3c could not catch the old fault | ⚠ G6 |
| RM3 | `ActivityPreferredRooms`: rooms of the individual-tuition type | R3c | ✅ |
| RM4 | `ActivityPreferredRooms`: rooms with no type; whole-class lessons only those that fit | R3b, R3c (a wanted kind may use a classroom too), R3e (every lesson has a room) | ✅ |
| P1 | Off (weight 0, and no home rooms in the data) | — | — |
| P2 | Per class, a range of day lengths N wide around its own average: `StudentsSetMinHoursDaily` and `StudentsSetMaxHoursDaily`, per subgroup where loads differ. Stronger than the rule | R17, per half, and per attendance group once attendance is entered, as FET is told | ✅ stronger than the rule |
| P3 | Through T5's weekly setting | — (not counted; see G5) | ⚠ |

**G1. Religia and etyka without attendance numbers — closed 2026-11-21 (RUNLOG R4).** Attendance groups are
written only where the school has entered how many pupils attend each subject.
None are entered, so every optional lesson falls back to
`ActivityEndsStudentsDay`. That means *last only*, which is much stricter than
S2, and it is the configuration that stalled at 432 of 1331. **Decided (1.1):**
when nothing is entered, treat it as if all three attendance groups existed,
which is exactly "first or last" for every class. **Encoding (1.2):** FET 7.x
states that directly as `ActivityBeginsOrEndsStudentsDay`, which applies per
subgroup, so a block shared by seven classes sits at the edge of each class's
day. That replaces `ActivityEndsStudentsDay`, and needs neither attendance
numbers nor the subgroup mechanism G2 is about. FET 6.8.5 does not have it,
hence the engine upgrade (RUNLOG). Entering real numbers still relaxes it
where a group is empty.

**G2. The open defect.** In a completed week, 16–18 of the 46 attendance groups
had a one-hour window although FET had been told no windows (FEASIBILITY.md,
"The open defect"). This is unexplained. Since 1.4 the checker can see it: R9
looks at each attendance group, so a week with such a window is rejected
rather than saved. What is still unknown is whether FET 7.12.1 produces it.

**G3. S4's whole-class link — closed 2026-11-21 (RUNLOG R5).** Blocks are grouped per audience, so FET and R5
both allow a whole-class block and a half's block of one subject on the same
day. Fix: add the whole-class activities to each half's spreading group, as
`MinDaysBetweenActivities`, and make R5 check across audiences. To be built
together with G4, which relies on it.

**G4. S5 — closed 2026-11-21 (RUNLOG R5).** Decided (1.2): give FET "each pair at
most one day apart", which keeps 73–100% of the valid patterns and never lets
a violation through. Give our checker the exact condition. The full definition
and the reasoning are under S5 above. Same-day pairing was rejected in 1.1.

**G5. Teacher windows.** FET cannot say "no 2 in a row". It counts gaps per day,
never the length of a run. At 2 a day it can still leave a 2-hour hole. R15
catches that afterwards, but nothing checks the daily or weekly counts.
**Moot since 2026-11-21** while the daily setting is 1 (RUNLOG R6): one free
period a day cannot be two in a row. Open again if it is raised. Since 1.5 the
checker counts the daily and weekly free periods itself (R15b, R15c), so
whatever FET is told, a week over either setting is rejected.

**G6. PE in classrooms — fixed in the data, 2026-11-21.** Wychowanie fizyczne
and its 14 PE units (of 17 cross-class units; the other 3 are religia and
etyka) had no room type. FET was therefore putting PE groups in classrooms.
They now require Sala gimnastyczna, hard, and the export confirms all 138 PE
group lessons are confined to the six PE spaces. Still open: R3c could not see
the fault, and a re-import would lose the setting unless import sets it.

**G7. Teacher defaults, minor.** The school-wide default for working days is
never sent; only per-teacher values are. A per-teacher daily cap *above* the
school default is overridden by the default. Both are harmless with today's
defaults (5 and 8).

**G8. A setting outside this spec was on — fixed 2026-11-21.** The shortest
class day was set to 4 and sent to FET as a hard rule. It is now cleared.

**G9. The legacy CP-SAT solver — removed 2026-11-21.** The last commit with it
is `2a9a1a4`. If it is brought back, this is what it lacked: attendance
groups, S4's whole-class link, S5, and P2. Its "starts at slot 1" rule had
already been taken out, and its religia edge rule already meant "first or last
of the class's own day".

**G10. Exporter off-by-one — fixed 2026-11-21 (RUNLOG R4).** The first individual-tuition lesson reuses the
group number of the last cross-class unit. FET is therefore told to keep that
lesson on a different day from the unit's blocks, a rule nobody asked for.
Found 2026-11-21; see RUNLOG.

---

## 7. Open questions and TODO

- **Individual tuition** — how the school runs it, the data, what is wrong
  today and the designs: [INDIVIDUAL_TUITION.md](INDIVIDUAL_TUITION.md)
  (draft, 2026-11-22). A pupil stays in their class and takes some subjects
  individually; today FET does not know the pupil exists, and the week of
  RUNLOG R12 double-books pupils on 22 pupil-days. The rule (S9) waits on the
  open questions there.
- **Reconsider CP-SAT against FET** once the rules here are settled. ADR 0004
  compared the two on different rule sets: CP-SAT was carrying "starts at slot
  1", which FET could not express. See SOLVER_EVALUATION's addendum. The code
  was removed on 2026-11-21 and is in git history (G9).
- **Specialist rooms beyond PE**, if the school needs them later.
- **Support teachers (*nauczyciel wspomagający*): none in the data.** T1 and
  the model cover them (a second teacher on a lesson line), but the arkusz's
  support-teacher rows came in as 30 hours of phantom individual tuition and
  were deleted, and how the arkusz books them is still unknown (REQUIREMENTS
  §8, ROADMAP). If the school has any, their hours are missing from every
  generated week.
- **Teacher home rooms (P1): none in the data** (see P1).
- **P2 and attendance groups.** The range is per subgroup, so once attendance
  is entered, pupils who skip religia get a range for their own shorter
  week. R17 checks halves, not attendance groups (as with G2).

---

## 8. Changelog

### 1.5 — 2026-11-22

No rule changed. T5's daily and weekly counts are checked (R15b, R15c), not
only its run length; they had been FET's word alone. Free periods exclude hours
the teacher is unavailable, as FET counts them, and R15's run length now does
too.

### 1.4 — 2026-11-22

No rule changed. The checker is brought in line with this file after the
review of 2026-11-21 (REVIEW_2026-11-21 D1–D7):

- **S1, S2, P2 per attendance group.** `PlanCheck` now knows each class's
  groups of pupils: half × attendance group, the groups not entered assumed
  to exist (G1). R1 and R9 work per group; the class-level "first or last"
  check (R10) is folded into R9, where religia mid-day is a gap for those who
  skip it. It wrongly rejected religia ending one half's day and starting the
  other's. R17 measures attendance groups once attendance is entered.
- **S3 checked** (R4). It was not, despite §6 saying "indirectly".
- **S7 matched, not only counted** (R7); **S6/S8 bounded** (R12 now means
  "every lesson within the week").
- **RM2–RM4:** a soft requirement may use a classroom (R3c rejected it), and
  every lesson must have a room (R3e).

### 1.3 — 2026-11-21

- **P2 on, as a configurable bound:** a class's longest day minus its
  shortest at most N, N a school setting, set to 3. Was "2–3", off. Given to
  FET per class as a range of day lengths; checked exactly by R17.
- **T5 coverage:** the school's settings are now 1 free period a day and 2 a
  week, stricter than T5, which stays the limit they may be relaxed to. G5 is
  moot while the daily setting is 1.
- **P1:** recorded that no teacher has a home room in today's data.
- **TODO added:** support teachers, absent from the data.
- **G9 closed:** the legacy CP-SAT solver is removed, kept in git history.

### 1.2 — 2026-11-21

No rule changed in meaning. S5 is written out in full, precisely enough to
model exactly in a solver that can, and two encodings are decided:

- **S5 restated** as "both halves level at every whole-class lesson, counting
  from Monday", which is equivalent to 1.1's pairs wording. Added: why it
  counts from Monday and not since the last whole-class lesson, its two
  equivalent forms, its assumptions, worked examples, where it applies, and an
  exact CP-SAT formulation.
- **G4 decided:** for FET, each pair at most one day apart, plus S4's
  whole-class link (G3). The checker gets the exact condition.
- **G1 encoding:** native "begins or ends the students' day" (FET 7.x)
  replaces "ends", once the engine is upgraded.

### 1.1 — 2026-11-21

No rule changed. Two decisions on how rules are given to FET, and coverage
brought up to date:

- **G1:** with no attendance entered, assume all three attendance groups. This
  replaces "last only".
- **G4:** S5 must be encoded exactly. Same-day pairing is rejected as too
  restrictive.
- **G6 and G8 fixed in the data.** PE rooms, and the shortest-day bound.
- **G10 added:** the exporter off-by-one.

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
