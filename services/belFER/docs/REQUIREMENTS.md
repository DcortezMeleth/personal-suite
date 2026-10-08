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

### 1.4 The instance is assumed solvable

38 weekly hours into 40 available slots, with no class gaps and every day
starting at slot 1, leaves very little slack — a 38-hour class is essentially
forced into a shape like 8/8/8/8/6.

Even so, **we assume a valid plan exists.** The school produces one every year,
so the constraint set is satisfiable in practice.

The consequence is a reframing, not a relaxation: **an infeasible run means the
input data is wrong, not that the school is impossible to schedule.** That is
exactly why detailed infeasibility reporting and strong data-entry validation
are MVP features rather than polish — they are the diagnostics for a data-entry
mistake somewhere in ~500 assignment rows.

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
- No odd/even week cycle. **"Every week is identical" was recorded earlier
  and is wrong** — assignments carry validity windows; see §4.1.
- No explicit school-year entity.
- The plan is built **twice a year**. **The hour allocation changes between
  semesters**, and new subjects — and therefore new teachers — may appear.
- Mid-year changes (a teacher is replaced; final-year classes leave early after
  matura) are handled by **patching the existing plan**, not re-planning from
  scratch. This is post-MVP; see §11.

### 4.1 Validity windows

Every assignment in the arkusz carries `TydzienPocz`/`TydzienKon` — the school
weeks over which it applies. Only 525 of 879 run the full year. **belFER
stores these windows**; they are not flattened away at import.

Partitioning the year at every window boundary gives **12 segments** in which
the set of active assignments is constant:

| Weeks | Length | Active assignments |
|---|---|---|
| 1–4 | 4 wk | 751 |
| 5–11 | 7 wk | 753 |
| 12 | 1 wk | 760 |
| 13–18 | 6 wk | 753 |
| 19 | 1 wk | 842 |
| 20 | 1 wk | 756 |
| 21–29 | 9 wk | 752 |
| 30 | 1 wk | 766 |
| 31–35 | 5 wk | 611 |
| 36 / 37 / 38 | 1 wk each | 616 / 611 / 533 |

Five segments cover **31 of the 38 weeks**, and differ from one another by only
a handful of assignments (751 / 753 / 753 / 752 / 611). The other seven are
single-week blips.

**Resolved with the school: a plan covers one stretch of weeks, and when the
allocation changes the school issues a new plan.** History drops from two hours
a week to one at week 12, and from week 13 the classes get a fresh timetable —
the expiring hour is not simply dropped, because doing so would leave the class
with a gap unless it happened to be the last lesson of its day.

So the importer detects the stretches over which the allocation is constant and
offers them, rather than asking for two numbers. In the real arkusz they are:

| Weeks | Length | Allocations | h/week |
|---|---|---|---|
| 1–4 | 4 wk | 622 | 1110 |
| 5–11 | 7 wk | 624 | 1114 |
| 13–18 | 6 wk | 624 | 1107 |
| 20–29 | 10 wk | 624 | 1104 |
| 31–35 | 5 wk | 499 | 846 |

The last is the drop after the final-year classes leave. Single-week boundaries
(12, 19, 30, 36–38) are not offered: the school handles those by hand rather
than reprinting a timetable.

**Hours from different windows are never added together.** An allocation stated
for two stretches is two variants in time, not two parts of a sum. The design
follows from that:

- **The data layer keeps every window.** Nothing is lost on import, and the
  post-MVP mid-year patching work depends on knowing them.
- **The solver works on one chosen interval at a time.** Constraints are
  evaluated across the assignments active in that interval; they are not
  individually time-aware. A new interval means a new plan, which is what the
  school already does. Making every pairwise constraint conditional on
  overlapping weeks would multiply the solver's cost and, more to the point,
  would yield a timetable that changes every few weeks — which cannot be
  printed and hung in a corridor.
- **The segmentation is surfaced in the UI**, so the planner can see where a
  plan would have to change and pick which interval to solve.

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

## 7. Cross-class lessons (zajęcia międzyoddziałowe)

The hardest part of the model and the clearest place where belFER can beat the
alternatives.

**The mechanism is not specific to PE**, which is what it was originally called
here. Teaching a group drawn from several classes at once also carries religia
and etyka — the real arkusz routes 19 WF, 3 Etyka and 1 Religia through exactly
this shape (§16.2d). PE is the most demanding case and the examples below use
it, but the model names no subject of its own; each unit carries one.

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
CrossClassGroup { label, teacher, classes: Set[Class] }   // e.g. {1A} or {1A, 1B}
CrossClassUnit  { subject, groups, blocks: [1, 2] }       // classes = union of group classes
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

