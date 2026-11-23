# Individual tuition — how it works and how to model it

**Decided 2026-11-22; not built yet.** This records how the school runs
individual tuition, what the data says, what is wrong today, and the design.
The rule itself is RULES S9; this file keeps the reasoning.

---

## 1. How the school does it

From the planner, 2026-11-22.

### Three kinds of pupil

| | Lessons with the class | Windows allowed | Room |
|---|---|---|---|
| **Remote** (*zdalny*) | none | **yes** | yes: the teacher connects from a room |
| **On-site** (*stacjonarny*) | none | no | yes |
| **Mixed** (*mieszany*) | some | no | yes |

- **Remote and on-site pupils have only individual lessons,** none with their
  class, so for timetabling each is a class of their own. A remote pupil's
  day may have windows; an on-site pupil's may not.
- **A mixed pupil** attends most lessons with their class and takes a few
  subjects individually, typically maths. They are out of every class lesson
  of those subjects. Where the class has more hours of the subject than the
  pupil has individually (2 of 4 maths), in the class's remaining hours the
  pupil has another of their individual lessons, or is free; and if free, the
  hour must be the first or last of their day. The same holds where the
  individual teacher is the class's own teacher of the subject, and the two
  cannot run together.

### What the arkusz says, and what it does not

- **The individual part is a pseudo-class:** the class code followed by the
  pupil's initials. A subject listed under it is taught to the pupil
  individually; anything else (for a mixed pupil) is with the class.
- **Codes that differ only in case are one pupil,** an error in the data. They
  are to be merged, not kept apart.
- **A group number on an individual row is ignored.** One pupil cannot be split
  in half.
- **Not in the arkusz,** to be entered by the planner in the app:
  - **the kind** of each pupil (remote, on-site, mixed);
  - **the half** a mixed pupil is in, which decides which half-class lessons
    (languages, informatics, …) they attend.

### Subjects

- **Rewalidacja and TUS** exist only for individual pupils; whole classes
  never have them.
- **Doradztwo zawodowe** is an ordinary 4th-year subject. In the arkusz it is
  one hour in week 30 only, so a plan of weeks 1–19 rightly has none. The 5
  individual doradztwo lessons in today's data came from the importer reading
  tuition from every week regardless of the range chosen. That is fixed
  (`cb6e819`) and a re-import drops them.
- **German** is in the arkusz: 1C, 1F, 2C, 2F, 4C and 4F have 2 hours each,
  in halves. One individual German lesson belongs to a 3F pupil whose class
  has none. Worth checking in the original file.
- **Rooms.** An individual lesson may be held in a tuition room or in an
  ordinary classroom. A lesson of a whole class (or a half) never goes into a
  tuition room.
- **Individual PE needs a room,** for simplicity. A remote pupil's PE strictly
  would not, but treating all alike is simpler, at some cost in flexibility.

### How it was handled before belFER

Neither of the planner's previous programs could model it. For mixed pupils,
she added a support teacher (*nauczyciel wspomagający*) standing for the
individual teacher to the class's lessons of the subject. The class and the
pupil then had the subject in the same slots, so the pupil had no gap. No room
was booked for the individual lesson; she did that by hand. **This stays the
fallback** (§5).

---

## 2. What the data says

Measured 2026-11-22 on the imported school. Aggregates only; the database
holds no pupil identity, only class and ordinal.

- **23 pupils with an individual plan**, in 15 classes; 118 individual
  lessons, 203 hours a week. All of them are booked into the 6 tuition rooms
  today: 85% of those rooms' 240 slots a week.
- **The plans split by size**, which probably follows the kind:
  - **9 large plans,** 14–24 hours over 7–13 subjects, plus one of 7 subjects
    at an hour each: likely remote or on-site.
  - **13 small plans,** 2–5 hours over 1–3 subjects, mostly rewalidacja:
    likely mixed.
- **Small plans (likely mixed): 28 lessons.**
  - 16 are of a subject the class also has.
  - All 16 give the pupil fewer hours than the class sits, for example 2 of
    the class's 4 hours of maths.
  - 7 of the 16 are taught by the class's own teacher of the subject.
  - The rest are rewalidacja, TUS, and the doradztwo lessons that a re-import
    drops.
- **Large plans: 90 lessons,** 42 taught by the class's own teacher and 54
  with fewer hours than the class. None of this matters if these pupils are
  remote or on-site, since they have nothing with the class.

## 3. What is wrong today

An individual lesson books its teacher and a tuition room, and nothing else.
FET does not know the pupil exists. In the week generated on 2026-11-22
(RUNLOG R12), measured:

- **22 pupil-days where a pupil has two individual lessons in the same hour.**
- **39 pupil-days with a window** between a pupil's own lessons. That is wrong
  for on-site and mixed pupils.
- **Small plans:** 28 of 34 lesson hours fall during a lesson the whole class
  is in, and 2 during a lesson of one half.

