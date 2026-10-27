# Architecture decision log

One file per decision, numbered in the order they were taken. A decision gets
an entry here when reversing it would be expensive — a dependency the rest of
the design leans on, a boundary other code is written against, a constraint
everything downstream inherits. Routine choices do not need one.

The point is not the decision; it is the **context** and the **consequences**.
A year from now the useful question is rarely "what did we pick" — it is "what
did we know at the time, and what did picking it commit us to".

Each entry follows the same shape:

| | |
|---|---|
| **Status** | Proposed, Accepted, or Superseded by NNNN |
| **Context** | What forced a choice, and what was true when it was made |
| **Decision** | What was chosen, stated plainly |
| **Consequences** | What follows — including the parts that are worse |

An entry is never edited once Accepted, except to mark it Superseded. If the
decision changes, the new entry says so and the old one stays as it was. The
log is a record of reasoning, not a description of the current system; the
current system is described by REQUIREMENTS.md and the code.

## Index

| | Decision | Status |
|---|---|---|
| [0001](0001-timetable-solver-engine.md) | Timetable solver engine: OR-Tools CP-SAT | Accepted |
| [0002](0002-soft-constraints-as-weighted-penalties.md) | Soft constraints as weighted penalties | Accepted |

Decisions taken before this log existed are recorded in `TECH_STACK.md` and in
`REQUIREMENTS.md` §2; they have not been back-filled.
