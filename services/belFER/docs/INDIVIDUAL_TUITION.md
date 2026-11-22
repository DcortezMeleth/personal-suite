# Individual tuition — how it works and how to model it

**Draft, 2026-11-22.** Not built. This records how the school runs individual
tuition, what the data says, what is wrong today, the designs considered, and
the questions still open. When the open questions are answered, the decided
rule goes into RULES.md and this file keeps the reasoning.

---

## 1. How the school does it

From the planner, 2026-11-22.

- **A pupil on individual tuition stays in their class.** They attend most
  lessons with the class (biology, history, …) and take a few subjects
  individually, typically maths.
- **The arkusz lists the individual part as a pseudo-class:** the class code
  followed by the pupil's initials, for example a class `4C` and a record
  `4C` + two letters. A subject listed under that record is taught to the
  pupil individually. A subject not listed there, they take with their class.
- **Rewalidacja is a subject only individual pupils have.** Whole classes
  never have it.
- **No windows for these pupils either** (RULES S2 applies to them).
- **When the class has a subject the pupil takes individually,** the pupil's
  individual lesson has to be at the same time; otherwise the pupil has a gap
  while the class is in that lesson.
- **Which half of the class the pupil is in** is not in the arkusz. The planner
  can find out, and it has to be settable in the app.

### How it was handled before belFER

Neither of the planner's previous programs could model it. Her workaround:
on the class's lessons of the subject, she added a support teacher
(*nauczyciel wspomagający*) standing for the individual teacher. The class and
the pupil then had the subject in the same slots, which kept the pupil free of
gaps. What it could not do was book a room for the individual lesson; she did
that by hand. **This stays the fallback** if nothing better works (§5, B).

---

## 2. What the data says

Measured 2026-11-22 on the imported school. Aggregates only; the database
holds no pupil identity, only class and ordinal.

- **23 pupils with an individual plan** in 15 classes; 118 individual lessons,
  203 hours a week.
- **Plan sizes split in two:**
  - 9 large plans of 14–24 hours over 7–13 subjects;
  - one of 7 subjects at an hour each;
  - 13 small plans of 2–5 hours over 1–3 subjects, mostly rewalidacja.
