# Choosing the solver — measured against the school's real rules

> Phase 3 rests on this choice. ROADMAP 0.4 only ever proved Timefold runs
> under Bazel, not that it is the right engine. The code is in
> `services/belFER/solver-poc/`; this is what it found.

## How it was measured

Each candidate is given **the same instance** (`poc/Instance.java`) and its
answer is checked by **the same verifier** (`poc/Verifier.java`). Nothing here
rests on what a library's documentation claims — a rule counts as expressible
only when a finished timetable satisfies it, checked independently of the
solver that produced it.

`POC_CLASSES` scales the instance from 3 classes (87 lessons) to 25 (727
lessons, 153 teachers, 81 rooms, 26–30 of 40 slots filled per class), which is
the shape of the real school. `POC_SKIP` drops individual rules, `POC_OBJECTIVE`
switches the optimisation key, and `POC_PRINT` prints a class's week.

## The rules, and whether they can be said without hacks

This was the question worth answering, and the answer is yes for both
candidates — with one exception worth knowing about.

| | Rule | How it is said |
|---|---|---|
| R1 | class never double-booked | one sum per half of a class, ≤ 1 |
| R2 | teacher never double-booked | one sum per teacher per slot |
| **R12** | **nauczyciel wspomagający** | **the same sum, with the supporting teacher in it** |
| R3/R3b | rooms, and the right kind | optional intervals per candidate room |
| R5 | one block of a subject per day, per half | "at most one of these starts today" |
| R6 | a block stays inside one day | a start that would overrun is never offered |
| **R7** | **both halves busy together** | **one equality between the two halves' sums** |
| **R8** | **zajęcia międzyoddziałowe** | **equal starts, and a group books resources once** |
| R9 | day starts at slot 1, no gaps | busy slots non-increasing within a day |
| R10 | religia/etyka first or last | at slot 1, or the next slot is free |
| R11 | teacher unavailability | the slot is never offered |
| R13/R14 | working days, lessons per day | sums over the same busy grid |
| **R15** | **≤ 2 free periods in a row** | **"busy earlier" and "busy later" per position** |

Three of these deserve comment.

**The halves rule was wrong before I measured it.** "Both halves of the same
subject run together" does not describe what the school does. The real rule is
that whenever one half of a class is busy, so is the other — full stop. Said
that way it is *one equality between two sums*, it is simpler than the version
it replaced, and the language swap falls out of it for free: Gr1 sitting
angielski while Gr2 sits niemiecki satisfies it exactly as a shared subject
does. The POC forces that case by giving the halves opposite language loads,
and both solvers produce it.

**A merged cross-class group needed a concept, not a hack.** Modelling "3A's
girls alone, its boys with 3B's" as one lesson per class gives two classes the
same teacher in the same slot — which is how the first instance came out
infeasible. What it needs is the idea that *a group is taught once however many
classes it draws from*: one teacher, one room, every participating class
occupied. That is `Instance.booksResources`, and it is the only structural
concept the exercise added.

**R15 is the one that takes care.** "No more than two free periods in a row" is
not a statement about a pair of lessons or about a single slot; it needs to
know whether the teacher is present on both sides of a run. Done naively — a
fresh pair of helper variables per window — it added about twenty thousand
variables restating what the busy grid already said, and the model went from
solving in minutes to overrunning a thirty-minute limit by a factor of five.
Done properly, "busy somewhere earlier" and "busy somewhere later" are computed
once per position and reused. Any solver will need that care; it is a property
of the rule, not of the library.

Everything else is ordinary. Extension hours need no machinery at all — base
and extension share a subject name, so they compete for the same day exactly as
the requirements say they should.

## What each candidate did

Five were tried. Three were ruled out on the first, simpler instance and are
recorded at the end; the two that survived were taken to the real rule set and
the real size.

### At school size — 25 classes, 727 lessons, 30 minutes

| | OR-Tools CP-SAT | JaCoP |
|---|---|---|
| 3 classes | 16/16 | 16/16 in 0.4 s |
| 6 classes | 16/16 | **nothing** |
| 25 classes | **16/16, proven optimal** | **nothing, even in 30 min** |

**JaCoP cannot do this problem.** On the earlier, simpler instance it was the
fastest thing on the page — 25 classes in 463 ms. With supporting teachers,
paired halves, merged cross-class groups and the three teacher-shape rules it
cannot place **six** classes in four minutes, or twenty-five in thirty. The
earlier number was a property of the easier model, not of the library, which is
exactly why the instance was made to carry the real rules.

**CP-SAT solves it, and the optimisation key decides whether it does.**

### The optimisation key is the whole ball game

The same model, the same rules, the same budget — only the objective differs:

| classes | satisfaction only | `spread` = max − min | `linear` = hours past target |
|---|---|---|---|
| 3 | OPTIMAL, 2 s | OPTIMAL, 39 s | **OPTIMAL, 2 s** |
| 6 | OPTIMAL, 19 s | FEASIBLE, hit limit | **OPTIMAL, 13 s** |
| 9 | OPTIMAL, 88 s | FEASIBLE, hit limit | **OPTIMAL, 76 s** |
| 12 | none | none | FEASIBLE |
| **25** (30 min) | **none** | FEASIBLE, spread 72 | **OPTIMAL, penalty 0** |

Two things fall out of this and neither was obvious beforehand.

**Asking for nothing is worse than asking for the right thing.** Pure
satisfaction finds nothing at all at 25 classes in thirty minutes, while the
same model with a linear objective comes back optimal. CP-SAT devotes workers
to large-neighbourhood search when there is an objective to improve, and those
workers are what find the feasible solution in the first place. Leaving the
objective out to "make it easier" makes it harder.

**How the preference is phrased matters more than any constraint.** CT-20 says
a class's longest and shortest day should not differ by much. Said literally —
minimise max minus min — it costs 20× at three classes and never reaches
optimality beyond that. Said as a weighted penalty — every hour a day runs past
its target costs one unit — it is free relative to pure satisfaction and solves
the full school. The second phrasing is also what UC-04 describes, so the
requirement and the tractable formulation happen to agree.

### What it actually takes at school size

Run three times, because the first result was too good to report without
checking — and the check turned out to explain itself:

| budget | outcome | time taken |
|---|---|---|
| 30 min | **OPTIMAL**, penalty 0 | 1788 s |
| 30 min | FEASIBLE, penalty 16 | hit the wall at 1800 s |
| 2 hours | **OPTIMAL**, penalty 0 | **1754 s** |

**Proving optimality takes about half an hour, so a half-hour budget is exactly
the wrong one to give it.** Two of the three runs got there, both at around
1,760–1,790 seconds; the one that did not was the one whose deadline landed in
the middle of that window. Given two hours it used twenty-nine minutes and
stopped — the extra budget was never spent, it just removed the cliff.

The practical reading: the work is a ~30-minute job at this size, and the
timeout should be set well clear of it rather than at it. A run that is cut off
still returns a valid timetable — the verifier checks the finished article, so
the FEASIBLE row is a real plan a school could hang on a wall, 16 hours over
target across 25 classes — but there is no reason to accept that when an hour's
headroom gets the optimum.