Every constraint in §9.1 and §9.2 is evaluated **within the interval being
solved** (§4.1). Two assignments whose validity windows do not overlap are
never compared.

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
- **The whole solver module is written in Java**, not just the planning
  domain. Timefold's planning entities are annotation- and JavaBean-driven,
  and its ConstraintStreams API is heavily generic — both are unpleasant from
  Scala 3. Keeping the entire engine in Java avoids `@BeanProperty`,
  annotation targeting, SAM conversion and generic-inference friction
  altogether.
- **No Timefold type ever crosses into Scala.** The boundary is one plain Java
  interface over plain Java DTOs:

  ```java
  public interface TimetableSolver {
      SolverOutput solve(SolverInput input, SolverSettings settings,
                         ProgressListener listener);
  }
  ```

  Scala maps its domain to `SolverInput`, calls `solve`, and maps
  `SolverOutput` back. Planning entities, the `ConstraintProvider`,
  `SolverConfig` and `SolverManager` all stay inside the Java module.

  This also contains the risk from §10: if Timefold is ever replaced, only the
  Java module changes, and the Scala side is unaffected.
- Layout: `src/main/java/` as a `java_library`, `src/main/scala/` as a
  `scala_library` depending on it. Java 17 is already the repo toolchain
  (`.bazelrc`), so records and sealed interfaces are available for the DTOs.
  `rules_java` is not yet a `bazel_dep` in `MODULE.bazel` and may need adding.
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
  design system. The solver module is **Java 17** (see §10.1).
- **Test data** lives in `services/belFER/testdata/`, exposed as the
  `//services/belFER/testdata:testdata` filegroup and consumed through a test
  target's `resources` — the same mechanism `//db:migrations` uses. Its
  `private/` subdirectory is gitignored and excluded from the filegroup, for
  real non-anonymised school data. See that directory's `README.md`.
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

---

## 16. What the real arkusz revealed

The first real `planOrg` export (anonymised, in
`services/belFER/testdata/arkusz/full-semester.xml`) contains 75 teachers, 46
`<klasa>` records, 34 subjects, 879 assignments and 23 cross-class lessons. It
**contradicts several things recorded above**. Nothing in §1–§15 has been
edited yet; the conflicts are listed here so they can be resolved with the
school first.

### 16.1 Format outline

| Element | Carries |
|---|---|
| `<placowka>` | school `kod`, `nazwa`, `regon` |
| `<nauczyciel>` | `Nazwa`, `Imie`, `Pensum`, `Znizka`, `Kod` (the join key) |
| `<klasa>` | `Kod`, `Poziom`, `Name`/`Nazwa` (specialisation), `LiczbaUczniow`, `LiczbaDziewczyn`, `WychowawcaRef` |
| `<zajecie>` | a cross-class lesson: `Kod`, `Nazwa` |
| `<przedmiot>` | `Kod`, `Nazwa`, `Pensum` |
| `<przydzial>` | the assignment: `KlasaRef` **or** `ZajecieRef`, `PrzedmiotRef`, `NauczycielRef`, `LiczbaGodzin`, `Grupa`, `GrPodzial`, `GrNazwa`, `TydzienPocz`, `TydzienKon` |

`WychowawcaRef` confirms §6: the wychowawca is already in the data.
`LiczbaDziewczyn` (girls per class) is present, which is relevant to PE
grouping even though §7 says student-level data is not needed.

### 16.2 Conflicts to resolve

**(a) Class list — conflicts with §3's "same number of classes per year, A…X".**
Letters are not contiguous (there is no 1B) and the years are uneven. Of the 46
`<klasa>` records only about 25 look like real classes; the rest have codes of
the form `<year><letter><two more letters>`, which look like **extension or
option groups modelled as pseudo-classes**. If so, §6's "an
extended subject is just more hours within the class" is wrong, and extension
groups are a first-class entity that may cross classes.

**(b) Week ranges — conflicts with §4's "every week is identical".**
Every assignment carries `TydzienPocz`/`TydzienKon`. Only 525 of 879 span the
full 1–38. The common ranges — 1–19, 19–37, 1–30, 30–30, 37–38, 1–12, 12–19 —
suggest the file is a **whole-year document with per-assignment validity
windows**, with 1–19 and 19–37 as the two semesters. "Every week is identical"
may still hold *within* a semester, which is what gets scheduled, but **import
must filter to one semester** rather than taking the file whole. This also
explains why naively summing hours gives a class 42 — above the 40-slot
capacity — because it counts both semesters at once.

