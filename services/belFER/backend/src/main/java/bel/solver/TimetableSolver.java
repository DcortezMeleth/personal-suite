package bel.solver;

import bel.solver.TimetableProblem.Audience;
import bel.solver.TimetableProblem.Lesson;
import bel.solver.TimetableProblem.Rule;
import com.google.ortools.Loader;
import com.google.ortools.sat.BoolVar;
import com.google.ortools.sat.CpModel;
import com.google.ortools.sat.CpSolver;
import com.google.ortools.sat.CpSolverStatus;
import com.google.ortools.sat.IntVar;
import com.google.ortools.sat.LinearExpr;
import com.google.ortools.sat.LinearExprBuilder;
import com.google.ortools.sat.Literal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The timetable as a CP-SAT model.
 *
 * Time-indexed: every lesson gets one boolean per legal starting slot and
 * exactly one of them is true. Everything else is a sum over those booleans —
 * "this class is busy here", "this teacher is busy here" — which is what makes
 * the awkward rules ordinary. Both halves of a class being busy together is one
 * equality between two sums; a teacher's working days, daily cap and run of
 * free periods all read off the same per-slot booleans rather than each needing
 * a different encoding.
 *
 * Rooms are counted rather than chosen — see {@link #assignRooms} for why.
 *
 * The encoding is the one measured in services/belFER/solver-poc against the
 * school's real rules at its real size; see docs/SOLVER_EVALUATION.md.
 */
public final class TimetableSolver {

    private static boolean nativeLibrariesLoaded;

    private static synchronized void loadNatives() {
        if (!nativeLibrariesLoaded) {
            Loader.loadNativeLibraries();
            nativeLibrariesLoaded = true;
        }
    }

    public static TimetableSolution solve(TimetableProblem problem) {
        loadNatives();
        long began = System.currentTimeMillis();

        List<Lesson> lessons = problem.lessons();
        int n = lessons.size();
        int slots = problem.days() * problem.slotsPerDay();
        if (n == 0)
            return new TimetableSolution(TimetableSolution.Status.OPTIMAL, List.of(), 0, 0, "");

        CpModel model = new CpModel();
        Map<String, TimetableProblem.Teacher> teachers = new LinkedHashMap<>();
        for (TimetableProblem.Teacher t : problem.teachers()) teachers.put(t.id(), t);

        boolean[] books = resourceBookers(lessons);

        // ── Start booleans. Two rules live here rather than as constraints,
        //    because "never offer this value" is cheaper than "forbid it": a
        //    block that would run past the end of its day, and a slot either of
        //    its teachers is away for.
        BoolVar[][] startsAt = new BoolVar[n][slots];
        IntVar[] start = new IntVar[n];
        BoolVar[] isPlaced = new BoolVar[n];
        List<Literal> assumptions = new ArrayList<>();
        Map<Integer, String> blamed = new HashMap<>();
        for (int i = 0; i < n; i++) {
            Lesson l = lessons.get(i);
            List<Literal> options = new ArrayList<>();
            LinearExprBuilder value = LinearExpr.newBuilder();
            for (int s = 0; s < slots; s++) {
                if (position(problem, s) + l.length() > problem.slotsPerDay()) continue;
                if (awayDuring(problem, teachers, l, s)) continue;
                startsAt[i][s] = model.newBoolVar("s" + i + "_" + s);
                options.add(startsAt[i][s]);
                value.addTerm(startsAt[i][s], s);
            }
            if (options.isEmpty() && !problem.settings().partial())
                return impossible(began, "nie ma slotu, w którym lekcja mogłaby się odbyć");
            // Filling one day at a time means most lessons belong to another
            // day, so "nowhere today" has to be an available answer. What
            // stops that answer being chosen for everything is the shape,
            // which says how many hours the class has today.
            if (problem.settings().partial() || problem.settings().explain()) {
                isPlaced[i] = model.newBoolVar("placed" + i);
                model.addAtMostOne(options.toArray(new Literal[0]));
                model.addEquality(LinearExpr.sum(options.toArray(new Literal[0])), isPlaced[i]);
                if (problem.settings().explain()) {
                    // Everything still has to be placed, but it is placed
                    // under something the solver may point at afterwards.
                    BoolVar blame = model.newBoolVar("must" + i);
                    model.addImplication(blame, isPlaced[i]);
                    assumptions.add(blame);
                    blamed.put(blame.getIndex(), lessons.get(i).id());
                }
            } else {
                model.addExactlyOne(options.toArray(new Literal[0]));
            }
            start[i] = model.newIntVar(0, slots - 1, "start" + i);
            model.addEquality(start[i], value.build());
        }

        // occupies[i][s]: lesson i is running in slot s. With exactly one start
        // true this is a plain sum over the starts that would cover s.
        LinearExprBuilder[][] occupies = new LinearExprBuilder[n][slots];
        for (int i = 0; i < n; i++) {
            Lesson l = lessons.get(i);
            for (int s = 0; s < slots; s++) {
                LinearExprBuilder covering = LinearExpr.newBuilder();
                for (int d = Math.max(0, s - l.length() + 1); d <= s; d++)
                    if (startsAt[i][d] != null) covering.add(startsAt[i][d]);
                occupies[i][s] = covering;
            }
        }

        // ── A class is in one place, and its halves are normally busy together.
        //
        //    A whole-class lesson counts against both halves; a half counts
        //    against only its own, so capping each at one is the "never
        //    double-booked" rule. Keeping the two equal is the "both halves
        //    busy" rule, and it is what makes the language swap legal — Gr1
        //    sitting angielski while Gr2 sits niemiecki satisfies it exactly as
        //    a shared subject would.
        //
        //    They are not forced equal, though. One teacher sometimes takes
        //    both halves of a split and cannot teach them at once, which leaves
        //    the other half with nothing for that hour. Rare and unwanted, so
        //    every such slot is paid for in the objective — and because each
        //    half's day has to be unbroken, the hours it misses can only fall
        //    at the start or the end, where that half comes in later or goes
        //    home earlier rather than sitting through a free period.
        List<String> classIds = distinct(lessons);
        Map<String, BoolVar[]> classBusy = new LinkedHashMap<>();
        Map<String, BoolVar[][]> halfBusy = new LinkedHashMap<>();
        List<IntVar> halvesApart = new ArrayList<>();
        for (String classId : classIds) {
            BoolVar[] busy = new BoolVar[slots];
            BoolVar[][] halves = new BoolVar[2][slots];
            for (int s = 0; s < slots; s++) {
                LinearExprBuilder[] half = { LinearExpr.newBuilder(), LinearExpr.newBuilder() };
                for (int i = 0; i < n; i++) {
                    Lesson l = lessons.get(i);
                    if (!classId.equals(l.classId())) continue;
                    if (l.audience() != Audience.GROUP_2) half[0].add(occupies[i][s].build());
                    if (l.audience() != Audience.GROUP_1) half[1].add(occupies[i][s].build());
                }
                for (int h = 0; h < 2; h++) {
                    halves[h][s] = model.newBoolVar("h" + h + "_" + classId + "_" + s);
                    model.addEquality(halves[h][s], half[h].build());
                }

                IntVar apart = model.newIntVar(0, 1, "apart_" + classId + "_" + s);
                model.addGreaterOrEqual(apart, LinearExpr.newBuilder()
                    .add(halves[0][s]).addTerm(halves[1][s], -1).build());
                model.addGreaterOrEqual(apart, LinearExpr.newBuilder()
                    .add(halves[1][s]).addTerm(halves[0][s], -1).build());
                halvesApart.add(apart);

                busy[s] = model.newBoolVar("cb_" + classId + "_" + s);
                model.addImplication(halves[0][s], busy[s]);
                model.addImplication(halves[1][s], busy[s]);
                model.addBoolOr(new Literal[] { busy[s].not(), halves[0][s], halves[1][s] });
            }
            classBusy.put(classId, busy);
            halfBusy.put(classId, halves);
        }

        // ── A teacher is in one place, counting hours spent supporting someone
        //    else's lesson exactly like their own.
        Map<String, BoolVar[]> teacherBusy = new LinkedHashMap<>();
        for (String teacherId : teachers.keySet()) {
            BoolVar[] busy = new BoolVar[slots];
            for (int s = 0; s < slots; s++) {
                LinearExprBuilder here = LinearExpr.newBuilder();
                for (int i = 0; i < n; i++) {
                    if (!books[i]) continue;   // a merged group is taught once
                    Lesson l = lessons.get(i);
                    if (teacherId.equals(l.teacherId()) || teacherId.equals(l.supportTeacherId()))
                        here.add(occupies[i][s].build());
                }
                busy[s] = model.newBoolVar("tb_" + teacherId + "_" + s);
                model.addEquality(busy[s], here.build());
            }
            teacherBusy.put(teacherId, busy);
        }

        // ── Rooms as capacity, not as a choice.
        //
        //    Two rooms of the same kind are interchangeable, so which one a
        //    lesson gets is not worth searching over — it only has to be true
        //    that enough of the right kind are free. Counted per slot this is a
        //    few hundred constraints; written as "an optional interval per
        //    lesson per candidate room" it was 34,752 intervals in an 85,000
        //    variable model that spent its entire budget in presolve. The rooms
        //    themselves are handed out afterwards, where it is a matching.
        Map<String, Integer> roomsOfKind = new LinkedHashMap<>();
        Set<String> countedRooms = new HashSet<>();
        int totalRooms = 0;
        for (TimetableProblem.Room r : problem.rooms()) {
            if (countedRooms.add(r.id())) totalRooms++;
            if (r.kindId() != null) roomsOfKind.merge(r.kindId(), 1, Integer::sum);
        }
        for (int s = 0; s < slots; s++) {
            LinearExprBuilder anyRoom = LinearExpr.newBuilder();
            Map<String, LinearExprBuilder> byKind = new LinkedHashMap<>();
            for (int i = 0; i < n; i++) {
                if (!books[i]) continue;
                anyRoom.add(occupies[i][s].build());
                String kind = problem.settings().has(Rule.ROOM_KIND)
                    ? lessons.get(i).requiredRoomKindId() : null;
                if (kind != null)
                    byKind.computeIfAbsent(kind, k -> LinearExpr.newBuilder())
                        .add(occupies[i][s].build());
            }
            if (problem.settings().has(Rule.ROOM_CAPACITY))
                model.addLessOrEqual(anyRoom.build(), totalRooms);
            for (Map.Entry<String, LinearExprBuilder> e : byKind.entrySet())
                model.addLessOrEqual(e.getValue().build(), roomsOfKind.getOrDefault(e.getKey(), 0));
        }

        // ── At most one block of a subject per day, per audience. Base and
        //    extension share a subject, so they compete for the day — which is
        //    what the requirements say they should.
        if (problem.settings().has(Rule.ONE_BLOCK_PER_SUBJECT_PER_DAY)) {
            Map<String, List<Integer>> sameSubject = new LinkedHashMap<>();
            for (int i = 0; i < n; i++) {
                Lesson l = lessons.get(i);
                // The rule is about a class's day. A lesson with no class is
                // the resource half of a cross-class group — its classes are
                // accounted for by their own occupancy lessons, and grouping
                // these by subject alone would put every WF group in the
                // school into one bucket and allow one a day between them.
                if (l.classId() == null) continue;
                sameSubject
                    .computeIfAbsent(l.classId() + "|" + l.subjectId() + "|" + l.audience(),
                        k -> new ArrayList<>())
                    .add(i);
            }
            for (List<Integer> group : sameSubject.values()) {
                if (group.size() < 2) continue;
                for (int d = 0; d < problem.days(); d++) {
                    LinearExprBuilder startingToday = LinearExpr.newBuilder();
                    boolean any = false;
                    for (int i : group)
                        for (int p = 0; p < problem.slotsPerDay(); p++) {
                            BoolVar here = startsAt[i][d * problem.slotsPerDay() + p];
                            if (here != null) { startingToday.add(here); any = true; }
                        }
                    if (any) model.addLessOrEqual(startingToday.build(), 1);
                }
            }
        }

        // ── A cross-class unit runs as one.
        Map<String, List<Integer>> units = new LinkedHashMap<>();
        for (int i = 0; i < n; i++)
            if (lessons.get(i).crossClassUnitId() != null)
                units.computeIfAbsent(lessons.get(i).crossClassUnitId(), k -> new ArrayList<>()).add(i);
        if (problem.settings().has(Rule.CROSS_CLASS_TOGETHER))
            for (List<Integer> together : units.values())
                for (int k = 1; k < together.size(); k++) {
                    int a = together.get(0), b = together.get(k);
                    if (problem.settings().partial()) {
                        // Either the whole unit runs today or none of it does.
                        model.addEquality(isPlaced[a], isPlaced[b]);
                        model.addEquality(start[a], start[b]).onlyEnforceIf(isPlaced[a]);
                    } else {
                        model.addEquality(start[a], start[b]);
                    }
                }

        // ── A class's day is a solid block starting at the first slot, and so
        //    is each half's — a student has nowhere to be during a free
        //    period. The halves may begin later or end earlier than the class
        //    does, which is what an hour without the other half buys them, but
        //    neither may have a hole in the middle.
        //
        //    Stated through the length of the day rather than slot by slot.
        //    "Busy here means busy in the slot before" is the same rule and
        //    propagates almost nothing: each slot argues only with its
        //    neighbour, and nothing argues with the day. Against a single
        //    length, fixing any one slot settles all eight.
        //
        //    It did not move the wall this rule puts the school against — see
        //    the addendum in docs/SOLVER_EVALUATION.md — so it is kept for
        //    being the clearer statement, not for being faster.
        Map<String, IntVar[]> dayLength = new LinkedHashMap<>();
        if (problem.settings().has(Rule.CLASS_DAY_CONTIGUOUS)) {
            for (Map.Entry<String, BoolVar[]> entry : classBusy.entrySet()) {
                BoolVar[] busy = entry.getValue();
                BoolVar[][] halves = halfBusy.get(entry.getKey());
                IntVar[] lengths = new IntVar[problem.days()];
                for (int d = 0; d < problem.days(); d++) {
                    int base = d * problem.slotsPerDay();
                    IntVar length = model.newIntVar(0, problem.slotsPerDay(),
                        "len_" + entry.getKey() + "_" + d);
                    // When the week's shape is already settled, say so. Every
                    // "is this class in school now" then stops being a question.
                    int[] decided = problem.shape().get(entry.getKey());
                    if (decided != null && d < decided.length) {
                        // Pinned exactly, the last day of the week has to be an
                        // exact cover of whatever the earlier days left behind,
                        // which almost never happens by luck — so every week
                        // ends up back at the whole-week problem. A day that
                        // may run an hour long or an hour short costs the
                        // shape little and gives the week somewhere to absorb
                        // the difference.
                        int slack = problem.settings().shapeSlack();
                        model.addLinearConstraint(length,
                            Math.max(0, decided[d] - slack),
                            Math.min(problem.slotsPerDay(), decided[d] + slack));
                    }
                    lengths[d] = length;
                    if (problem.settings().has(Rule.CLASS_DAY_STARTS_FIRST)) {
                        for (int p = 0; p < problem.slotsPerDay(); p++) {
                            model.addGreaterThan(length, p).onlyEnforceIf(busy[base + p]);
                            model.addLessOrEqual(length, p).onlyEnforceIf(busy[base + p].not());
                        }
                    } else {
                        // Unbroken, but not necessarily from the first hour: the
                        // class is in school across one window of the day and
                        // nowhere outside it. Costs an integer and two booleans
                        // a slot over the version that starts at zero, which is
                        // the price of letting a class come in late.
                        IntVar from = model.newIntVar(0, problem.slotsPerDay(),
                            "from_" + entry.getKey() + "_" + d);
                        IntVar until = model.newIntVar(0, problem.slotsPerDay(),
                            "until_" + entry.getKey() + "_" + d);
                        model.addEquality(
                            LinearExpr.newBuilder().add(until).addTerm(from, -1).build(), length);
                        for (int p = 0; p < problem.slotsPerDay(); p++) {
                            BoolVar here = busy[base + p];
                            model.addLessOrEqual(from, p).onlyEnforceIf(here);
                            model.addGreaterThan(until, p).onlyEnforceIf(here);
                            BoolVar early = model.newBoolVar("early_" + entry.getKey() + d + p);
                            BoolVar late = model.newBoolVar("late_" + entry.getKey() + d + p);
                            model.addGreaterThan(from, p).onlyEnforceIf(early);
                            model.addLessOrEqual(until, p).onlyEnforceIf(late);
                            model.addBoolOr(new Literal[] { here, early, late });
                        }
                    }
                    for (int h = 0; h < 2; h++)
                        atMostOneStart(model, problem, halves[h], base,
                            entry.getKey() + "_" + h + "_" + d);
                }
                // No "the days add up to the week" constraint here, and it
                // is worth saying why: the obvious figure — hours counted once
                // per split — is only right when both halves are always
                // together. Every hour one half spends alone is an extra slot
                // the class occupies, so the equality quietly forbids the one
                // arrangement the school asked for.
                dayLength.put(entry.getKey(), lengths);
            }
        }

        // ── Days are interchangeable, so most of the search is relabelling.
        //
        //    Nothing in this model tells Monday from Friday: the only
        //    day-specific thing is a teacher being elsewhere, and when nobody
        //    is, every timetable exists a hundred and twenty times over — the
        //    same plan with its days shuffled. CP-SAT looks for this itself and
        //    says so in its log, but on an instance this size it gives up part
        //    way: "some automorphisms were found, but probably not all".
        //
        //    Pinning one class's week into descending order picks a single
        //    representative of each of those hundred and twenty. Ordering on
        //    every class lexicographically breaks strictly more and was tried;
        //    it found the same timetables in the same time, so the simpler
        //    statement stands.
        //
        //    Sound only while the days really are alike, so it switches off the
        //    moment anyone is unavailable: then Monday genuinely differs from
        //    Friday and shuffling would throw away real answers.
        // A settled shape already picks one arrangement of the days, so
        // ordering them again would only argue with it.
        boolean daysAlike = problem.shape().isEmpty()
            && problem.teachers().stream().allMatch(x -> x.unavailable().isEmpty());
        if (daysAlike && !dayLength.isEmpty()) {
            IntVar[] week = dayLength.values().iterator().next();
            for (int d = 1; d < week.length; d++)
                model.addGreaterOrEqual(week[d - 1], week[d]);
        }

        // ── Religia and etyka sit at an end of the day: at the first slot, or
        //    with the next one free, which given the rule above means last.
        for (int i = 0; i < n; i++) {
            Lesson l = lessons.get(i);
            if (!l.optional() || !problem.settings().has(Rule.OPTIONAL_AT_DAY_EDGE)) continue;
            BoolVar[] busy = classBusy.get(l.classId());
            if (busy == null) continue;
            for (int s = 0; s < slots; s++) {
                if (startsAt[i][s] == null || position(problem, s) == 0) continue;
                if (position(problem, s) + l.length() < problem.slotsPerDay())
                    model.addEquality(busy[s + l.length()], 0).onlyEnforceIf(startsAt[i][s]);
            }
        }

        // ── What is asked of a teacher: working days, daily cap, and no long
        //    run of free periods between two lessons.
        for (Map.Entry<String, BoolVar[]> entry : teacherBusy.entrySet()) {
            TimetableProblem.Teacher teacher = teachers.get(entry.getKey());
            BoolVar[] busy = entry.getValue();
            List<Literal> daysWorked = new ArrayList<>();
            for (int d = 0; d < problem.days(); d++) {
                int base = d * problem.slotsPerDay();
                LinearExprBuilder today = LinearExpr.newBuilder();
                for (int p = 0; p < problem.slotsPerDay(); p++) today.add(busy[base + p]);
                if (problem.settings().has(Rule.TEACHER_LESSONS_PER_DAY))
                    model.addLessOrEqual(today.build(), teacher.maxLessonsPerDay());

                BoolVar works = model.newBoolVar("w_" + teacher.id() + "_" + d);
                model.addGreaterThan(today.build(), 0).onlyEnforceIf(works);
                model.addEquality(today.build(), 0).onlyEnforceIf(works.not());
                daysWorked.add(works);

                if (problem.settings().has(Rule.TEACHER_GAP_LIMIT))
                    addGapLimit(model, problem, busy, base, teacher.id(), d);
            }
            if (problem.settings().has(Rule.TEACHER_WORKING_DAYS))
                model.addLessOrEqual(LinearExpr.sum(daysWorked.toArray(new Literal[0])),
                    teacher.maxWorkingDays());
        }

        // ── The optimisation key, as weighted per-unit penalties. ADR 0002 on
        //    why this is not "longest day minus shortest": said that way the
        //    same preference costs twentyfold and stops proving anything.
        List<IntVar> penalties = new ArrayList<>();
        for (Map.Entry<String, BoolVar[]> entry : classBusy.entrySet()) {
            // What an even week looks like for this class in particular. Using
            // one figure for the whole school means the busiest class sets it,
            // every other class sits comfortably under it, and the objective
            // stops saying anything about them — which leaves the solver with
            // nothing to improve and no reason to balance anybody's days.
            int hours = 0;
            for (int i = 0; i < n; i++) {
                Lesson l = lessons.get(i);
                if (!entry.getKey().equals(l.classId())) continue;
                if (l.audience() == Audience.GROUP_2) continue;  // shares its slot
                hours += l.length();
            }
            int target = problem.settings().targetDayLength() > 0
                ? problem.settings().targetDayLength()
                : Math.min(problem.slotsPerDay(),
                    (hours + problem.days() - 1) / problem.days());

            BoolVar[] busy = entry.getValue();
            IntVar[] known = dayLength.get(entry.getKey());
            for (int d = 0; d < problem.days(); d++) {
                // Where contiguity applies the length is already a variable;
                // summing the slots again would only restate it.
                LinearExprBuilder length = LinearExpr.newBuilder();
                if (known != null) length.add(known[d]);
                else for (int p = 0; p < problem.slotsPerDay(); p++)
                    length.add(busy[d * problem.slotsPerDay() + p]);
                IntVar over = model.newIntVar(0, problem.slotsPerDay(), "over");
                model.addGreaterOrEqual(over,
                    LinearExpr.newBuilder().add(length.build()).add(-target).build());
                penalties.add(over);
            }
        }

        // Always minimise something, even when every weight is zero: with an
        // objective CP-SAT puts workers on large-neighbourhood search, and at
        // school size those are what find the first feasible solution at all.
        List<IntVar> weighted = new ArrayList<>(penalties);
        List<Long> weights = new ArrayList<>();
        for (int i = 0; i < penalties.size(); i++)
            weights.add((long) problem.settings().dayLengthWeight());
        for (IntVar apart : halvesApart) {
            weighted.add(apart);
            weights.add((long) problem.settings().halvesApartWeight());
        }
        model.minimize(LinearExpr.weightedSum(
            weighted.toArray(new IntVar[0]),
            weights.stream().mapToLong(Long::longValue).toArray()));

        if (!assumptions.isEmpty())
            model.addAssumptions(assumptions.toArray(new Literal[0]));

        CpSolver solver = new CpSolver();
        solver.getParameters().setMaxTimeInSeconds(problem.settings().timeLimitSeconds());
        if (problem.settings().workers() > 0)
            solver.getParameters().setNumWorkers(problem.settings().workers());
        // BELFER_SOLVER_LOG=1 turns on CP-SAT's own commentary. Noisy, but when
        // a run comes back with nothing to explain itself, its presolve summary
        // usually says where the model went wrong.
        solver.getParameters().setLogSearchProgress("1".equals(System.getenv("BELFER_SOLVER_LOG")));
        // No linearisation override here, though it looks like an easy win.
        // A timetable's linear relaxation is hopeless — half a lesson in half a
        // room means nothing — and CP-SAT's log appears to confirm it: every
        // solution on this school's data is reported by the subsolver named
        // "no_lp". Turning linearisation off anyway made twenty classes
        // unsolvable where the default had managed it. Which worker reports a
        // solution is not which work was needed to reach it; the linear
        // subsolvers were evidently feeding the one that got there.

        CpSolverStatus status = solver.solve(model);
        long millis = System.currentTimeMillis() - began;

        if (status != CpSolverStatus.OPTIMAL && status != CpSolverStatus.FEASIBLE) {
            boolean proven = status == CpSolverStatus.INFEASIBLE;
            // When asked to explain, a proven failure names the lessons whose
            // being here together is what cannot be done. It is usually a
            // handful out of hundreds, which is the difference between knowing
            // that a day failed and knowing something about why.
            List<String> core = new ArrayList<>();
            if (proven && !blamed.isEmpty())
                for (int index : solver.sufficientAssumptionsForInfeasibility()) {
                    String id = blamed.get(index);
                    if (id != null) core.add(id);
                }
            return new TimetableSolution(
                proven ? TimetableSolution.Status.INFEASIBLE : TimetableSolution.Status.UNKNOWN,
                List.of(), millis, 0,
                proven ? "Tych zajęć nie da się ułożyć przy obecnych regułach"
                       : "Nie znaleziono planu w wyznaczonym czasie",
                core);
        }

        List<int[]> placed = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            if (isPlaced[i] != null && solver.value(isPlaced[i]) == 0) continue;
            placed.add(new int[] { i, (int) solver.value(start[i]) });
        }
        return new TimetableSolution(
            status == CpSolverStatus.OPTIMAL
                ? TimetableSolution.Status.OPTIMAL : TimetableSolution.Status.FEASIBLE,
            assignRooms(problem, lessons, books, placed),
            millis, (long) solver.objectiveValue(), "");
    }

    /**
     * A day with no holes in it, which may begin after the first slot.
     *
     * Counting rising edges rather than forcing a prefix: a half that arrives
     * late because the other had the first lesson alone is still a day without
     * gaps, and a prefix would forbid it. One rise is a single unbroken
     * stretch; two would mean a hole between them.
     *
     * Saying it instead as a window — from here until there — reads better and
     * propagates more per decision, which is why the class's own day is stated
     * that way. It was tried here too and cost twenty classes their timetable,
     * so the halves keep the weaker statement. Where the stronger form helps
     * and where it hurts is not something to reason about from the armchair.
     */
    private static void atMostOneStart(CpModel model, TimetableProblem problem,
                                       BoolVar[] busy, int base, String name) {
        List<IntVar> rises = new ArrayList<>();
        for (int p = 0; p < problem.slotsPerDay(); p++) {
            IntVar rise = model.newIntVar(0, 1, "rise_" + name + "_" + p);
            LinearExprBuilder change = LinearExpr.newBuilder().add(busy[base + p]);
            if (p > 0) change.addTerm(busy[base + p - 1], -1);
            model.addGreaterOrEqual(rise, change.build());
            rises.add(rise);
        }
        model.addLessOrEqual(LinearExpr.sum(rises.toArray(new IntVar[0])), 1);
    }

    /**
     * Hands out the actual rooms once the timetable is known.
     *
     * By then it is a matching rather than a search: within a slot the lessons
     * that need a particular kind are seated first, since theirs is the narrow
     * choice, and the rest take what is left. The solver has already
     * guaranteed enough of each kind is free. A block keeps the room it
     * started in.
     */
    private static List<TimetableSolution.Placement> assignRooms(
            TimetableProblem problem, List<Lesson> lessons, boolean[] books, List<int[]> placed) {
        int slots = problem.days() * problem.slotsPerDay();
        Map<Integer, String> roomOf = new HashMap<>();

        for (int s = 0; s < slots; s++) {
            List<Integer> running = new ArrayList<>();
            for (int[] p : placed) {
                int i = p[0];
                if (books[i] && s >= p[1] && s < p[1] + lessons.get(i).length()) running.add(i);
            }
            Set<String> taken = new HashSet<>();
            List<Integer> needing = new ArrayList<>();
            for (int i : running) {
                String already = roomOf.get(i);
                if (already != null) taken.add(already);
                else needing.add(i);
            }
            needing.sort((a, b) -> Boolean.compare(
                lessons.get(b).requiredRoomKindId() != null,
                lessons.get(a).requiredRoomKindId() != null));
            for (int i : needing) {
                String kind = lessons.get(i).requiredRoomKindId();
                for (TimetableProblem.Room r : problem.rooms()) {
                    if (taken.contains(r.id())) continue;
                    if (kind != null && !kind.equals(r.kindId())) continue;
                    roomOf.put(i, r.id());
                    taken.add(r.id());
                    break;
                }
            }
        }

        List<TimetableSolution.Placement> out = new ArrayList<>();
        for (int[] p : placed) {
            int i = p[0];
            int booker = books[i] ? i : bookerFor(lessons, i);
            out.add(new TimetableSolution.Placement(lessons.get(i).id(), p[1], roomOf.get(booker)));
        }
        return out;
    }

    /**
     * No more than {@code maxConsecutiveTeacherGaps} free periods in a row —
     * but only between two lessons, since a teacher who simply starts late is
     * not sitting through anything.
     *
     * "Busy somewhere earlier" and "busy somewhere later" are built once per
     * position and shared by every window. Building them per window instead
     * restates what the busy grid already says thousands of times over.
     */
    private static void addGapLimit(CpModel model, TimetableProblem problem,
                                    BoolVar[] busy, int base, String teacherId, int day) {
        int perDay = problem.slotsPerDay();
        int run = problem.settings().maxConsecutiveTeacherGaps() + 1;
        if (run > perDay) return;

        BoolVar[] before = new BoolVar[perDay + 1];
        before[0] = model.newBoolVar("bf_" + teacherId + "_" + day + "_0");
        model.addEquality(before[0], 0);
        for (int p = 1; p <= perDay; p++) {
            before[p] = model.newBoolVar("bf_" + teacherId + "_" + day + "_" + p);
            model.addBoolOr(new Literal[] { before[p].not(), before[p - 1], busy[base + p - 1] });
            model.addImplication(before[p - 1], before[p]);
            model.addImplication(busy[base + p - 1], before[p]);
        }
        BoolVar[] after = new BoolVar[perDay + 1];
        after[perDay] = model.newBoolVar("af_" + teacherId + "_" + day + "_end");
        model.addEquality(after[perDay], 0);
        for (int p = perDay - 1; p >= 0; p--) {
            after[p] = model.newBoolVar("af_" + teacherId + "_" + day + "_" + p);
            model.addBoolOr(new Literal[] { after[p].not(), after[p + 1], busy[base + p] });
            model.addImplication(after[p + 1], after[p]);
            model.addImplication(busy[base + p], after[p]);
        }
        for (int p = 0; p + run <= perDay; p++) {
            List<Literal> escape = new ArrayList<>();
            for (int k = 0; k < run; k++) escape.add(busy[base + p + k]);
            escape.add(before[p].not());
            escape.add(after[p + run].not());
            model.addBoolOr(escape.toArray(new Literal[0]));
        }
    }

    /**
     * Which lessons book a teacher and a room. A merged cross-class group is
     * taught once however many classes it draws from, so only its first lesson
     * consumes those; the others record that their class is occupied.
     */
    private static boolean[] resourceBookers(List<Lesson> lessons) {
        boolean[] books = new boolean[lessons.size()];
        Set<String> seen = new HashSet<>();
        for (int i = 0; i < lessons.size(); i++) {
            String group = lessons.get(i).crossClassGroupId();
            books[i] = group == null || seen.add(group);
        }
        return books;
    }

    private static int bookerFor(List<Lesson> lessons, int index) {
        String group = lessons.get(index).crossClassGroupId();
        for (int i = 0; i < index; i++)
            if (group.equals(lessons.get(i).crossClassGroupId())) return i;
        return index;
    }

    private static boolean awayDuring(TimetableProblem problem,
                                      Map<String, TimetableProblem.Teacher> teachers,
                                      Lesson l, int start) {
        for (String id : new String[] { l.teacherId(), l.supportTeacherId() }) {
            TimetableProblem.Teacher t = id == null ? null : teachers.get(id);
            if (t == null) continue;
            for (int k = 0; k < l.length(); k++) {
                int slot = start + k;
                for (TimetableProblem.Blocked b : t.unavailable())
                    if (b.day() == slot / problem.slotsPerDay()
                        && position(problem, slot) >= b.fromPosition()
                        && position(problem, slot) <= b.toPosition()) return true;
            }
        }
        return false;
    }

    private static int position(TimetableProblem problem, int slot) {
        return slot % problem.slotsPerDay();
    }

    private static List<String> distinct(List<Lesson> lessons) {
        List<String> out = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (Lesson l : lessons)
            if (l.classId() != null && seen.add(l.classId())) out.add(l.classId());
        return out;
    }

    private static long[] filled(int value, int size) {
        long[] out = new long[size];
        java.util.Arrays.fill(out, value);
        return out;
    }

    /** A lesson with nowhere legal to go is decided before the solver runs. */
    private static TimetableSolution impossible(long began, String why) {
        return new TimetableSolution(TimetableSolution.Status.INFEASIBLE, List.of(),
            System.currentTimeMillis() - began, 0, why);
    }

    private TimetableSolver() {}
}