**Cores matter more than the headline suggests.** All of the above had ten.
With two, the same model and the same thirty minutes produced *nothing*.
Whatever box this ends up on wants real parallelism, and the worker count is
worth exposing rather than leaving to `availableProcessors()`.

### Caveats on these numbers

- **Single runs, except where stated.** CP-SAT's portfolio is multi-threaded
  and stochastic, and the results are not monotone in size: 15 and 20 classes
  returned nothing at four minutes while 25 came back optimal at thirty. Treat
  each cell as one draw, not a curve. The full-size run is the one that was
  repeated, three times, and the spread between those runs is what the section
  above is about.
- **The time limit is approximate.** A 12-class run took 328 s of a 240 s
  budget. CP-SAT checks its deadline between phases, and a large presolve can
  overrun it. For a user-facing "stop after N minutes" this needs a hard outer
  timeout as well.
- **The instance is regular.** 25 classes of three shapes, not 25 different
  ones, and ~30 hours each against the real school's 26–34.

## Deployment: the native dependency

CP-SAT is not a JVM library. `ortools-java` is a thin wrapper over a native
binary, which is the one real cost of choosing it.

**It runs in a Linux container — checked, not assumed.** The POC solves its
instance to 16/16 inside `eclipse-temurin:25-jre-noble`. That was proved on
linux/arm64 rather than linux/amd64, because **this machine cannot execute
amd64 containers at all** — `docker run --platform linux/amd64 alpine uname -m`
fails with `exec format error`, so the emulation is simply not enabled here.
The amd64 image builds and loads correctly and carries the right artifacts; what
remains unproven is specifically amd64 *execution*, which needs a Linux host or
Rosetta turned on in Docker. The mechanism — a native library loading and
solving inside a container — is the same one, and it works.

Three things worth knowing before building on it:

- **Every platform's binary ships.** `ortools-java` depends on all of them, so
  darwin-x86-64, darwin-aarch64, linux-x86-64, linux-aarch64 *and* win32 all
  arrive transitively — about 106 MB, of which one is ever used. A `select()`
  on the target platform is therefore pointless, and trimming the image means
  excluding artifacts explicitly.
- **Protobuf versions must match exactly.** `gencode 4.33.1 / runtime 4.33.0`
  is a hard failure at class-load, not a warning. protobuf-java is pinned to
  whatever OR-Tools was generated against.
- **rules_oci still cannot resolve an arm64 temurin base**, the same wall
  Phase 0 hit. The container test here goes through a plain Dockerfile instead.
  Not a problem for the services, which ship amd64, but it is why there is no
  `oci_image` for the POC's arm64 variant.

Unrelated to the choice but now load-bearing: Choco and JaCoP still ask for
slf4j 1.7 where everything else wants 2.0, so slf4j-api is pinned and
`version_conflict_policy = "pinned"` is set in MODULE.bazel.

## The three ruled out earlier

Measured on the first, simpler instance — before the real rules were added —
and not carried forward:

| | why not |
|---|---|
| **Timefold 1.16** | Degrades linearly with the paired rules: −2 hard per band of three classes, always on the split halves, and ten minutes at full size was no better than two. Its default move selectors change one variable at a time, and moving one half of a pair always breaks the pair. Fixable with a custom move selector — the number is my model's ceiling, not Timefold's — but that is work the other candidates did not need. |
| **Choco 4.10** | Two seconds at three classes, nothing at twenty-five. The obvious remedy made it worse: `domOverWDeg` failed at *both* sizes. |
| **Hand-rolled annealing** | Worked — 114 s at full size on the simpler instance — and showed what the libraries buy. Everything else (optimality bounds, infeasibility explanation, weights) would be ours to build. |

## Recommendation

**Build Phase 3 on OR-Tools CP-SAT**, behind the existing Java boundary so the
engine stays replaceable — which is what `//services/belFER/backend:solver`
already exists to guarantee.

Three things this exercise says about how to build it:

1. **State the optimisation key as weighted penalties, not as extremal
   quantities.** This is worth more than any other decision here: the same
   preference phrased two ways is the difference between a usable timetable for
   the whole school and no answer at all. UC-04's weights are the right shape;
   resist the temptation to write CT-20 literally.
2. **Always give it an objective.** Running in pure satisfaction mode is slower
   at scale, not faster — the objective is what earns the large-neighbourhood
   workers that find the first feasible solution.
3. **Set the timeout well clear of the expected run, not at it.** At school
   size the work is about half an hour on ten cores; a half-hour budget is the
   one setting that turns a proven optimum into a merely good answer. §2.11
   already allows a few hours, and an hour of headroom costs nothing because
   the solver stops when it is done. The UI should still show the best plan so
   far and let the planner stop early — but the default should not be a cliff.
4. **Expose the worker count**, and put a hard timeout outside the solver,
   because its own limit is approximate.

Two things still open, neither a blocker:

- **Infeasibility explanation (UC-32).** CP-SAT gives an unsat core over
  assumption literals, which is a usable basis but more work than Timefold's
  score explanation. This is the one place Timefold is genuinely ahead and it
  deserves a spike before the design is fixed.
- **amd64 execution**, which needs a machine that can run it.

## Running it

```bash
POC_CLASSES=25 POC_OBJECTIVE=linear \
  bazel run //services/belFER/solver-poc:ortools -- 1800
```

`POC_SKIP=R15,SOFT` drops rules — useful for finding which one an instance is
actually straining against. `POC_PRINT=0,1` prints those classes' weeks.
`POC_CLASSES` scales the school. The second argument to the binary caps the
worker count.

---

# Addendum: what happened when the real school went through it

The comparison above used a generated instance. Phase 3.1 put the school's own
data through the chosen engine, and the result is worth recording here because
it changes what Phase 3.4 can promise.

## Fifteen classes solve. Eighteen do not.

| classes | 8 | 15 | 18 | 20 | 22 | 25 |
|---|---|---|---|---|---|---|
| 30 minutes | ✅ | ✅ 785 s | ❌ | ❌ | ❌ | ❌ |

The wall is sharp and it is not about time: twenty-five classes given **two
hours** did no better than ten minutes. A search that never finds a first
feasible solution does not improve with patience.

## It is the no-gaps rule

Dropping each rule in turn, at twenty-five classes:

| | |
|---|---|
| without **a class's day being unbroken** | **solves, 307 s** |
| without anything else | nothing |

That rule is not negotiable — a student has nowhere to be during a free period
— so this is a statement about what it costs, not an argument for dropping it.

## Four things that did not help

Recorded because each sounded reasonable and each cost an hour to disprove:

