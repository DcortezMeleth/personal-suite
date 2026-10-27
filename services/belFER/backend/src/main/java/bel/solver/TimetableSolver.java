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
            if (options.isEmpty())
                return impossible(began, "nie ma slotu, w którym lekcja mogłaby się odbyć");
            model.addExactlyOne(options.toArray(new Literal[0]));
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

        // ── A class is in one place, and its halves are busy together.
        //    A whole-class lesson counts against both halves; a half counts
        //    against only its own. Equality between the two sums is both the
        //    "never double-booked" rule and the "both halves busy" rule, and it
        //    is what makes the language swap legal — Gr1 sitting angielski
        //    while Gr2 sits niemiecki satisfies it as a shared subject does.
        List<String> classIds = distinct(lessons);
        Map<String, BoolVar[]> classBusy = new LinkedHashMap<>();
        for (String classId : classIds) {
            BoolVar[] busy = new BoolVar[slots];
            for (int s = 0; s < slots; s++) {
                LinearExprBuilder half1 = LinearExpr.newBuilder();
                LinearExprBuilder half2 = LinearExpr.newBuilder();
                for (int i = 0; i < n; i++) {
                    Lesson l = lessons.get(i);
                    if (!classId.equals(l.classId())) continue;
                    if (l.audience() != Audience.GROUP_2) half1.add(occupies[i][s].build());
                    if (l.audience() != Audience.GROUP_1) half2.add(occupies[i][s].build());
                }
                busy[s] = model.newBoolVar("cb_" + classId + "_" + s);
                model.addEquality(busy[s], half1.build());
                if (problem.settings().has(Rule.HALVES_TOGETHER))
                    model.addEquality(busy[s], half2.build());
                else
                    model.addLessOrEqual(half2.build(), 1);
            }
            classBusy.put(classId, busy);
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
                for (int k = 1; k < together.size(); k++)
                    model.addEquality(start[together.get(0)], start[together.get(k)]);

        // ── A class's day is a solid block starting at the first slot.
        if (!problem.settings().allowClassGaps() && problem.settings().has(Rule.CLASS_DAY_CONTIGUOUS))
            for (BoolVar[] busy : classBusy.values())
                for (int d = 0; d < problem.days(); d++)
                    for (int p = 1; p < problem.slotsPerDay(); p++)
                        model.addGreaterOrEqual(busy[d * problem.slotsPerDay() + p - 1],
                            busy[d * problem.slotsPerDay() + p]);

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
        int target = problem.settings().targetDayLength();
        for (BoolVar[] busy : classBusy.values())
            for (int d = 0; d < problem.days(); d++) {
                LinearExprBuilder length = LinearExpr.newBuilder();
                for (int p = 0; p < problem.slotsPerDay(); p++)
                    length.add(busy[d * problem.slotsPerDay() + p]);
                IntVar over = model.newIntVar(0, problem.slotsPerDay(), "over");
                model.addGreaterOrEqual(over,
                    LinearExpr.newBuilder().add(length.build()).add(-target).build());
                penalties.add(over);
            }
        // Always minimise something, even when every weight is zero: with an
        // objective CP-SAT puts workers on large-neighbourhood search, and at
        // school size those are what find the first feasible solution at all.
        model.minimize(LinearExpr.weightedSum(
            penalties.toArray(new IntVar[0]),
            filled(problem.settings().dayLengthWeight(), penalties.size())));

        CpSolver solver = new CpSolver();
        solver.getParameters().setMaxTimeInSeconds(problem.settings().timeLimitSeconds());
        if (problem.settings().workers() > 0)
            solver.getParameters().setNumWorkers(problem.settings().workers());
        // BELFER_SOLVER_LOG=1 turns on CP-SAT's own commentary. Noisy, but when
        // a run comes back with nothing to explain itself, its presolve summary
        // usually says where the model went wrong.
        solver.getParameters().setLogSearchProgress("1".equals(System.getenv("BELFER_SOLVER_LOG")));

        CpSolverStatus status = solver.solve(model);
        long millis = System.currentTimeMillis() - began;

        if (status != CpSolverStatus.OPTIMAL && status != CpSolverStatus.FEASIBLE) {
            boolean proven = status == CpSolverStatus.INFEASIBLE;
            return new TimetableSolution(
                proven ? TimetableSolution.Status.INFEASIBLE : TimetableSolution.Status.UNKNOWN,
                List.of(), millis, 0,
                proven ? "Tych zajęć nie da się ułożyć przy obecnych regułach"
                       : "Nie znaleziono planu w wyznaczonym czasie");
        }

        List<int[]> placed = new ArrayList<>();
        for (int i = 0; i < n; i++) placed.add(new int[] { i, (int) solver.value(start[i]) });
        return new TimetableSolution(
            status == CpSolverStatus.OPTIMAL
                ? TimetableSolution.Status.OPTIMAL : TimetableSolution.Status.FEASIBLE,
            assignRooms(problem, lessons, books, placed),
            millis, (long) solver.objectiveValue(), "");
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