**(c) Group splits — conflicts with §6's "max 2 groups, always the same split".**
`GrNazwa` takes three distinct values, i.e. a class is split along **at least
three independent dimensions**:

| `GrNazwa` | `GrPodzial` values | Assignments |
|---|---|---|
| `grupy` | `1 grupa` … **`4 grupa`** | 373 |
| `religia / etyka` | `religia`, `etyka` | 41 |
| `WF` | `DZIEWCZĘTA`, `CHŁOPCY`, `Dziew-1`, `Dziew-2`, `Chłop-1`, `Chłop-2` | 65 |

There are 12 assignments at `4 grupa` and one at `3 grupa`, so **up to four
groups exist**, not two. The "always the same split" rule may apply only within
the `grupy` dimension.

**(d) Religia and etyka are cross-class — conflicts with §6.**
`<zajeciaMiedzyOddzialowe>` holds 19 Wychowanie fizyczne, **3 Etyka and 1
Religia**. §6 records that religia is not merged across classes; the data says
otherwise.

**(e) Cross-class assignments carry no `KlasaRef`.**
23 `<przydzial>` rows reference a `ZajecieRef` instead, so the importer must
handle both shapes, and a cross-class lesson's member classes are **not stated
directly** — they appear to be encoded in the `<zajecie>` `Kod` (`4DE`, `2CD`,
`3AF`, `DH3`). That encoding needs confirming rather than guessing.

**(f) Extension subjects have their own codes — confirms §1.4.**
Nine subjects are coded `r_*` (`r_matematyka`, `r_angielski`, …) alongside
their base subject, exactly the split the school describes as artificial. The
lesson-line model in §6.1 merges them; the importer must do that mapping.

**(g) Minor.** Some assignments have an empty `NauczycielRef` (not yet
allocated). `Pensum` appears on both `<nauczyciel>` and `<przedmiot>`, and
teacher pensum varies from 18 to 30.

### 16.3 Answers from the school

**(a) Resolved — pseudo-classes are individual teaching.**
A code of the form `<year><letter><two more letters>` is not a class. It is the
plan for **one student** in that class, identified by their initials, receiving
*nauczanie indywidualne* / *rewalidacja* / *zajęcia wyrównawcze*. There were 21
such records, all with `LiczbaUczniow="0"` and no
wychowawca. **The MVP ignores them**; support comes later. Missing class
letters (no 1B) and uneven year sizes are simply normal — §3's "same number of
classes per year, A…X" is wrong and should be dropped.

> ⚠ **These records are the reason the fixture needed a second anonymisation
> pass.** Their `Name` carried students' full names next to their provision —
> named minors plus health data. See `testdata/README.md`.

**(b) Acknowledged, not yet decided — week ranges.**
Confirmed as a real complication. Still open: does import always target a
single semester, or does belFER model validity windows? **This is the one
remaining blocking question**, because it decides whether a plan is scoped to
a semester or carries per-assignment date ranges.

**(c) Resolved — two groups, and the data agrees.**
The same two-group split applies to everything *except* `zajęcia
międzyoddziałowe`. The `grupy` rows at 3 and 4 groups turned out to belong
**only to the individual-teaching pseudo-classes**.
Once those are ignored, `grupy` is exactly two groups across all real classes,
exactly as §6 records. The `WF` and `religia / etyka` dimensions belong to
cross-class lessons, not to the in-class split.

**(d) Resolved — §6 was wrong.**
Religia and etyka *are* merged across classes. The earlier statement that only
PE is merged was a mistake. Cross-class lessons are 19 WF, 3 Etyka, 1 Religia.

**(e) Resolved — `<zajecie>` codes and the three row shapes.**
`4DE` means classes **4D + 4E**. Ordering is not significant: `D4E` and `DE4`
occur too, so the code must be parsed by extracting the digit and the letters
rather than by position.

The `<przydzial>` rows come in exactly three shapes, which together give a
clean import model:

| `KlasaRef` | `ZajecieRef` | `NauczycielRef` | Count | Meaning |
|---|---|---|---|---|
| ✓ | — | ✓ | 800 | an ordinary in-class lesson |
| ✓ | ✓ | — | 56 | **this class contributes students** to cross-class lesson Z |
| — | ✓ | ✓ | 23 | **the cross-class group itself**, and who teaches it |

Participation rows deliberately carry **no teacher**: assigning one there
would count that teacher's hours twice. The planner assigns teachers to these
groups by hand afterwards, in whatever tool she loads the data into — which is
work belFER should absorb.

