# 0002 — Soft constraints as weighted penalties, never as extremal quantities

**Status:** Accepted (2026-10-11)

## Context

UC-04 lets the planner configure the optimisation key, and the soft constraints
in REQUIREMENTS §2.10 are written in the language a person naturally uses.
CT-20 is the clearest case: *"a class's longest minus shortest day ≤ 2–3
hours"*. Written into the model exactly as stated, that is minimise
`max(days) − min(days)` per class.

Measuring the two phrasings of that same preference, on the same model with the
same rules and the same budget, produced a larger difference than any
constraint in the problem:

| 25 classes, 30 min | result |
|---|---|
| no objective at all | **nothing** |
| `max − min` per class | a valid timetable, 72 away from balanced |
| every hour past target costs one unit | **proven optimal, nothing over target** |

At three classes the literal phrasing costs 20× (39 s against 2 s) and never
proves optimality beyond nine classes. The penalty phrasing is *free relative
to pure satisfaction* — 13 s against 19 s at six classes, 76 s against 88 s at
nine — while also returning an optimum.

The reason is not subtle: `max` and `min` over a set are weak to propagate
through, and proving a bound on their difference is most of the work. A sum of
per-day penalties is linear, and a solver can reason about it directly.

The second row above is the one worth dwelling on. **Removing the objective
makes the problem harder, not easier.** With something to improve, CP-SAT
devotes workers to large-neighbourhood search, and those are what find the
first feasible solution at this size. Running in pure satisfaction mode to "go
faster" finds nothing at all.

## Decision

**Every soft constraint is modelled as a weighted sum of per-unit penalties.**
A preference is expressed by what each unit of deviation costs, never by an
extremal quantity over a set.

CT-20 becomes: for each class and day, a penalty for each hour the day runs
past its target length, weighted. Not `max − min`.

**The solver is always given an objective**, even when the planner has set
every weight to zero — in which case the objective is a constant and costs
nothing, but the search still behaves as an optimisation.

## Consequences

**UC-04 and the tractable formulation agree**, which is lucky rather than
designed. A weight per soft constraint is both what the planner wants to tune
and what the solver wants to be given, so the settings screen maps directly
onto the model with nothing in between.

**Some requirements need restating before they can be modelled.** §2.10 is
written in extremal language in more than one place; each will need converting
to "what does one unit of this cost". That conversion is a design step, not a
transcription, and it is where the meaning can quietly change — "longest minus
shortest ≤ 3" and "each hour past six costs one" are not the same preference,
they merely want the same thing. Where they differ, the requirement should be
re-agreed with the school rather than silently reinterpreted.

**Penalty units must be commensurable.** Once everything is a weighted sum, an
hour of over-long day and a lesson outside its teacher's home room are being
traded against each other by their weights alone. Weights therefore need a
documented scale, or tuning one will silently swamp another.

**A hard constraint stated as a heavy penalty is still soft.** The temptation
to make something "effectively required" with a large weight should be
resisted: hard constraints belong in the model as constraints, so that an
impossible plan comes back as infeasible and can be explained (UC-32), rather
than as a very expensive solution that looks like success.
