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