| attempt | result |
|---|---|
| **Pairing hours into doubles.** The arkusz states hours, never their shape, so an import is almost all single lessons — 1,175 blocks for 1,100 hours. Fewer blocks ought to mean a smaller problem. | No. 15 classes went from solvable to **not**. Block count is not the driver. |
| **A warm start.** Solve five classes at a time, hand the combined answer to the full run. CP-SAT improves an arrangement far better than it finds one. | No. All five chunks solved, 1,053 hints, still nothing. The chunk answers conflict too thoroughly to be a starting point. |
| **Re-encoding contiguity through the day's length** rather than slot-by-slot implications, so that fixing one slot settles the whole day instead of only its neighbour. | No. Better propagation, same wall. |
| **Bounding day lengths to within an hour of even**, and branching on them first, to collapse the 8+8+8+8+6 versus 8+8+8+7+7 search. | No, and it restricts solutions for nothing, so it was reverted. |

The first two were guesses at a problem that had not been diagnosed. The rule
ablation that named the culprit took one pass and should have come first.

## What the chunk timings say

Solving five classes at a time, same size and same rules each time:

```
1A, 1C, 1D, 1E, 1F    FEASIBLE  343 s (hit its limit)
1G, 1H, 2A, 2C, 2D    OPTIMAL     4 s
2E, 2F, 2G, 2H, 3A    OPTIMAL     3 s
3D, 3F, 3H, 4A, 4C    FEASIBLE  343 s
4D, 4E, 4F, 4G, 4H    FEASIBLE  343 s
```

Two orders of magnitude between groups of the same size. The difficulty lives
in **particular classes**, not in the total — the slow groups are the ones whose
classes carry five split subjects each, nearly all with one teacher taking both
halves. That is also why the two volume-based remedies failed.

## What this means for Phase 3.4

A single model over the whole school is not the way to generate a plan at this
size. The remaining approach is **staged construction**: solve a few classes,
fix them, extend, and backtrack when a stage fails. That is a real algorithm
rather than a parameter, it can paint itself into corners that a whole-school
solve would have avoided, and it needs deciding before 3.4 is designed.

Until then the solver is genuinely useful below the wall — fifteen classes is
most of a school year group, and the CLI takes `--limit` for exactly that.

## The shape of the answer has to change

Waiting is not a strategy. Time to the first usable plan, measured from
CP-SAT's own log:

| classes | first plan appears |
|---|---|
| 15 | 276 s |
| 20 | around 900 s — marginal, and it does not always arrive |
| 22–25 | never, up to two hours |

Three and a half times longer for five more classes, and then a cliff. On that
trend twenty-five should land near 3,000 s; it produces nothing at 7,200. This
is the shape of a problem that has crossed from loosely to critically
constrained, and no budget buys the other side of it.

It also means a run gives **nothing for several minutes and then a plan**,
which improves quickly once it arrives — eight better answers inside a fifth of
a second at fifteen classes. A generation screen has to say so, or the planner
will conclude it has failed while it is still working.

### Choose the shape, then fill it in

The way out is already in the model, and it is the one thing the no-gaps rule
hands us for free: **because a class's day is unbroken, the slots that class
occupies are decided entirely by its five day lengths.** Which lesson goes
where is a separate question.

So the problem is really two:

1. **The shape** — how many lessons each class has on each day. A handful of
   numbers per class, summing to its week, each no longer than a day.
2. **The filling** — which lesson goes in which of those slots, subject to
   every rule about teachers, rooms, halves and subjects.

Today the solver does both at once, and the freedom in the first multiplies the
search in the second. Fix the shapes and "is this class in school now" stops
being a variable and becomes a fact: the contiguity constraints disappear
entirely, the day-length variables disappear, and what remains is an assignment
problem.

This is also how a person does it — decide that 1A has seven lessons on Monday,
then work out which seven.

**Why this is a better decomposition than the one already tried.** Solving five
classes at a time failed because the chunks booked the same teachers at the same
hours and their answers could not be reconciled. Shapes do not conflict: one
class's day lengths constrain only that class. The interaction is indirect and
weak — shapes together determine how many rooms are wanted at each hour — and
that is a counting question, checkable before any search begins.

**What it costs.** A shape can turn out unfillable, and then something has to
choose the next one. The honest version is a loop: start from the most even
shape, and when the filling fails, use the rooms-per-hour count and the solver's
own unsatisfiable core to decide which class's week to alter. That is a real
algorithm with its own failure modes — it can take several rounds, and a badly
chosen first shape wastes one — which is why it belongs in Phase 3.4 as a piece
of design rather than being bolted on here.

### Settling the shape first: measured

| classes | solving both at once | shape settled first |
|---|---|---|
| 15 | a plan after 900 s, never proven best | **best possible, 74 s** |
| 18 | nothing, at any budget | **best possible, 82 s** |
| 20 | sometimes a plan, right at the deadline | **best possible, 220 s** |
| 22 | nothing | nothing |
| 25 | nothing | nothing, including a full hour |

Five more classes, and a different kind of answer: the solver now proves there
is no better timetable, where before it spent its whole budget looking for any.

Twenty-two is the new wall and it is not short of rooms. Under the even shape
the busiest hour has twenty-five classes in school against thirty-two rooms,
with room to spare for every split. What stops it is search, which is what a
second shape is for — the even one is only the first guess, and it is the guess
that puts every class in school at exactly the same hours.

### What a wrong shape costs

The first estimate of the loop's cost assumed a wrong shape always costs the
whole budget, because that is what the even shape does at twenty-two and
twenty-five. Trying a second shape showed the assumption was wrong, and the
difference matters: there are three outcomes, not two.

| the shape is | what it costs | seen in |
|---|---|---|
| impossible, and arithmetic can see why | nothing — no search at all | compact at 22 |
| impossible, and only search can see why | seconds, proven | compact before the checks |
| fillable | 19–117 s to a first plan | even at 15, 18, 20 |
| fillable but beyond reach | the whole budget | even at 22, 25 |

Only the last is expensive. So the loop costs roughly

    (shapes that are hard × budget) + the one that works

and the checks below move as many shapes as possible out of that first term.

The compact shape is the example worth keeping. It fails because a subject
wanting five blocks wants five separate days, and packing days to the brim
sends a twenty-four-hour class home after four of them. The solver needed nine
seconds to find that contradiction. Counting needs none, and it names 1A, 1H
and 4A rather than simply saying no — which is what tells the loop whose week
to alter next.

### A second shape does not move the wall

The even shape is an obviously poor guess for a whole school: it gives every
class its long day on Monday, so all of them want the late hours at once and
all leave early on Friday. Rotating it — same day lengths for any one class,
turned by one day for each successive class — spreads that out, and was the
natural first thing to try.

| 22 classes | result |
|---|---|
| even | nothing, 900 s |
| rotated | nothing, 600 s |
| compact | impossible, and now rejected by counting |

Twenty-five behaves the same way. So the contention argument, plausible as it
was, is not what twenty-two is straining against — or at least not in a way a
better spread of day lengths relieves. Two informed guesses, neither of which
produced a single timetable.

This is the number the loop's cost estimate was missing, and it is not the one
that was hoped for. Each attempt costs what the budget says plus a minute to
build the model, and two run side by side comfortably; what is missing is any
evidence that attempts converge at all above twenty.

### The loop cannot enumerate, so it has to steer

