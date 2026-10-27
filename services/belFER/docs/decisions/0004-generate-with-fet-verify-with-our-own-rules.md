# 0004 — Generate with FET, verify with our own rules

**Status:** Proposed (2026-10-22)

## Context

FET laid out the whole school — twenty-five classes, 1131 activities — in **two
seconds**, satisfying every one of the sixteen rules in REQUIREMENTS §2.9 that
we asked it for, checked independently afterwards. Our own solver produces a
complete week for fifteen and twenty classes and has never produced one for
twenty-five.

That is not a gap tuning closes. It is the difference between a program that
works and one that does not, and it forces the question of whether to keep
building a solver at all.

## The options

### A — keep building our own

OR-Tools CP-SAT, the decomposition of ADR 0003, the learning loop.

### B — drive FET and stop building a solver

Write the school out as `.fet`, run `fet-cl`, read the result back.

### C — drive FET, and keep our rules as an independent checker

B, plus a verifier in our own code that reads a finished timetable — whoever
made it — and reports which of our rules it satisfies.

## What each is good and bad at

**Correctness.** FET has been timetabling real schools for twenty years and has
a constraint vocabulary far past our sixteen rules. It also refuses bad input:
in one afternoon it caught three faults in our data and our translation that
our own pipeline would have carried silently into a failed search — a class
charged a cross-class hour once per group, a subgroup owing a forty-four hour
week, and two activities told to coincide and to fall on different days. We
have since added the first of those checks to our own validation.

Against that, **the translation becomes the thing that can be wrong**, and all
three of those faults were in the translation rather than in the data. Rules
would live in two places — our validation and the exporter — and two
expressions of one rule drift.

**Expressiveness.** FET's vocabulary is wider than ours almost everywhere, and
narrower in exactly one place that matters: it can say an activity ends a
class's day, and it can pin one to an hour, but it cannot say **"first or
last, whichever suits"**. We exported the stricter reading and it happened to
work. That is luck rather than design, and it is a real limit — religia and
etyka are the hardest objects in this school.

It also cannot express *"a class's day starts at the first hour"* as a hard
rule. On the evidence that is a point in its favour: twenty-eight per cent of
its class-days start later, and the rule was never the school's.

**Performance.** Two seconds against hours, or against nothing at all. This is
not a tuning difference, it is a difference in what the product can be:
REQUIREMENTS §2.11 budgets *a few hours* for a run and designs an asynchronous
job around it. At two seconds the planner presses a button and waits. Several
use cases collapse into nothing — progress reporting, early stop, run
comparison — and the interesting ones get cheap: try a change, see the week,
try another.

**Tuning.** Ours is a model we control completely: the optimisation key of
UC-04 is ours to shape, soft constraints are weighted as we please, and an
infeasible run can be explained in our own vocabulary because we built the
machinery for it. FET offers weight percentages per constraint, which covers
much of UC-04 and is not the same thing, and when it fails it explains itself
in its terms rather than ours.

**Cost.** FET is free and the work is a round trip: stable identifiers out,
process management, results back. Call it a week. Our solver's remaining cost
is unknown, which after this session is the honest figure rather than a
pessimistic one.

**The licence is the one real catch.** FET is **AGPL v3**, not GPL. Running an
unmodified copy as a separate process, for a school's own timetabling on its
own machine, asks almost nothing of us. Offering belFER to other schools as a
hosted service would: section 13 reaches users across a network. That costs
nothing to satisfy today — publish FET's source alongside, which is a link —
but it is a decision to take knowingly rather than discover later.

**Running it on the home lab.** A Linux x86-64 build is published, and FET is
packaged by most distributions. `fet-cl` is a separate binary from the GUI and
the macOS bundle does not ship it, which is a packaging nuisance rather than a
difficulty. It is one process, one input file, one output directory, no
service and no state.

**One more, in FET's disfavour.** It is essentially one author's work, twenty
years in. It is free software and we could fork it, but we would be forking a
large Qt C++ program to keep a feature alive, and we should not pretend
otherwise when weighing it.

## Decision

**Option C.** Generate with FET. Keep our rules in our own code as a verifier
that reads a finished timetable and says which of them it satisfies.

The reason is not that FET is better than what we would build. It is that
**the verifier is a thing we need regardless**, and once it exists the main
argument against depending on FET goes away.

We need it because UC-41 to UC-43 — move a lesson, swap two, reject an edit
that breaks a hard rule — are not solver work. They are rule work, in our
domain, against a timetable that already exists. Those rules have to live in
our code whatever generates the week.

And once they do, FET's two weaknesses stop mattering much. Its opacity does
not matter when we never take its word for anything: every timetable it
returns is checked against our own statement of the rules, in our own
vocabulary, before anyone sees it. The risk that the translation drifts does
not matter when the check is independent of the translation — an exporter bug
makes a timetable that fails our verifier, which is exactly what happened three
times this afternoon, except that it was FET doing the complaining and it will
be us.

That leaves FET as what it is good at — searching — and keeps in our hands the
two things that are properly ours: what the rules *are*, and what happens when
somebody wants to move a lesson.

## Consequences

**Our solver stops being on the critical path.** It is not deleted. It solves
fifteen and twenty classes, it is the only thing that can express "first or
last", and it is a second opinion on instances FET finds hard. ADR 0001 and
0003 stand as the record of why it is built the way it is. But nobody waits for
it.

**The exporter needs identifiers that survive the round trip.** It currently
numbers activities sequentially and cannot map a result back to a lesson line.
That is the first piece of work.

**The verifier is the piece that must be right.** It is much smaller than a
solver and has no search in it, which is the point. It should be tested against
timetables known to be good and known to be bad, including the one FET has
already produced.

**"First or last" is exported stricter than the school is.** Until FET can say
it — or we pre-place those activities ourselves — a week it calls impossible
might be possible. Worth remembering before believing a refusal.

**The AGPL decision is taken now**, for self-hosting, with the knowledge that
offering this to other schools as a service would mean publishing FET's source
alongside. Nothing we are doing today is affected.
