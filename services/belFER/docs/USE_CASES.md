# belFER — use cases and test cases

The basis for automated tests. Requirements live in `REQUIREMENTS.md`;
sequencing lives in `ROADMAP.md`.

Three kinds of entry:

| Prefix | Meaning |
|---|---|
| `UC-` | A user-facing use case. Becomes an API/integration test, and a manual acceptance check. |
| `VAL-` | A data-entry validation rule. Becomes a unit test on the validation engine. |
| `CT-` | A solver constraint test — a minimal fixture the solver must reject, or penalise for soft constraints. |

`CT-` cases are the most valuable: they are small, deterministic, and they
pin down the rules that decide whether a generated plan needs manual fixing.

---

## Configuration

**UC-01 — Configure the school**
Set name, number of years, classes per year, and class letters.
*Then* the class list can be generated from those values.

**UC-02 — Configure the bell schedule**
Define 8 slots with start and end times.
*Then* slot times appear in plan views; changing a time never invalidates an
existing plan, since times do not affect scheduling.

**UC-03 — Configure scheduling settings**
Toggle "allow class gaps"; set max lessons per day and max consecutive
teacher gaps.
*Then* the solver honours the changed setting on the next run.

**UC-04 — Configure constraint weights**
Adjust the weight of each soft constraint.
*Then* two runs with different weights produce measurably different score
breakdowns.

---

## Reference data

**UC-05 — Manage subjects**
Create, edit and delete subjects; flag a subject as optional (first-or-last);
set its required room kind and whether that requirement may be waived.

**UC-06 — Manage room kinds**
Create and edit room kinds (ordinary, gym, chemistry lab, computer room, …).

**UC-07 — Manage rooms**
Create a room with number, name, one or more kinds, and the fits-whole-class
flag. The sports hall is entered as 3 PE-capable rooms.

**UC-08 — Manage teachers**
Create a teacher with name, the subjects they teach, and an optional home
room. Two teachers may share a home room.

**UC-09 — Set teacher unavailability**
Add a weekly blocked range, e.g. "Tuesday, slots 1–4".
*Then* the solver never places that teacher there.

**UC-10 — Set max working days**
Default 5; override for a part-time teacher.

**UC-11 — Set per-day lesson cap**
Default 8; override per teacher.

**UC-12 — Generate the class list**
From "4 years × 7 classes", generate 1A…4G.
*Then* individual classes can be renamed, added or removed.

**UC-13 — Assign a wychowawca**
*Then* godzina wychowawcza for that class must be taught by that teacher.

**UC-14 — Define a specialisation template**
Define "mat-fiz" once as a set of subjects with hours and block patterns.

**UC-15 — Apply a template to a class**
Stamp the template onto a class, then adjust individual subjects.
*Then* later edits to the template do not silently alter classes already
stamped.

---

## Teaching assignments

**UC-16 — Add a lesson line**
Class + subject + audience + teacher + blocks, e.g. `1A / matematyka /
whole class / Kowalska / [1,1,2]`.

**UC-17 — Add extension hours**
A class already has 5 base hours of maths; add 2 extension hours.
*Then* it remains **one subject** with 7 hours, not two subjects — and the
extension may carry a different teacher.

**UC-18 — Split a subject**
Add Group 1 and Group 2 lines for angielski with different teachers.

**UC-19 — Mix whole-class and split lines for one subject**
Biologia rozszerzona: 2 whole-class hours plus 2 split hours that need a lab
too small for the whole class.
*Then* both forms coexist on the same subject.

**UC-20 — Attach a support teacher**
Attach a nauczyciel wspomagający to (1A, matematyka).
*Then* that teacher is booked for every hour of that pair and cannot teach
elsewhere during them.

**UC-21 — Define a fully merged PE unit**
Classes 1A, 1B and 1C share PE, split into 6 groups.

**UC-22 — Define a partially merged PE unit**
1A's boys have PE alone; 1A's girls merge with 1B's girls; 1B's boys have PE
alone.
*Then* 1A and 1B are in the same unit and must have PE in the same slot.

**UC-23 — Live counters during entry**
*Then* the UI shows each teacher's running total and each class's hours
assigned vs. required, updating as lines are added.

---

## Import

**UC-24 — Import the principal's XML**
Upload the file and preview what will be created, updated and skipped before
anything is written.

**UC-25 — Resolve unmapped entities**
The XML names a subject or teacher belFER does not know.
*Then* the user maps it to an existing record or creates a new one; the
import does not fail silently.

**UC-26 — Re-import an updated arkusz**
*Then* a diff against current data is shown, and the user chooses what to
apply.