Asking how long it takes to try several shapes invites the question of how
many there are to try. A class with twenty-four hours can split its week
across five days in 3,195 ways; the school's twenty-five classes together
have about 4 x 10^64 combinations. Nothing enumerates that, at any budget,
ever. "Try another shape" can only mean changing the one we have.

So the loop needs a direction to move in, and the checks above give one. They
do not answer no, they answer *1A needs five separate days and this shape
gives four* — which names the class to alter and the amount to alter it by.
The solver's unsatisfiable core would say something similar, but only for
shapes it can prove impossible, and those are the cheap case already. The
expensive case — a shape that is fillable but out of reach — produces no core
at all, because nothing was ever proven. Guidance has to come from counting
and from structure, not from the solver's failures.

### What is still missing

The loop. One shape is tried; when it fails nothing chooses another. That wants

  - ~~a count, before any solving, of how many classes each shape puts in school
    at each hour~~ — done, along with the days-a-subject-needs check above
  - a rule for what to vary when a filling fails, with the solver's own
    unsatisfiable core to say whose week to alter
  - a budget per attempt, which on this evidence can be short: a shape that
    works does so in a minute or two, so a timeout really does mean "bad shape"
    rather than "be patient"

That last point only became safe to say once the shapes were measured. Before
it, a timeout meant nothing at all.

# Addendum: what the people who already solved this did

Everything above was worked out from first principles, which was a mistake
worth naming. School timetabling has been studied since the 1960s, it has a
standard problem formulation, a benchmark archive, and a literature that had
already answered the question we were stuck on.

## Our school is not a big instance

The standard benchmark is **XHSTT**, an XML archive of real instances from
around the world, collected for the ITC-2011 competition and maintained since.
Set against XHSTT-2014:

| instance | times | teachers | rooms | classes | lesson-hours |
|---|---|---|---|---|---|
| AU-BG-98 | 40 | 56 | 45 | 30 | 1564 |
| IT-I4-96 | 36 | 61 | — | 38 | 1101 |
| KS-PR-11 | 62 | 101 | — | 63 | 1912 |
| UK-SP-06 | 25 | 68 | 67 | 67 | 1227 |
| **I LO** | **40** | **75** | **32** | **25** | **1175** |

We are mid-sized, and smaller than several instances that have been solved to
proven optimality. Twenty-five classes is not where this problem gets hard, so
the wall was ours and not the school's.

## Finding the first timetable is the hard part, and that is known

It is not a quirk of our model that a budget buys nothing until it suddenly
buys everything. The curriculum-timetabling literature reports instances where
a single feasible solution took under an hour for half of them, under two
hours for two thirds, and **over ten days** for a few. Our ten-minute budgets
were never going to settle anything.

It is also why XHSTT results are customarily reported *without a time limit*:
the exact methods that hold the best-known bounds are given days.

## Nobody solves the whole week at once

Every competitive method decomposes, and two of them decompose the way we
needed:

  - **Valouxis, Gogos, Alefragis and Housos**, *Decomposing the High School
    Timetable Problem* (PATAT 2012). Phase one solves **one day at a time**,
    each day an IP over teachers' daily schedules generated as columns. Phase
    two then takes **pairs of days** and moves lessons between them, which they
    describe as a very-large-scale neighbourhood search. Best known results for
    two XHSTT instances.
  - **Fonseca and Santos**, fix-and-optimize with variable neighbourhood
    descent, using **three decompositions — class, teacher and day**. New best
    known solutions for seven instances, three of them optimal.

The second half of Valouxis is the part we do not have: re-solving two days
together is what repairs a day that was filled greedily.

## Why a day is the right unit for us specifically

Our hardest rule is that a class's day has no gaps. That rule lives **entirely
inside one day**. Posed over a week it couples every placement to every other
through forty slots; posed over Monday it is a constraint on eight. The
decomposition the literature arrived at for general reasons happens to cut our
hardest constraint along its grain.

Measured, for all twenty-five classes: a day solves to proven optimality in
one to three seconds. The week has never solved at all.

## Calibration: what an ordinary school actually runs

FET is open-source, in use by real schools, and solves this problem with a
constructive heuristic called recursive swapping — place the hardest lesson
first, and when nothing fits, recursively swap what is in the way. It reports
five to twenty minutes for a complicated timetable, hours for an extreme one.
No exact search anywhere in it.

Worth holding on to as a reference point. We do not need to prove our timetable
optimal; we need a good one by Tuesday.

## What we had been doing

Exact CP-SAT, over the whole week, under a ten-minute budget. The literature
uses exact methods *without* time limits, or heuristics *within* them. The one
combination nobody uses is the one we had.

# Addendum: a week built a day at a time, and where it stops

## It produces a timetable

Fifteen classes, every one of the 732 blocks placed, in about twenty-four
seconds of solving. That is the first complete week this system has made.
Monday, Tuesday and Wednesday fall out in one to three seconds each at the
full twenty-five classes, against a whole-week model that has never produced
a single timetable at any budget.

So the decomposition is right, and the measured day times say why: our
hardest rule is about one day, and a day is small.

## Where it stops, and why it is not the repair's fault

Friday is never satisfiable on its own. Pinned to the shape, it has to be an
exact cover of whatever the first four days left behind, and that does not
happen by luck. So the week falls back on re-solving days together, and that
is where the measurements get interesting:

| window, twenty-five classes | result |
|---|---|
| day 4 | infeasible, 0.7 s |
| days 3+4 | **solved**, 36 s |
| day 5 | infeasible, 0.2 s |
| days 4+5 | infeasible, 0.8 s |
| days 3+4+5 | infeasible, 2.8 s |
| days 2+3+4+5 | infeasible, 6.3 s |
| the whole week | nothing, at any budget |

The fourth line down is the one that matters. **With four of the five days
free to be rearranged, there is no way to finish the week** — and the solver
proves it in six seconds. Monday is already wrong by then.

Which means no amount of going back one more day can help, because going back
far enough to fix Monday is the whole-week problem the decomposition existed
to avoid. The repair is not too weak; the order is wrong.

## What that leaves

Filling days in sequence makes each day's choice locally, and a locally
perfect Monday is still a fatal Monday. What is missing is the decision that
sits between the shape and the placement: **which subjects a class takes on
which day**, settled for the whole week before anything is placed.

It is a far smaller problem than placement, because it has no positions in it
— one variable per lesson per day, a class's hours per day, one block of a
subject a day, and a cap on what a teacher can take in a day. Gale and Ryser
say an arrangement exists whenever the counting works out, and for the classes
that broke the greedy run it does.

Two things already measured recommend it. The sub-week infeasibilities come
back in seconds, so a bad spread would be rejected cheaply rather than costing
a budget. And each day, given its subjects, is a one to three second problem.