There are 23 `<zajecie>` elements and 23 definition rows, so **one `<zajecie>`
is one teaching group**, with `LiczbaUczniow` giving its size.

### 16.4 Resolved — (b) validity windows

**belFER models the windows**, storing them per assignment rather than
flattening them at import. The solver still works on one interval at a time
rather than making each constraint time-aware; §4.1 carries the reasoning and
the segment data behind it.

Nothing in §16.2 is still blocking.

### 16.5 How the arkusz records a support teacher — conflicts with §8

Before the fixture was reduced (see §16.6) the export contained three
`NAUCZ.WSPOM.` assignments — *obowiązki nauczyciela wspomagającego*. They were
recorded as **a subject assignment against an individual-teaching record**, one
teacher at 10 h/week across weeks 1–38, covering three students in different
classes.

§8 assumes a support teacher attaches to a **(class, subject)** pair. The
arkusz does something different: it books the teacher's hours against the
student's individual plan, with no subject or class of its own.

Both facts can be true — the principal allocates the hours one way, the
timetable has to place them another — but the importer cannot derive §8's model
from this data without being told which lessons the support teacher actually
attends. **Open question for the school:** given 10 h/week of support, which
(class, subject) pairs is the teacher present for?

**This is open but not blocking.** Until it is answered:

- **§8 stands as the scheduling model.** A support teacher is a second teacher
  on a (class, subject) pair, booked like any other teacher. Phase 1.5 builds
  that, and the solver constraints in §9.1 already cover it.
- **Only the import mapping is deferred.** `NAUCZ.WSPOM.` rows are recognised
  and reported rather than silently dropped, and the planner assigns the pairs
  by hand — which is what happens today anyway. Phase 2 revisits it once the
  answer arrives.

So the question gates a convenience in the importer, not the data model and
not the solver.

### 16.6 The committed fixture is reduced

`testdata/arkusz/full-semester.xml` is not the whole export. Anonymising
identities does not anonymise the data: a full copy shows how many students in
each class receive special-needs provision, which on a public repository is a
re-identification risk for a child if anyone works out which school it is.

The committed fixture therefore keeps **three individual-teaching records
instead of twenty-one**, chosen so that none carries any provision detail, and
the three special-needs subject codes are dropped along with them. Class rolls
are nudged by a few students so the published numbers are not the school's real
roll. Everything else — teachers, classes, subjects, hour allocations, group
splits, cross-class lessons, validity windows — is untouched real data.

Counts after reduction: 28 classes (25 real + 3 individual), 75 teachers, 31
subjects, 797 assignments, 23 cross-class lessons.

---

## 17. What the import revealed

Mapping the real arkusz (weeks 1–19) produces **25 classes, 75 teachers, 22
subjects, 668 lesson lines and 17 cross-class units covering 49 groups**, with
nothing left unmappable. The model holds. Five things were learned doing it.

**`Grupa` under the WF scheme is a school-wide index, not a count.** A row
reading `Grupa="5"` is group number five in the school's WF numbering
(1 = Dziew-1, 3 = Chłop-1, 5 = DZIEWCZĘTA, 6 = CHŁOPCY), not a class split into
five. Read as a count it looks like classes needing six halves; read correctly
most classes have one or two WF rows.

**WF must not map onto the class's two halves.** It is grouped independently of
the `grupy` split — §6 said so and the data agrees — so putting it in
GROUP_1/GROUP_2 would tell the solver that two unrelated halves are the same
students. Any grouping scheme other than the class's own becomes a unit.

**A class's own WF half and the half it merges away are one arrangement.** Of
25 classes, 21 teach one half themselves and send the other to a neighbouring
class; 3 teach both halves; 1 merges everything. Scheduling the in-class half
apart from the merged one would leave half the class idle while the other half
had a lesson, so units are the connected components of "shares a class with",
computed per subject. That is what takes 38 naive units down to 17 real ones.

**Extension subject codes do not always strip.** Seven of nine `r_*` codes have
their base by removing the prefix, but `r_angielski`'s base is `j.angielski`
and `r_informat.`'s is `informatyka`. Matching falls back to the subject name
with the *rozszerzony* adjective removed.

**The arkusz states hours, never block shapes.** It says four hours of maths,
not `1,1,2`. Imported hours therefore become single lessons, and the double
blocks have to be set by hand afterwards — a real limitation of the source, not
of the importer.

Also skipped, by design: 3 individual-teaching plans, and 25 allocations whose
week range falls outside the imported semester.