- **95 lessons are of a subject the class also has, and 70 of those give the
  pupil fewer hours** than the class sits (for example 2 individual hours of
  maths against the class's 4). 23 give the same; 2 give more.
- **49 lessons are taught by the class's own teacher of that subject.** That
  teacher cannot be with the class and with the pupil in the same hour.
- **23 lessons are of subjects the class does not have:**

  | Subject | Lessons |
  |---|---|
  | Zajęcia rewalidacyjne | 13 |
  | Doradztwo zawodowe | 5 |
  | Wychowanie fizyczne | 3 |
  | Język niemiecki | 1 |
  | TUS | 1 |

  Wychowanie fizyczne is listed only because the class has PE in cross-class
  groups, not as its own lessons.

## 3. What is wrong today

An individual lesson books its teacher and a tuition room, and nothing else.
FET does not know the pupil exists. In the week generated on 2026-11-22
(RUNLOG R12), measured:

- **22 pupil-days where a pupil has two individual lessons in the same hour.**
- **39 pupil-days with a window** between a pupil's own individual lessons.
- **Small plans:** 28 of 34 session hours fall during a lesson the whole class
  is in, and 2 during a lesson of one half.
- **Nothing ties an individual lesson to the class's lesson** of that subject.
  The pupil is shown as attending the class lesson and the individual one.

None of this is caught by the checker, because the checker does not know the
pupil exists either.

---

## 4. The rule, as far as it is known

Draft of the rule that would go into RULES.md (as S9):

> A pupil with an individual plan belongs to a class and to one of its
> halves.
> - They attend the lessons of their class and half, except lessons of the
>   subjects listed in their plan.
> - Those subjects, and any the class does not have (rewalidacja), they are
>   taught individually, in a tuition room.
> - Their own day — the class lessons they attend plus their individual
>   lessons — has no windows (S2), no two things at once (S1), and at most
>   eight lessons (S8).

Open points in it are §6.

---

## 5. Designs

### A. The pupil as a student set of their own (preferred)

FET is told the pupil exists, and its own rules do the rest.

- **Student structure.** Inside the pupil's half, a subgroup of its own (next
  to the half's ordinary subgroup), one per pupil.
- **Class lessons the pupil attends** stay addressed to the class or the half,
  which now include the pupil.
- **Class lessons of a subject in the pupil's plan** are addressed to the
  class's other subgroups by name, so the pupil is free then.
- **Individual lessons** are addressed to the pupil's subgroup, with their
  teacher and a tuition room, as now.
- **No windows** come from the no-gaps rule FET already applies to every
  subgroup. FET is then free to put an individual maths lesson where the
  class's maths is (when the teachers differ), or at the start or end of the
  pupil's day (when they do not, and for rewalidacja).
- **Day lengths** (P2) and the daily cap (S8) already work per subgroup, so a
  pupil whose load differs from the class gets their own range.
- **Checker.** `PlanCheck` gains the pupil as a group of its own: class
  lessons of excluded subjects do not occupy them; individual lessons do. R1,
  R9, R12 and R17 then hold for the pupil with no new rule.
- **Data and UI.** A half (1 or 2) on each individual plan, editable on the
  Nauczanie indywidualne page. Import leaves it empty.

**For:** exact; books rooms; handles rewalidacja, gaps and double bookings;
uses FET as it is meant to be used.

**Against:**
- It is a harder search: 23 more subgroups with no-gaps, and the cases in §2
  where the class's hours exceed the pupil's or the teacher is the same leave
  FET few places to put things. How much harder is the first thing to measure.
- FET once left windows in subgroups it had been told to keep window-free
  (RULES G2, on 6.8.5). Since RULES 1.4 the checker would reject such a week
  rather than save it, but it would have to be re-tested on 7.12.1.

### B. The planner's workaround (fallback)

On every class lesson of a subject in a pupil's plan, add the individual
teacher as a second teacher, as the support teacher was.

**For:** proven in practice; the pupil never has a gap from it; little to
build.

**Against:**
- It books the individual teacher for all the class's hours of the subject,
  not the pupil's (70 lessons have fewer).
- It cannot work where the individual teacher is the class's own teacher of
  that subject (49 lessons).
- It does not place rewalidacja or other subjects the class does not have.
- It books no room. FET's virtual rooms could book the classroom and a tuition
  room together; not tried.

### C. Tie each individual lesson to a class lesson

A `same starting time` link from each individual lesson to a lesson of the
class in the same subject, with the pupil left out of FET as now.

**For:** simple, and books rooms.

**Against:** the same two limits as B (hours, same teacher). Rewalidacja is
still unplaced. There is still no window rule for the pupil.

**Recommendation: A**, measured first with the pupils' halves filled in. If
the search becomes too slow, use B for the subjects where it fits (a different
teacher, equal hours) and A for the rest, before falling back to B
everywhere.

---

## 6. Open questions

1. **Fewer individual hours than the class has** (70 of 95 lessons). Take
   maths: the class has 4 hours, the pupil 2 individually. What does the pupil
   do in the class's other 2 maths hours?
   - a. Free, so those have to be at the start or end of the pupil's day.
   - b. In the class lesson with everyone else.
   - c. With a support teacher, as in the workaround. Then the arkusz's 2 is
     not the number of hours they spend on it.
2. **The same teacher for the class and the pupil** (49 lessons). Is the
   individual lesson then a separate hour, at the start or end of the pupil's
   day? Or is it given inside the class lesson, so that it is not a separate
   slot at all?
3. **Subjects the class does not have** (rewalidacja, doradztwo, German,
   TUS): at the start or end of the pupil's day, or also during a class lesson
   the pupil is excused from?
4. **PE**: 3 pupils have it individually, and the class has PE in cross-class
   groups. Is the pupil out of those groups for those hours?
5. **The pupil's half not yet entered:** block generation for that class, or
   assume one?
6. **Religia and etyka** for these pupils: as for the rest of their class (a
   later step, with attendance)?

---

## 7. Steps, once the questions are answered

1. RULES.md gets S9, worded from §4 and the answers.
2. A half on each individual plan: migration, API, the Nauczanie indywidualne
   page. The planner fills it in.
3. Checker first: `PlanCheck` learns the pupil (§5, A). Run it on the saved
   week to see how far today's plans are from the rule. They are expected to
   fail.
4. Exporter: the pupil's subgroup and the addressing in §5, A. A run per step
   in RUNLOG; the first measures the search time against R12.
5. If too slow, the hybrid with B.