## Things tried along the way that did not work

  - **Pacing each subject across the week** — holding a subject to its share
    of the week by the day in question. It does what it claims: the warnings
    about classes running out of material disappear. Friday still fails, for
    a different reason, so it is kept but it is not the answer.
  - **Letting a day run an hour over or under** — sound in principle, and it
    did carry all four of the first days at twenty-five classes without any
    repair. But give has to be paid back, and left to itself it is paid on
    Friday. Worth having, off by default: fifteen classes cost 24 s pinned and
    369 s with give.
  - **A shape pre-filter from the Gale-Ryser condition** — right idea, wrong
    arithmetic. It counted only a class's own lesson lines, so a class drawing
    hours from a cross-class unit looked short of material, and it counted both
    halves of a split as two hours rather than one hour taught twice. It called
    4A and 4F impossible when they are not. Reverted.

# Addendum: planning the days before placing the lessons

## What it is

A second, much smaller model that decides only which day each lesson falls on.
No position, no room, no contiguity, no gaps — a lesson is on a day or it is
not. What is left is the counting: a class's hours a day, one block of a
subject a day, what a teacher can take in a day and how many days they come
in, a cross-class block running everywhere at once, and two day edges to put
optional subjects at.

It answers in seconds for the whole school, which was the hope: the question
it asks is the one the day-at-a-time run was answering by accident.

## What it does and does not get

| | |
|---|---|
| two classes | complete week, every block placed |
| eight classes, contiguity relaxed | **complete week, all 370 blocks** |
| eight classes, contiguity on | day one infeasible |
| fifteen classes, contiguity on | day one infeasible |

The middle row is the finding. A rule sweep — drop one rule, see if the day
goes in — puts the whole difference on `CLASS_DAY_CONTIGUOUS` and nothing
else. Teacher gaps, rooms, room kinds, lessons per day and optional subjects
at the edges are all satisfied by what the spread hands over.

## Why that is the one it misses

A class's day may not have a hole in it, and whether a day's lessons can be
packed without one depends on where things sit, not on how many there are.
When a class is split, the two halves sit in the same slots, and whether they
can be made to line up is a question about positions. A model with no
positions in it cannot answer that, and a count that says a class owes seven
hours today says nothing about whether those particular seven hours form an
unbroken morning.

Two attempts to bridge it by counting both failed, and in the same way. Taking
the longer of the two halves says nothing about alignment. Forcing them equal
says too much — it costs more arrangements than it buys, and eight classes
went from most of a week to none of it.

## Where that leaves it

The spread does not need to be more clever about contiguity. It needs to be
told when it gets one wrong. The placement already proves a day impossible in
a fraction of a second, and that proof is exactly the information the spread
is missing — so the shape of the answer is a loop: plan the days, try to place
one, and when it will not go in, forbid that particular arrangement and plan
again. Each round is cheap, because both halves are.

That is logic-based Benders decomposition, and it is the standard answer when a
planning model cannot see what makes its plans infeasible. It is also the first
time this problem has handed us a cut worth feeding back — until the week was
broken into days, nothing failed fast enough to learn from.

Kept behind `--spread`, off by default, alongside `--relax` for dropping named
rules, which is what found the single blocker here in one sweep.

# Addendum: the loop

## What was missing, and what closes it

Two models, each blind where the other sees. The planner settles which day
every lesson falls on and sees the whole week at once, but it works in counts,
so it cannot tell whether a day it has arranged can be laid out without a hole
in somebody's morning. The placement sees exactly that and settles a day in
about a fifth of a second — but it only ever sees one day, so it cannot choose
a better week.

So the placement's failures are the planner's missing information. Every
lesson is now placed under an assumption the solver may blame, which turns an
impossible day from a verdict into a list of names: the lessons that cannot
share it. The planner takes those back as arrangements it may not make again,
and plans afresh.

This is logic-based Benders decomposition, and it is the standard answer when
a planning model cannot see what makes its plans infeasible.

## Two things that decide whether it learns or grinds

**The account has to be pared down.** The solver's own is sufficient and not
small — asked what went wrong with a day of eighty lessons it will blame all
eighty, and a rule against that exact day forbids one arrangement out of
astronomically many. Since a day costs a fifth of a second, it is cheaper to
keep asking than to accept the first answer. Whole classes go first — dropping
a class with nothing to do with it removes a dozen lessons for one question —
and then a lesson at a time. Eighty come down to five or six, named to the
classes they belong to. Measured over fifty-four rounds, the average is **7.2
lessons**.

**A lesson learned once should hold all week.** Days are alike unless somebody
is away, so lessons that cannot share a Monday cannot share a Thursday either,
provided none of the teachers involved keeps different hours on different days.
Where that holds the cut is recorded for every day rather than the one it was
found on.

## Where it gets to

**Eight classes, every one of the 370 blocks placed, in 85 plans.** Each round
is a second or two — plan, lay out days until one fails, pare the failure down,
record it, plan again — so the whole thing is a few minutes, and 84 cuts is
what it took to learn enough about this school's week to arrange one.

The last plan goes in without a single failure:

    plan 85: 370 bloków rozłożonych na dni
      dzień 1: 79 bloków, OPTIMAL w 0,1 s
      dzień 2: 71 bloków, OPTIMAL w 0,1 s
      dzień 3: 75 bloków, OPTIMAL w 0,1 s
      dzień 4: 76 bloków, OPTIMAL w 0,1 s
      dzień 5: 69 bloków, OPTIMAL w 0,1 s

Worth saying that it is not reliable yet: the loop is not deterministic, and a
run capped at 120 rounds had got nowhere near finishing while this one was done
at 85. The same pair of classes comes back round after round with a different
handful of lessons each time, because the cuts are sound and small but each one
forbids a particular set, and there are many sets that fail for the same
underlying reason.

So the next gain is not sharper cuts but more general ones — a way to say *why*
those lessons cannot share a day rather than only that they cannot. That is a
different question from any asked so far: not how to search, but what the
conflict actually is.

## Learning about the lesson rather than the block

The loop's weakness was visible in its own log: the same two classes came back
round after round, each time with a different handful of lessons. The cuts were
sound and small and each one still forbade only its own particular set, and a
class's four maths blocks make four sets that fail for the same reason.

Naming what a lesson *is* rather than which block it happened to be binds all
four at once.

| a cut names | eight classes, repeated runs |
|---|---|
| the blocks that failed | 85 plans once; another run gave up at 120 having got nowhere |
| what the lesson is | 48, 71, 71 plans — **a week every time**, in under two minutes |

The loop is not deterministic, so single runs are worth very little: the first
comparison looked like 85 against 40 and that was mostly luck. What repeating
it shows is not that the generalised cut is twice as fast but that it is the
difference between finishing and not.

The first attempt at this was wrong in a way worth keeping. Naming the subject
seems right — the blocks of a subject are interchangeable — but a class's
standard and extended hours are *one subject and often two different teachers*,
so a rule learned about the extension was applied to the base hours it was
never true of. The planner ran out of weeks it was allowed to arrange and
reported, 315 cuts in, that no arrangement existed at all.

That failure is the useful kind: an unsound cut does not produce a wrong
timetable, it produces no timetable, loudly. Everything that can make one block
harder to place than another — teacher, support teacher, length, room kind,
whether it must sit at the edge of a day — is in the key now.

## Fifteen classes