**UC-27 — Edit imported data**
*Then* imported records behave exactly like hand-entered ones.

---

## Validation

**VAL-01** A class whose total hours exceed the available slots is flagged.
**VAL-02** A teacher whose assigned hours exceed their availability is flagged.
**VAL-03** A subject whose blocks cannot fit into 5 days, given one block per
day, is flagged.
**VAL-04** A PE unit whose member classes have mismatched PE hours is flagged.
**VAL-05** A split subject whose two groups have different hour counts is
flagged.
**VAL-06** A teacher assigned a subject they are not recorded as teaching is
flagged.
**VAL-07** A class with no wychowawca, or whose godzina wychowawcza is
assigned to someone else, is flagged.

> Validation reports problems; it does not block saving. Half-entered data is
> normal during a long entry session.

---

## Generation

**UC-28 — Start a run**
Choose a timeout and start.
*Then* the run is asynchronous and the UI stays usable.

**UC-29 — Watch progress**
*Then* the current best score and elapsed time are visible.

**UC-30 — Stop a run early**
*Then* the best plan found so far is kept.

**UC-31 — A run completes**
*Then* the plan is saved with a score breakdown per constraint.

**UC-32 — A run cannot find a feasible plan**
*Then* the user sees **which lessons could not be placed and which constraints
were violated**, in enough detail to fix the input data. A bare "no solution"
is a failed implementation of this use case.

**UC-33 — Compare two runs**
*Then* score breakdowns are shown side by side.

**UC-34 — Mark a plan as current**
*Then* one plan is distinguished from experiments; history is retained.

---

## Viewing

**UC-35** View the plan for one class.
**UC-36** View the plan for one teacher.
**UC-37** View the plan for one room.
**UC-38** View the whole-school grid.
**UC-39** Split lessons render as two half-rows in the class view, showing
both groups, their subjects and their teachers.
**UC-40** PE renders with its groups and partner classes, so it is clear
where each half of a class is.

---

## Manual editing (safety net)

**UC-41 — Move a lesson**
*Then* validation runs live and shows what the move breaks.
**UC-42 — Swap two lessons.**
**UC-43 — An edit breaking a hard constraint is rejected; a soft-constraint
edit warns but is allowed.**

---

## Post-MVP

**UC-44** Export PDF per class, teacher, room and whole school.
**UC-45** Lock a subset of lessons and re-solve the rest, minimising
disruption.
**UC-46** Export in a form importable into Librus.
**UC-47** Roll over to the next school year, promoting classes.
**UC-48** Read-only access for a co-planner or the principal.

---

## Constraint test cases

Each is a minimal fixture. Hard cases must be **rejected** (scored as a hard
violation); soft cases must be **penalised but accepted**.

| ID | Fixture | Expected |
|---|---|---|
| **CT-01** | One teacher, two classes, same slot | reject |
| **CT-02** | One class, two lessons, same slot | reject |
| **CT-03** | One room, two lessons, same slot | reject |
| **CT-04** | Lesson inside a teacher's unavailable range | reject |
| **CT-05** | Teacher with max 3 days scheduled across 4 | reject |
| **CT-06** | Teacher with a cap of 6 scheduled for 7 in a day | reject |
| **CT-07** | A configured block unplaced, or placed twice | reject |
| **CT-08** | Two blocks of one subject for **one audience** on one day | reject |
| **CT-09** | A 2-hour block across non-consecutive slots, or across two days | reject |
| **CT-10** | Only one group of a split class busy in a slot | reject |
| **CT-11** | PE-merged classes scheduled in different slots | reject |
| **CT-12** | A class whose day does not start at slot 1 | reject |
| **CT-13** | A class with a mid-day gap | reject — **and accept** the same fixture when the school setting allows gaps |
| **CT-14** | A class with 9 lessons in one day | reject |
| **CT-15** | An optional subject placed mid-day | reject |
| **CT-16** | A teacher with 3 consecutive free periods | reject |
| **CT-17** | A support teacher booked against their own lesson | reject |
| **CT-18** | A lesson outside the teacher's home room | penalise |
| **CT-19** | A specialist subject held in an ordinary room | penalise |
| **CT-20** | A class whose longest and shortest days differ by more than 3 | penalise |
| **CT-21** | **The language-swap scenario** — 1A Gr.1 angielski / Gr.2 niemiecki at slot 1, mirrored at slot 2, same day | **accept** |

> **CT-21 is the key regression test for CT-08.** The class sees *angielski*
> twice on the same day, which is legal because the one-block-per-day rule
> applies per group, not per class. An implementation that rejects CT-21 has
> mis-read the rule and will force exactly the manual edits this project
> exists to eliminate.