The checker cannot see any of it, for the same reason.

---

## 4. The rule, as far as it is known

Draft of S9:

> Every individual pupil has a kind, set by the planner.
> - **All kinds:** no two lessons at once (S1); every individual lesson in a
>   room.
> - **Remote and on-site pupils** have only their individual lessons. An
>   on-site pupil's day has no windows (S2); a remote pupil's may.
> - **A mixed pupil** belongs to one half of their class. They attend that
>   half's and the whole class's lessons, except in subjects listed in their
>   plan, which they are taught individually. Their day (the class lessons
>   they attend plus their individual lessons) has no windows (S2) and at most
>   eight lessons (S8).

Open points in it are §6.

---

## 5. Design

Each pupil becomes a student set of their own, so FET knows they exist. How
depends on the kind:

- **Remote:** a FET year of its own, no no-gaps rule.
- **On-site:** a FET year of its own, with no-gaps and the 8-lesson cap.
- **Mixed:** a subgroup inside their half of the class. Then:
  - class lessons the pupil attends stay addressed to the class or the half,
    which now include the pupil;
  - class lessons of a subject in the pupil's plan are addressed to the
    class's other subgroups by name, so the pupil is free then;
  - FET's no-gaps rule, already on every subgroup, keeps the pupil's day
    whole. It may put an individual lesson where the class has that subject,
    when the teachers differ, or at the start or end of the pupil's day.
    Rewalidacja and TUS go wherever the pupil is free without leaving a gap,
    including an hour the class has a subject the pupil does not attend.
  - day-length ranges (P2) and the 8-lesson cap already work per subgroup.
- **Every kind:** individual lessons are addressed to the pupil, with their
  teacher and a room (§6).
- **The checker** gains the pupil as a group of pupils of its own, per kind.
  R1, R9 (skipped for remote pupils), R12 and R17 then hold for them with no
  new rule.
- **Data and UI.** Each individual plan gets a kind and, for a mixed pupil, a
  half; both are editable on the Nauczanie indywidualne page. Import leaves
  them unset, and Walidacja reports any unset. **Import merges** pseudo-class
  codes that differ only in case.

**For:** exact; books rooms; handles rewalidacja, double bookings and
windows; uses FET as it is meant to be used.

**Against:** a harder search. How much harder is the first thing to measure.
Remote and on-site pupils should cost little: they share only teachers and
rooms. Mixed pupils are the hard part: few of them, but tight. And FET once
left windows in subgroups (RULES G2, on 6.8.5); the checker would now reject
such a week, but it has to be re-tested on 7.12.1.

**Fallback for mixed pupils:** the planner's support-teacher workaround,
emulated (the individual teacher added to the class's lessons of the
subject), for the subjects where it fits: a different teacher, and the same
hours.

---

## 6. Decisions (2026-11-22)

1. **Rooms:** an individual lesson may use a tuition room or an ordinary
   classroom; class lessons never use tuition rooms. This also takes the
   pressure off the 6 tuition rooms (85% full today).
2. **Fewer hours than the class:** in the class's remaining hours of the
   subject the pupil has another individual lesson or is free, and a free
   hour must be first or last in their day (§1).
3. **Same teacher:** answered by 2; the individual lesson is a separate hour.
4. **Kind or half not entered:** there is no UI for it yet (the tuition page
   is read-only); it is to be built (§7). Until a pupil's kind is set,
   Walidacja reports it as blocking, and the export treats the pupil as
   on-site: a class of their own with no windows, which never double-books
   them and never leaves them waiting, though it misses a mixed pupil's class
   lessons. Proposed; to confirm.

---

## 7. Steps

Done (2026-11-22):
- 1: RULES S9; RM3's rooms (`79d0b86`).
- 2: import merges case variants (`d72a945`).
- 3: kind and half on the Nauczanie indywidualne page.
- 4: the checker, R18 and R19 (`731eb62`).
- 5: remote and on-site pupils in FET (`864fbb8`); RUNLOG R13 complete in
  220 s against R12's 139 s, with no pupil double-booked or waiting.
- 6: mixed pupils as subgroups of their half.

**What remains: the planner sets each pupil's kind (and half), then a run
measures mixed pupils on real data.** All 24 plans are unset today and so
planned as on-site.

1. RULES.md gets S9.
2. **Import:** merge case-variant pseudo-class codes.
3. **Data:** a kind and a half on each individual plan, editable on the
   Nauczanie indywidualne page; Walidacja flags unset ones.
4. **Checker first:** `PlanCheck` learns the pupils. Run it on the saved week
   to see how far it is from the rule; it is expected to fail.
5. **Exporter,** remote and on-site pupils first, mixed second. A run after
   each, in RUNLOG, measured against R12's 139 s.
6. If mixed pupils make the search too slow, the support-teacher fallback for
   the subjects where it fits.