**Every one of the 732 blocks placed, in 375 plans.** The last one needs no
correction at all — five days, each proved best in about a second:

    plan 375: 732 bloków rozłożonych na dni
      dzień 1: 161 bloków, OPTIMAL w 1,0 s
      dzień 2: 154 bloków, OPTIMAL w 1,1 s
      dzień 3: 138 bloków, OPTIMAL w 1,4 s
      dzień 4: 137 bloków, OPTIMAL w 0,3 s
      dzień 5: 142 bloków, OPTIMAL w 0,7 s

Three hundred and seventy-four cuts to get there, averaging ten lessons each.
Eight classes take forty to seventy; fifteen take three hundred and
seventy-five. That is steep, and it is what sent us looking at what the cuts
were actually made of.

## Asking what the quarrels had in common

Every cut records which lessons could not share a day. Printing what they were
made of rather than only how many turned out to be the most useful thing
measured all session:

| the conflict | share of all cuts |
|---|---|
| one hour of group one, one of group two, three whole-class | 25 of 70 |
| the same, with a cross-class block | 17 of 70 |
| everything else | 28 of 70 |

**Sixty per cent of every failure was the same shape**, and it is a shape with
a name. A split normally buys a class two lessons for one hour of its day,
because the halves sit in the same slot — but not when the same teacher takes
both halves, which is rare, undesired, and real. The plan kept promising days
an hour shorter than they could possibly be, and the placement kept sending
them back one at a time.

Telling the planner about it directly takes eight classes from forty-eight to
seventy-one plans down to **forty**, and fifteen classes from three hundred and
seventy-five down to **eighteen**.

| classes | plans before | plans after |
|---|---|---|
| 8 | 48–71 | **40** |
| 15 | 375 | **18** |

Which is the shape of a real fix rather than a tuning: it does not help a
little more as the school grows, it helps a great deal more, because the thing
it removes was being rediscovered separately for every class. It also has to be given an hour of give to
put it in: the extra hour must land on some day, and an evenly divided week has
nowhere to put it. That is the same slack that did nothing useful earlier in
the session — it turns out not to have been the wrong idea, only an idea
without a reason attached.

The general lesson is worth more than the particular fix. A cut records a
failure; a hundred cuts record a pattern; and the pattern is a rule somebody
could have told us. This is the first point in the whole exercise where the
solver explained the school to us rather than the other way round.

## Where the planner stops being able to plan

The same fix that is worth twenty-fold at fifteen classes makes the week
impossible at twenty-three. Found by taking the planner's own rules out one at
a time, which is the same trick as `--relax` and just as decisive:

| the planner, at twenty-three classes | |
|---|---|
| as it stands | no arrangement exists |
| without the cap on optional subjects a day | no arrangement exists |
| **without charging a shared teacher's extra hour** | **a plan straight away** |

Twenty-two classes works and twenty-three does not, and the twenty-third class
is 4F — which is the last member of *Etyka — 2A, 2F, 2H, 3A, 4F*, so including
it brings in a block five classes have to take on the same day. The extra hour
and that unit cannot both be afforded.

So the loop asks for the better plan and settles for the plainer one, keeping
the choice for the rest of the run. Which is the right shape for this: the
richer model is worth a great deal where it fits and the plainer one still
works, so there is no reason to pick between them in advance.

## Twenty, and what "no week exists" turned out to mean

Twenty classes reaches the fourth day and then the planner reports, after about
a hundred and thirty corrections, that there is no arrangement left. Once it
could tell the difference, that report came back **proved** rather than timed
out — so this is not a matter of giving it longer.

Which pins the problem exactly. Every cut was proved by placing those lessons
and nothing else, so each one is a true fact about the school. The planner's
rules are not facts; they are approximations of things the placement knows
exactly:

  - a class is busy for the longer of its two halves, plus an hour where one
    teacher takes both — an estimate of a day whose real length depends on how
    the halves line up in actual slots
  - a day is held within two hours of its share of the week
  - a teacher may have so many hours in a day, which says nothing about whether
    those hours can be put in different slots of different classes' days

True facts and approximate rules together have no solution, and the facts are
not the ones that can be wrong. **The planner is excluding weeks that work.**

The recurring cut says where to look: the same class, 4A, four lessons, round
after round. That is the shape the shared-teacher conflict had before it was
understood, and it is the same thing to do about it — ask what those four
lessons have in common rather than forbid another arrangement of them.

## Twenty-five

Not yet. The planner now produces a week and the days go in, but after about a
hundred and fifty corrections it runs out of arrangements it is allowed to make
and reports that none is left.

That is not the same failure as before and it says something useful. The cuts
are sound — each one was proved by placing exactly those lessons and nothing
else — so exhausting the planner means the planner's idea of a week is narrower
than the truth. It counts a class as busy for the longer of its two halves,
holds a day to within two hours of its share, and allows a teacher only so many
hours a day; every one of those is an approximation of something the placement
knows exactly. Somewhere in them is a week that would work and the planner is
not allowed to propose it.

Which is a better problem to have than not knowing where to look.

## A timeout is not an answer

Paring a failure down asks one question many times over: can these lessons
still not share a day, without that one? It was reading anything that was not
a timetable as "no, still impossible" — and a probe that runs out of time has
shown nothing at all.

So a slow probe minted a rule against an arrangement that works, and the
planner then obeyed it, and every later plan was drawn from a space slightly
smaller than the truth. Enough of those and the planner reports that no week
exists, which is exactly what twenty and twenty-five classes did after a
hundred and fifty-odd corrections while fifteen finished comfortably. The
bigger the school, the slower each probe, and the more often a timeout was
taken for proof.

Worth stating as a rule, because the same trap is waiting anywhere a search
feeds its own failures back to itself: **the only failure you may learn from is
one that was proved.** Everything else is a failure to find out, and learning
from it quietly narrows the world until there is nothing left in it.

The fix is one line — require the solver to say INFEASIBLE rather than merely
not say FEASIBLE — and it is invisible at the sizes where probes are fast,
which is why it survived fifteen classes finishing twice.

## What the twenty-class conflicts are made of

Running the same diagnostic again, with the shared-teacher case already
modelled:

| the conflict | share |
|---|---|
| an hour of each half, three whole-class hours, with a cross-class block | 6 of 22 |
| the same without one | 3 of 22 |
| everything else, no two alike | 13 of 22 |

Still the same shape, and the obvious guess about it is wrong. Religia and etyka
are cross-class *and* pinned to the edges of a day, and two classes sharing one
must take it at the same hour while "last" means a different hour to each of
them — a neat story, and not this one. Marking which lessons must sit at an
edge showed the cross-class blocks in these quarrels are mostly ordinary ones,
the sports hall rather than ethics.

So the recurring conflict is a class with a split, three whole-class hours, and
a block it shares with another class, and what it is about them is not yet
known. The method that found the last one still applies — the shapes are
collected and the question is what those lessons have in common — but the
answer is not this week's.

## A figure that stopped being true

