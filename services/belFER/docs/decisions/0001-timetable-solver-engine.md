# 0001 — Timetable solver engine: OR-Tools CP-SAT

**Status:** Accepted (2026-10-11)

## Context

belFER exists because the school's current software cannot express the rules
the plan actually has to satisfy, so the engine underneath it is not an
implementation detail — it is the product risk. REQUIREMENTS §2.11 named
Timefold, but that was a choice made on paper before anything had been tried,
and ROADMAP 0.4 only ever proved Timefold *runs* under Bazel. Whether it could
solve this problem was untested.

Five candidates were measured against the same instance, each handing its
answer to the same independent verifier, so "can it express the rule" is
settled by a finished timetable rather than by documentation. The instance
carries the things that make this problem awkward rather than a generic
timetable: a supporting teacher booked alongside the first, both halves of a
class busy at once, a cross-class unit with a partial merge, extension hours
competing with their base subject for the day, and the three limits on a
teacher's week. At full size it is 25 classes, 727 lessons, 153 teachers.

What the measurements found:

- **JaCoP** was the fastest candidate on a simpler early instance — 25 classes
  in 463 ms — and with the real rules cannot place **six** classes in four
  minutes, or twenty-five in thirty. The early number belonged to the easier
  model.
- **Timefold** degrades linearly with the paired rules: −2 hard per band of
  three classes, always on the split halves, and ten minutes at full size was
  no better than two. Its default move selectors change one variable at a time,
  and moving one half of a pair always breaks the pair. This is fixable with a
  custom move selector and is a limit of the model written, not of the engine —
  but it is work the chosen candidate did not need.
- **Choco** solved three classes in two seconds and never solved twenty-five;
  the obvious remedy, `domOverWDeg`, made it worse at both sizes.
- **Hand-rolled annealing** worked on the simpler instance and showed what the
  libraries buy: optimality bounds, infeasibility explanation and weights would
  all be ours to build.
- **OR-Tools CP-SAT** solves the full school with all sixteen rules and proves
  it optimal, in about half an hour on ten cores.

Full detail, including the numbers and their caveats, is in
[SOLVER_EVALUATION.md](../SOLVER_EVALUATION.md).

## Decision

**Use OR-Tools CP-SAT as belFER's timetable solver**, behind the existing Java
boundary at `//services/belFER/backend:solver`, so no solver type crosses into
the Scala side and the engine stays replaceable.

This supersedes the Timefold choice recorded in REQUIREMENTS §2.11. That
section should be read as the reasoning available at the time, not as current.

## Consequences

**The solver is no longer pure JVM.** `ortools-java` wraps a native binary, so
the runtime dependency is per-platform. This is the real cost of the choice:

- Every platform's binary arrives transitively — darwin, linux, Windows — about
  106 MB of which one is ever used. Trimming the image means excluding
  artifacts explicitly.
- protobuf-java must be pinned to the version OR-Tools was generated against.
  A mismatch is a hard failure at class-load, not a warning.
- It is proven to run in a Linux container. It is **not** proven to run in the
  `linux/amd64` image the services ship, because no machine here can execute
  one; that check is outstanding and belongs on a Linux host.

**Runs are a ~30-minute job at school size, and that shapes the UI.** The
timeout must be set clear of the expected run rather than at it — a budget
equal to the solve time turns a proven optimum into a merely good answer. The
generation screen should show the best plan so far and let the planner stop
early (UC-30), rather than promising an optimum.

**Parallelism is load-bearing.** Ten cores produced a timetable; two produced
nothing in the same thirty minutes. The worker count belongs in configuration,
not left to `availableProcessors()`.

**Infeasibility explanation gets harder.** This is the one place Timefold was
ahead: its score explanation is first-class, where CP-SAT offers an unsat core
over assumption literals. UC-32 is a real feature and now needs a spike before
its design is fixed. If that spike goes badly it is the most likely reason to
revisit this decision — in which case Timefold with custom move selectors is
the fallback, and that work is now understood rather than speculative.

**The constraint model is written against CP-SAT's idioms.** Resources are
intervals with `noOverlap`; occupancy is time-indexed booleans. A different
engine later means rewriting the constraints, not just swapping a dependency —
which is why the Java boundary matters and why nothing above it may see a
solver type.
