# belFER — requirements

An automatic lesson-plan (*plan lekcji*) generator for a Polish liceum, built
as a service in the `personal_suite` monorepo alongside delFIN.

This document is the record of what was gathered during requirements
discussions. `USE_CASES.md` turns it into testable scenarios; `ROADMAP.md`
sequences the work.

---

## 1. Context

The school currently uses **Vulcan**. It generates a plan automatically, but
the result needs so much manual rework that the generation barely helps.

### 1.1 Why belFER exists — what Vulcan does badly

1. **It cannot handle subjects with group splits.** This is the headline
   failure and the main reason for the project.
2. There is **no easy way to block slots for a teacher** — e.g. a teacher who
   also teaches at another school on fixed hours.
3. There is **no support for a nauczyciel wspomagający** (support/co-teacher).
4. The principal's *arkusz* lists e.g. *matematyka podstawowa* and *matematyka
   rozszerzona* as two separate subjects with separate hours, when in reality
   they are **one subject with base + extension hours**. Vulcan cannot express
   that.
5. There is **no way to choose the optimisation key**, and no way to add
   constraints (e.g. a maximum gap between a teacher's lessons).
6. There is no easy export.

Other planners on the market either **do not support PE at all**, or fake it
with cross-class lessons and several teachers attached to one lesson.

**No compatibility with Vulcan is required.**

### 1.2 Goal

A correctly configured generation run should need **zero manual edits**.

### 1.3 Principal risk

"Zero manual edits" holds only if the constraint model is **complete**. Every
rule the planner applies from memory that belFER fails to capture becomes a
manual edit — which is precisely Vulcan's failure mode.

**Therefore constraints must be pluggable, individually weighted, and
toggleable from the UI, not hard-coded.** This is the part of the system worth
over-investing in.

### 1.4 Secondary risk — feasibility, not optimality

38 weekly hours into 40 available slots, with no class gaps and every day
starting at slot 1, leaves almost no slack. A 38-hour class is essentially
forced into a shape like 8/8/8/8/6. Combined with ~15 teachers of restricted
availability and rooms being a contested resource, **the first real runs are
likely to come back infeasible.**

This is why detailed infeasibility reporting and strong data-entry validation
are MVP features rather than polish.

---

## 2. Instance size

| | |
|---|---|
| Years | 4 |
| Classes per year | 6–8 (≈ 24–32 classes) |
| Teachers | ≈ 80 |
| …with restricted availability | ≈ 15 |
| …working fewer than 5 days a week | ≈ 15 |
| Slots per day | 8 (Mon–Fri ⇒ 40 slots per week) |
| Weekly hours **per student** | 27 minimum, 38 maximum (estimates) |
| PE-capable rooms | ≈ 6 (3 sports-hall sections + extras), configurable |

---

## 3. Scope, users, deployment

- Built for **one school**, but the data model is **multi-tenant-capable** so
  that supporting another school later is configuration, not a rewrite.
- **Authentication**: a single user who can see and do everything. Groundwork
  for simple permissions is welcome. Read-only access for a co-planning
  teacher or the principal is post-MVP.
- Runs on the **home lab / LAN**, like delFIN.
- **Desktop only.**
- **Polish UI.**

---

## 4. Time model

- **Monday–Friday.**
- **8 lesson slots every day**, the same on every day. No "lesson 0". No
  shifts.
- Break lengths vary, but **breaks do not affect planning**. Slot start/end
  times exist only so the plan can be printed.
- **A double block may span the long break.**
- **Every week is identical** — no odd/even week cycle.
- No explicit school-year entity.
- The plan is built **twice a year**. **The hour allocation changes between
  semesters**, and new subjects — and therefore new teachers — may appear.
- Mid-year changes (a teacher is replaced; final-year classes leave early after
  matura) are handled by **patching the existing plan**, not re-planning from
  scratch. This is post-MVP; see §11.

---

## 5. Rooms

Rooms are a genuine scheduling resource.

- **A teacher's assigned room means priority, not exclusivity.** Several
  teachers may use the same room, and not every teacher has one.
- ⇒ **The solver assigns the room for each lesson**, with the teacher's own
  room as a strongly weighted preference.
- **Some subjects need a specialist room.** Specialist rooms can run short, in
  which case an ordinary room must be usable. This is a **per-subject flag**:
  normally required, but overridable when the school knows demand exceeds
  supply.
- The **sports hall splits into 3 sections**, and there are additional PE
  rooms — roughly 6 PE-capable spaces in total. Rooms and their kinds are
  **configurable**, so this is data, not code.
- **Several PE teachers can teach at the same time.**
- Specialist rooms are sometimes too small for a whole class — which is *why*
  some lessons are split. Rooms therefore carry a **fits-whole-class** flag.

### Model

```
Room    { number, name, kinds: Set[RoomKind], fitsWholeClass: Bool }
Teacher { …, homeRoom: Option[Room] }
Lesson  { …, room: assigned by the solver }
```

---

## 6. Subjects, hours and splits

- The subject list is **configured per school**.
- Weekly hours are defined **per class**, because classes differ by
  specialisation (mat-fiz, biol-chem, …), which changes the hour counts.
- **No fractional hours.**
- **Blocks are explicit.** For each class+subject the user lists block sizes:
  4 hours of maths entered as `1, 1, 2` means two single lessons and one
  double.
- **Base + extension hours.** A class has a standard number of hours for a
  subject, plus optional extra hours when the class is extended — e.g. "5
  standard + 2 extension". It is **one subject, not two**. Teachers often
  specialise in extended or standard classes, but this is **not a rule**, so
  base and extension hours may have the **same or different teachers**.
- **A single subject can have whole-class and split lessons simultaneously** —
  for example an extended subject needing a specialist room too small to hold
  a whole class. ⇒ **the split is a property of the lesson line, not of the
  subject.**
- A class is **always split into the same two groups** across all subjects —
  **except PE**, which is grouped entirely independently (see §7).
- **Both halves must always be busy at the same time.** One group must never
  sit idle while the other has a lesson.
- **The two groups may be doing different subjects in a paired slot.** Worked
  example taken from the current plan:

  | | Mon 8:00–8:45 | Mon 8:50–9:35 |
  |---|---|---|
  | **1A Gr. 1** | angielski | niemiecki |
  | **1A Gr. 2** | niemiecki | angielski |

  The swap must happen **within the same day**, and no whole-class lesson of
  the same subject may fall between the two halves of the exchange.

  > ⚠ **Assumption awaiting confirmation.** In the example above the class
  > sees *angielski* twice on Monday. So the rule "at most one block of a
  > subject per day" must apply **per group/audience, not per class** —
  > otherwise this perfectly normal arrangement would be illegal.

- **Optional subjects** (religia, etyka, possibly others) must be placed
  **first or last in the day**, so that students who do not attend can arrive
  late or leave early. This is a **per-subject flag**.
- **Godzina wychowawcza** is a normal 1-hour subject whose teacher must be the
  class's wychowawca. It has no placement rule of its own, and the wychowawca
  has no other scheduling significance.

### Model

```
LessonLine {
  class, subject,
  audience: WholeClass | Group1 | Group2,
  kind: Base | Extension,
  teacher, supportTeacher: Option[Teacher],
  blocks: [1, 1, 2]
}
```

Whole-class and split lines coexist for one subject. Base and extension become
extra lines on one subject rather than two subjects. The support teacher hangs
off the line. Group1 and Group2 lines are paired into shared slots.

---

## 7. PE

PE is the hardest part of the model and the clearest place where belFER can
beat the alternatives.

- Groups are **boys/girls**, formed **independently of classes**, and decided
  externally by the school.
- Classes are **sometimes merged, sometimes partially merged**. For example,
  if a class's boys' group is large enough it runs on its own, while the same
  class's girls join another class.
- A single class's students may therefore spread across several groups and
  several partner classes — some girls of 1A with 1B, others with 1C.
- **All classes linked by a merge must have PE in the same slot.** Otherwise a
  class would be partly absent during an ordinary lesson.
- The merge arrangement **can change between semesters**.
- The system must let the user **define these merges explicitly**.

> ✅ **Key simplification.** Student-level data is never needed. Knowing which
> classes participate and how many groups exist is sufficient to schedule
> correctly; who is in which group is decided outside the system and never
> affects the timetable.

### Model

```
PeGroup { label, teacher, classes: Set[Class] }   // e.g. {1A} or {1A, 1B}
PeUnit  { groups: [PeGroup], blocks: [1, 2] }     // unit classes = union of group classes
```

All groups of a unit run in the same slots; every participating class is
blocked from other lessons during them; each group takes its own PE-capable
room.

---

## 8. Teachers

- Stored: **name**, **subjects taught**, optional **home room**.
- **Unavailability blocks**: expressed as (day, slot range), repeating every
  week. No date-ranged availability.
- **Max working days per week**: per teacher, **default 5**, set only when
  different. About 15 teachers work fewer than 5 days.
- **Per-day lesson cap**: a general cap of **8**, with a per-teacher override.
- **No other teacher preferences.** Deliberately excluded — scheduling is hard
  enough without them.
- **Nauczyciel wspomagający (support teacher)**: a second teacher attached to
  a **(class, subject)** pair, e.g. to assist students with ADHD. Present for
  **all** hours of that pair. While supporting, they **cannot teach their own
  lessons** — they are booked like any other teacher.
- Teacher hour totals are tracked as a **data-entry sanity check**; hours are
  allocated elsewhere, before planning begins.
- **Substitutions (zastępstwa) are out of scope** — handled manually outside
  the app.

---

## 9. Constraints

### 9.1 Hard constraints

1. A class, group, or PE group is never double-booked.
2. A teacher is never double-booked — **including when acting as a support
   teacher**.
3. A room is never double-booked.
4. Teacher unavailability is respected.
5. A teacher's max-working-days is respected.
6. A teacher's per-day lesson cap is respected.
7. Every configured block is placed, exactly once.
8. At most one block of a subject per day **per group/audience** (see the
   caveat in §6).
9. A multi-hour block occupies consecutive slots on the same day.
10. Both groups of a split class are busy in the same slot.
11. All classes merged for PE have PE in the same slot.
12. A class's day starts at slot 1.
13. A class never has a gap — its day is contiguous. Exposed as a
    school-level setting so it can be relaxed.
14. A class has at most 8 lessons per day.
15. Optional subjects are first or last in the day.
16. A teacher never has more than 2 consecutive free periods.

### 9.2 Soft constraints, user-weighted

- Teacher gaps should be **single** where possible; 2 is tolerated but
  avoided.
- A class's **longest day minus shortest day should be at most 2–3 hours.**
- A lesson should use its **teacher's home room**.
- A lesson should use a room of its **required specialist kind**.
- *Post-MVP*: difficult subjects in the morning.

The weights are **editable by the user** — this is the "configurable
optimisation key" that Vulcan lacks.

---

## 10. Solver

- **Timefold Solver** is the starting point. Writing a well-optimised search
  algorithm by hand is not a task to take on up front; revisit only if
  Timefold proves insufficient.
- **Runtime budget is generous**: nothing useful is expected in under ~30
  minutes, and **a few hours is acceptable** if the result is good. The
  **timeout must be user-configurable.**
- Generation is **asynchronous**, running inside the backend for now and
  extractable into its own service later if scaling demands it. Several cores
  are available.
- **One plan per run** — no multi-candidate batches.
- **Failure reporting is a first-class feature.** On infeasibility, report in
  as much detail as possible what could not be placed and which constraints
  were violated.
- Input validation is wanted, but the goal is to catch most problems **at
  data-entry time** rather than at generation time.

### 10.1 Consequences of choosing Timefold

- Weighted hard/soft scoring maps directly onto the configurable-optimisation-
  key requirement: weights can live in Postgres and be edited in the UI rather
  than compiled in.
- Its termination configuration covers the user-settable timeout, and
  multi-threaded solving covers "use a few cores".
- **Scala 3 interop is the one wrinkle.** The planning domain is annotation-
  and JavaBean-driven. Write **only the planning domain in Java** — a
  `java_library` beside the `scala_library` — and keep everything else in
  Scala, rather than fighting `@BeanProperty` and annotation targeting.
- Prefer reflection-based domain access over runtime bytecode generation, as
  the latter is the riskier path under Bazel.
- `rules_jvm_external` requires every transitive dependency to be listed
  explicitly (see `TECH_STACK.md`); Timefold's dependency tree makes this a
  known chore.

---

## 11. Data entry and import

- The principal's assignment currently arrives as **XML**. **Import is in the
  MVP**, with editing in the app afterwards.
  > ⚠ **Blocked**: an anonymised sample file is still needed. The data-entry
  > layer cannot be finalised until the format is known.
- **Specialisation templates**: define a profile (e.g. "mat-fiz") once as a
  set of subjects + hours + block patterns, stamp it onto a class, then tweak.
  With roughly 500+ assignment rows this is the single biggest time-saver.
- The class list is **generated** from years × letters, then editable.
- Assignments are entered **per class**, with live counters for teacher hour
  totals and class hours assigned vs. required.

---

## 12. Output

- Views needed in the MVP: **per class, per teacher, per room, and the whole
  school**.
- **PDF is post-MVP.** The finished plan is printed and hung in a corridor.
  There is no fixed layout to match.
- **Librus export is post-MVP**, and depends on whether Librus supports
  import at all.

---

## 13. MVP boundary

**In scope:**
XML import → data editing → generation from scratch → on-screen views →
manual move/swap as a safety net.

**Out of scope for the MVP:**
PDF output, Librus export, mid-year patching with locking, year rollover,
difficult-subjects-in-the-morning, multi-user permissions, substitutions,
student-level data.

---

## 14. Platform constraints

- The service lives under `services/belFER/{backend,frontend}` — the pattern
  `TECH_STACK.md` already anticipates. Bazel auto-discovers new packages; only
  `MODULE.bazel` (new Maven artifacts) and `.bazelignore` need central edits.
  `pnpm-workspace.yaml` already globs `services/*/frontend`.
- **Its own Postgres schema** in the same instance as delFIN, with its own
  Flyway location. `db/BUILD.bazel` currently exposes one repo-global
  `filegroup`; belFER adds `db/belFER/migrations` plus a second filegroup,
  leaving delFIN's migrations untouched.
- Shared Scala code moves to a common module **only where genuinely worth
  sharing**. `Database`, `DoobieMeta` and config loading currently live
  private inside `fin.*`; extract only what belFER actually duplicates, once
  that is known in practice.
- Stack: Scala 3.3.7 / http4s Ember / doobie / Flyway on the backend,
  Vite + React 18 + Tailwind on the frontend, with the shared `libs/ui`
  design system.
- **Ports 3000 and 8080 are taken by delFIN** — belFER picks new ones.
- Repo-wide rules from `TECH_STACK.md` apply: every transitive dependency
  listed explicitly, no inline macros, cats-effect stays at 3.5.7 and
  cats-core at 2.9.0.

---

## 15. Still open

- ⭐ **An anonymised sample of the principal's XML.** Blocks Phase 2, which is
  MVP scope.
- Confirm the **per-group** reading of "one block of a subject per day" (§6).
- Room inventory by kind — entered as configuration, so not blocking.
- Which optional subjects exist beyond religia and etyka.