An early ablation found that twenty-five classes solve in 307 s once the
no-gaps rule is taken away, and that number has been quoted here since as
evidence that the rest of the problem is easy and only contiguity is hard.

Re-measured against the model as it now stands: **nothing, after 607 s.**

The model has changed a great deal in between — lessons may be left unplaced,
days may be solved alone, the shape may give — and somewhere in that the figure
stopped holding. It does not matter much which change did it. What matters is
that a measurement taken against one version of a model is evidence about that
version and nothing else, and this one was load-bearing: it was the reason for
believing a timetable with holes in it would be easy to come by and could then
be repaired.

So the ablation that named contiguity as the blocker should be read as a fact
about the model of that week, not about the school. Anything built on it needs
the number taken again first.

## Contiguity was never the only thing that was hard

Taking the retraction further. The original ablation did not only give a
number, it gave a culprit: remove the no-gaps rule and the week falls out, so
no-gaps is what twenty-five classes cannot afford. Re-run against the current
model, with the three most-suspected rules taken away one after another:

| twenty-five classes, posed whole | result |
|---|---|
| without no-gaps | nothing, 607 s |
| without no-gaps and the teacher gap limit | nothing, 307 s |
| without those and optional-subjects-at-the-edges | nothing, 307 s |

Strip all three and the week still does not come. So the honest statement is not
"contiguity is the blocker" but **"twenty-five classes posed as one model is
beyond us, and it is not any single rule's doing"**.

That has a consequence worth being plain about: construct-then-repair, which the
literature recommends and which we were about to build, needs something to
repair, and there is no relaxation of this problem that hands us a starting
timetable at twenty-five classes. The only thing that produces anything at this
size is the decomposition — a day at a time, one to three seconds a day.

Which puts all the remaining weight on the planner being right about what a
week can look like, and none on finding a cleverer way to search a model we
cannot even get started.

## Three bugs, one mistake

Worth collecting, because they are the same error wearing three hats, and all
three were invisible while the problem was small.

  1. **Paring a failure down** read anything that was not a timetable as proof
     that the lessons still clashed. A probe that runs out of time has proved
     nothing.
  2. **The planner's verdict** said "there is no week" in the same words
     whether it had shown that or merely run out of time.
  3. **Carrying a cut across the week** asked whether anyone involved keeps
     different hours on different days — but a lesson standing for a class's
     share of a cross-class block carries no teacher, so for two quarrels in
     five the question was put to nobody and came back no.

Each one claims more than the evidence supports, and each one makes the search
*look* better while making it wrong: fewer probes, a confident answer, a rule
that binds five days instead of one. A search that learns from its own failures
has to be strict about what counts as a failure, because its mistakes compound
instead of showing up.

The third was found by an impossible measurement rather than by reading the
code. A wider planner window exhausted in fifteen corrections where a narrower
one lasted a hundred and thirty, and a strictly larger space of weeks cannot
run out sooner than a smaller one. When a number cannot be true, it is a bug
and not a discovery.

## Twenty classes with nothing taken on trust

With every rule now proved on the day it is applied to — the loop tries the
other four days rather than arguing about whose absences could matter — twenty
classes still proves out, after ten plans and fifty rules.

Every obstacle turned out to hold on all five days, so the argument the checking
replaced had in fact been right. The checking stays: it was right twice and
wrong twice, and there is no way to tell which from the inside.

Ten true facts emptying the planner is a strong statement. It means either the
planner's picture of a week is far narrower than a week really is, or there is
no week. Only one rule in the planner is a candidate for the first — a class's
daily occupancy, guessed as the longer of its two halves plus the whole-class
hours, and held within a couple of hours of its even share. Everything else it
believes, the placement believes too.

So the question is now a single experiment rather than an argument: take the
window away, leave the facts, and see whether twenty classes finishes. If it
does, the shape of the week was the thing standing in the way all along. If it
does not, then the facts are binding, and what we have proved is something about
this school rather than about our solver — which would be worth knowing before
writing another line of it.

## An open bug, stated precisely

The loop can prove that a week does not exist for an instance where it has
itself built one. That is not a slow search or a bad heuristic, it is a wrong
answer, and it is the thing to fix before any other work on this.

**Reproduction.** Fifteen classes, even shape, an hour of give:

| rules are kept | result |
|---|---|
| only on the day they were proved on (`--one-day`, now the default) | **a timetable**, 59 and 73 plans on two runs |
| on every day they were checked against (`--all-days`) | **proved impossible** |

A rule is a statement that some lessons cannot share a day, established by
putting exactly those lessons on that day and nothing else, and being told it
cannot be done. A statement of that kind cannot remove a timetable that exists.
Adding more of them cannot turn a soluble problem insoluble. So one of them is
not what it claims to be.

**What has been ruled out.** The rule is proved on each day it is applied to,
not argued for — that was a real bug and fixing it did not fix this. A class's
share of a cross-class block no longer generalises to another block's — also a
real bug, also not this. Every day in this data is interchangeable, because the
school as imported has no teacher unavailability at all, so a rule proved on
Monday is a rule about Thursday too.

**Where it went.** It stopped reproducing. Three runs of the configuration that
had proved a solvable week impossible now build one — 141, 50 and 219 plans —
and the guard added in between, against a quarrel that names the same kind of
lesson twice, never once fired in any of them. So the guard is correct and is
not demonstrably what changed; either those runs simply did not meet the case
it catches, or something else did it. A loop this nondeterministic does not
offer a way to tell, and three successes are not a proof of soundness. The
reproduction below is kept for whoever meets it again.

**Where to look next.** Not by reasoning — that has now produced three wrong
answers in a row, two of which were real bugs that turned out not to be this
one, and a third about lesson kinds that share a class, subject and audience.
That third is wrong on inspection: a rule containing two such kinds is weaker
than intended rather than stronger, because another rule already keeps them
apart, so it forbids nothing instead of forbidding too much.

What would settle it is evidence rather than argument. Keep a timetable the
loop has built, and then run the loop again checking every rule against it as
the rule is issued. The first rule that the known-good week breaks is the
faulty one, and it can be read directly: which lessons, which day, and what the
probe claimed about them. That is a small piece of work and it ends the hunt,
where another round of reading the code probably does not.

# Addendum: the most expensive thing we believed was never asked for

Sixteen hard rules, and the planner was asked which of them she had actually
said. One she did not recognise at all: *a class's day starts at the first
hour*. It went into the requirements during the first pass, from the
conversation rather than from her, and has been load-bearing ever since.

In the solver it was not even a rule of its own. A class's day was stated as
"the slots below its own length", which says two things at once — unbroken, and
beginning at zero — so there has never been a way to test one without the
other. Told apart, with the first kept and the second lifted:

| | before | after |
|---|---|---|
| **twenty classes** | never, by any method | **a complete week, all 943 blocks, 71 plans** |

Twenty classes is the size that has defeated every approach in this document:
the whole week at once, the shape, the day at a time, the day-pair repair, the
loop. It falls out in about twenty minutes once a class is allowed to come in
at the second hour.

Which is worth saying plainly. Weeks of measurement went into making the search
cleverer, and what was actually in the way was a sentence nobody had checked.
The school's timetable has classes starting late — this is an ordinary thing in
a Polish liceum — and we had forbidden it without ever being told to.

The whole-week model is unmoved: twenty-five classes posed in one piece is
still nothing after ten minutes, with or without the rule. So the decomposition
is still doing the work; the rule was what the decomposition kept running into.

## Twenty-five, and a rule that got harder when another got easier

The whole school does not follow twenty through. Two hundred and thirty-three
plans, every one of them failing on the first day, with quarrels of about ten
lessons spanning a pair of classes each time.

There is a reason to suspect a particular rule, and it is a rule that *became*
harder when the start-of-day rule was lifted. An optional subject must be the
first or the last thing a class does. A cross-class block must happen at the
same hour for every class taking it. Put those together for the ethics block
that seven classes share, and while every class began its day at the first
hour, "first" meant the same hour for all of them — so the block went there and
that was that. Allow classes to come in late and "first" is a different hour for
each, and seven of them have to agree on one.

So lifting the rule that stopped twenty classes may be what stops twenty-five.
That is a pleasing shape for a problem to have and it is only a hypothesis; the
measurement that settles it is to lift optional-subjects-at-the-edges as well
and see whether the first day goes in.

## A day of the whole school is not the problem

Worth pinning down before chasing the planner any further. One day, all
twenty-five classes, both unconfirmed rules lifted, the solver free to choose
which lessons fall on it:

    25 oddziałów, 1175 bloków, 1x8 slotów
    OPTIMAL w 7,1 s

Seven seconds, proved best. So nothing about a day of this school is hard. What
fails, two hundred and thirty-three times over, is the particular set of lessons
the planner chooses to put on one — and that is a far better problem than a day
that cannot be laid out, because it is a question of choosing well rather than
of whether a choice exists.

# The bug under all the others: a question only some rules can be asked

The loop pares a failure down by asking, over and over, whether a handful of
lessons can still not share a day — with those lessons on it and nothing else.
That is a fair question for a rule which adding lessons can only make harder.
Two of ours are the other way about:

  - **a teacher's gap limit.** Take her other classes off the day and she sits
    idle through the middle of it. In a real day she is teaching somebody else.
  - **a class's day being unbroken.** Take a class's other lessons away and its
    morning acquires the holes they were filling.

So quarrels were being proved by gaps that existed only because the probe had
emptied the day, and the rules drawn from them forbade weeks that work. The
planner obeyed them, and eventually reported that no week existed — which it
did, for instances where this loop had built one.

The clearest case, found by asking the solver which rule each small quarrel was
about rather than by reading code: five lessons of 1H, three whole-class hours
and both halves of one English lesson. The halves share a teacher, so they
cannot be taught at once, so they sit at either end of the day — and with the
other three lessons removed, that teacher has three free hours between them.
Nothing about those five lessons is impossible. The probe made it so.

**Fixing it made the loop faster, not slower.**

| | before | after |
|---|---|---|
| fifteen classes | 50 to 219 plans | **11** |
| twenty classes | 71 plans | **25** |

Cores come out at two dozen lessons rather than a handful, because paring stops
where the evidence does. And the loop converges in a fraction of the rounds,
because it is no longer sending the planner away from weeks that were never
excluded. Four earlier attempts at this bug each found something real — a
timeout read as a proof, a verdict that conflated the two, a cross-class block
followed to the wrong teacher, a quarrel that named one kind twice — and none
of them was this.

The lesson generalises past this program. **A search that learns from its own
failures must only ask questions its answers are valid for.** Subset probing is
sound for constraints that adding work can only tighten, and quietly wrong for
anything that empty space can violate — gaps, idleness, balance, anything whose
breach is an absence rather than a collision.

## Where it stands

| | |
|---|---|
| fifteen classes | **a complete week, 11 plans** |
| twenty classes | **a complete week, 943 blocks, 25 plans** |
| twenty-five classes | six hundred and eighty-seven plans, none past the first day |
| one day of twenty-five classes, solver choosing | **proved best in 2.7 s** |

Those last two lines together say what is left. There is nothing hard about a
day of this school. What the planner cannot do is choose which lessons go on
one — six hundred and eighty-seven attempts, three and a half thousand rules
learned, and it never found a first day that could be laid out.

A loop that learns and does not converge is being told true things that do not
add up to the thing it needs. The planner decides by counting, and only one of
its beliefs is a guess rather than a copy of a rule the placement enforces:
what a class's day costs it, taken as the whole-class hours plus the longer of
its two halves, plus an hour wherever one teacher has both. That formula is
right when the halves line up and low when they do not — and it is low in
exactly the case this school has, where a shared teacher forces them apart.
Underestimating what a day costs is how a planner packs one too full, over and
over, in six hundred and eighty-seven different ways.

So the next thing to measure is not the search. It is whether the planner's
arithmetic for a day agrees with what the placement then finds, class by class,
on a day it has just proposed. Where they disagree is where the next rule is.

# The number that settles the argument

The planner has used a timetabling program that solved this school — all
twenty-five classes, with every rule in this document including optional
subjects at the edges of shared days — in **under ten minutes**. On cloud
hardware, likely stronger than this laptop.

Call the hardware worth five times. It does not matter. We do not produce a
timetable for twenty-five classes slowly; we do not produce one at all, after
six hundred and eighty-seven attempts and three and a half thousand learned
rules. The gap between ten minutes and never is not a hardware gap, and no
amount of tuning closes it.

So the conclusion that has been available since the literature was read, and
has been dodged twice since, has to be taken: **the approach is wrong for this
problem.** Not the model, not the decomposition, not the cut machinery — all of
those are sound now and none of them is the thing standing between us and a
timetable.

## What the working programs actually do

Every program that solves real schools at this size — FET, which is open source
and documented, and by every indication the commercial ones too — builds a
timetable by **construction and repair**, not by search over a declarative
model. Sort the lessons hardest-first. Place each one somewhere legal. When
nothing is legal, take out whatever is in the way, put this lesson there, and
re-place what was displaced, recursively. Restart with different randomness
when that runs out of road. FET quotes five to twenty minutes for a difficult
timetable and credits the hardest-first ordering with a tenfold speedup.

Against that, the measurements here say something encouraging. A day of this
school is laid out, proved optimal, in **2.7 seconds**. Five of those is
fourteen seconds. The placement is not slow and never was. What is missing is
the thing a constructive heuristic does and a counting planner cannot: **choose
which lessons go where, and change its mind locally when that fails.**

Our loop changes its mind globally — it throws away the whole week and plans
again — which is why six hundred and eighty-seven attempts teach it so little.
Recursive swapping changes its mind about one lesson and the handful in its
way, which is why it converges.

## What this costs to find out

Nothing already built is wasted. The day placement is the thing a heuristic
would call to finish each day exactly, the rule set and its model are shared,
the validation is shared, and the loop's diagnostics — which rule a quarrel is
about, what the quarrel is made of — stay useful whatever fills them. What is
being retired is the planner's claim to decide the week by counting.
