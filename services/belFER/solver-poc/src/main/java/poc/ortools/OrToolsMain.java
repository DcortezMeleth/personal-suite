package poc.ortools;

import com.google.ortools.Loader;
import com.google.ortools.sat.BoolVar;
import com.google.ortools.sat.CpModel;
import com.google.ortools.sat.CpSolver;
import com.google.ortools.sat.CpSolverStatus;
import com.google.ortools.sat.IntVar;
import com.google.ortools.sat.IntervalVar;
import com.google.ortools.sat.LinearExpr;
import com.google.ortools.sat.LinearExprBuilder;
import com.google.ortools.sat.Literal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import poc.Instance;
import poc.Verifier;

/**
 * The timetable for CP-SAT, time-indexed.
 *
 * Each lesson gets one boolean per legal starting slot and exactly one of them
 * is true. Everything else is then a sum over those booleans: "the class is
 * busy here", "this teacher is busy here", "this room is taken". That is what
 * makes the awkward rules ordinary — both halves busy together is one equality
 * between two sums, and a teacher's working days, daily cap and run of free
 * periods all read off the same per-slot booleans rather than needing a
 * different encoding each.
 *
 * Rooms stay as intervals, because noOverlap propagates far better than a sum
 * would and rooms are the one resource with no shape rules attached.
 */
public final class OrToolsMain {

    /** POC_SKIP=R13,R15 drops rules, to find which one makes an instance bite. */
    private static boolean on(String rule) {
        String skip = System.getenv("POC_SKIP");
        return skip == null || !java.util.Arrays.asList(skip.split(",")).contains(rule);
    }

    public static void main(String[] args) {
        Loader.loadNativeLibraries();

        List<Instance.Lesson> spec = Instance.lessons();
        int n = spec.size();
        int slots = Instance.SLOTS;
        CpModel model = new CpModel();

        // ── Start booleans. R6 and R11 live here: a start that runs past the
        //    end of its day, or that either of its teachers is away for, is
        //    simply never offered.
        BoolVar[][] startsAt = new BoolVar[n][slots];
        IntVar[] start = new IntVar[n];
        for (int i = 0; i < n; i++) {
            Instance.Lesson l = spec.get(i);
            List<Literal> options = new ArrayList<>();
            LinearExprBuilder startValue = LinearExpr.newBuilder();
            for (int s = 0; s < slots; s++) {
                if (Instance.position(s) + l.length() > Instance.SLOTS_PER_DAY) continue;
                boolean away = false;
                for (int k = 0; k < l.length(); k++) {
                    if (Instance.unavailable(l.teacher(), s + k)) away = true;
                    if (l.supportTeacher() >= 0 && Instance.unavailable(l.supportTeacher(), s + k))
                        away = true;
                }
                if (away) continue;
                startsAt[i][s] = model.newBoolVar("s" + i + "_" + s);
                options.add(startsAt[i][s]);
                startValue.addTerm(startsAt[i][s], s);
            }
            if (options.isEmpty()) throw new IllegalStateException("lesson " + i + " has nowhere to go");
            model.addExactlyOne(options.toArray(new Literal[0]));
            start[i] = model.newIntVar(0, slots - 1, "start" + i);
            model.addEquality(start[i], startValue.build());
        }

        // occupies[i][s]: true when lesson i is running in slot s. With exactly
        // one start true, this is a plain sum over the starts that would cover s.
        LinearExprBuilder[][] occupies = new LinearExprBuilder[n][slots];
        for (int i = 0; i < n; i++) {
            Instance.Lesson l = spec.get(i);
            for (int s = 0; s < slots; s++) {
                LinearExprBuilder covering = LinearExpr.newBuilder();
                for (int d = Math.max(0, s - l.length() + 1); d <= s; d++)
                    if (startsAt[i][d] != null) covering.add(startsAt[i][d]);
                occupies[i][s] = covering;
            }
        }

        // ── R1 and R7 together. A whole-class lesson counts against both
        //    halves; a half counts against only its own. Equality between the
        //    two sums is what keeps the class's halves busy at the same time,
        //    and it is what makes the language swap legal — Gr1 angielski
        //    against Gr2 niemiecki satisfies it exactly as a shared subject does.
        BoolVar[][] classBusy = new BoolVar[Instance.CLASSES.length][slots];
        for (int c = 0; c < Instance.CLASSES.length; c++) {
            for (int s = 0; s < slots; s++) {
                LinearExprBuilder half1 = LinearExpr.newBuilder();
                LinearExprBuilder half2 = LinearExpr.newBuilder();
                for (int i = 0; i < n; i++) {
                    Instance.Lesson l = spec.get(i);
                    if (l.classIndex() != c) continue;
                    if (l.group() == Instance.WHOLE || l.group() == Instance.GROUP_1)
                        half1.add(occupies[i][s].build());
                    if (l.group() == Instance.WHOLE || l.group() == Instance.GROUP_2)
                        half2.add(occupies[i][s].build());
                }
                classBusy[c][s] = model.newBoolVar("cb" + c + "_" + s);
                model.addEquality(classBusy[c][s], half1.build());   // R1: at most one
                if (on("R7")) model.addEquality(classBusy[c][s], half2.build());
                else model.addLessOrEqual(half2.build(), 1);
            }
        }

        // ── R2 and R12: the same sum per teacher, with supporting hours in it.
        BoolVar[][] teacherBusy = new BoolVar[Instance.teacherCount()][slots];
        for (int t = 0; t < Instance.teacherCount(); t++) {
            for (int s = 0; s < slots; s++) {
                LinearExprBuilder here = LinearExpr.newBuilder();
                for (int i = 0; i < n; i++) {
                    Instance.Lesson l = spec.get(i);
                    // A merged group is taught once however many classes it
                    // draws from, so only its first lesson books the teacher.
                    if (!Instance.booksResources(spec, i)) continue;
                    if (l.teacher() == t || l.supportTeacher() == t) here.add(occupies[i][s].build());
                }
                teacherBusy[t][s] = model.newBoolVar("tb" + t + "_" + s);
                model.addEquality(teacherBusy[t][s], here.build());
            }
        }

        // ── R3 and R3b: rooms as optional intervals, one per candidate room.
        BoolVar[][] inRoom = new BoolVar[n][Instance.rooms()];
        List<List<IntervalVar>> perRoom = new ArrayList<>();
        for (int r = 0; r < Instance.rooms(); r++) perRoom.add(new ArrayList<>());
        for (int i = 0; i < n; i++) {
            Instance.Lesson l = spec.get(i);
            IntVar end = model.newIntVar(0, slots, "e" + i);
            model.addEquality(end, LinearExpr.newBuilder().add(start[i]).add(l.length()).build());
            List<Literal> choices = new ArrayList<>();
            if (!Instance.booksResources(spec, i)) continue;   // the group's room is already booked
            for (int r = 0; r < Instance.rooms(); r++) {
                if (Instance.roomKindOf(r) != l.roomKind()) continue;
                inRoom[i][r] = model.newBoolVar("in" + i + "_" + r);
                choices.add(inRoom[i][r]);
                perRoom.get(r).add(model.newOptionalIntervalVar(
                    start[i], LinearExpr.constant(l.length()), end, inRoom[i][r], "oi" + i + "_" + r));
            }
            model.addExactlyOne(choices.toArray(new Literal[0]));
        }
        for (List<IntervalVar> rs : perRoom)
            if (rs.size() > 1) model.addNoOverlap(rs.toArray(new IntervalVar[0]));

        // ── R5, stated once per audience-subject-day rather than per pair of
        //    lessons: "at most one of these may start today" is what the rule
        //    actually says, and it propagates far better than the same thing
        //    spelled out as a disjunction for every pair. Base and extension
        //    share a subject name, so they compete for the day as they should.
        if (on("R5")) {
            Map<String, List<Integer>> byAudienceSubject = new HashMap<>();
            for (int i = 0; i < n; i++) {
                Instance.Lesson l = spec.get(i);
                byAudienceSubject
                    .computeIfAbsent(l.classIndex() + "|" + l.subject() + "|" + l.group(),
                        k -> new ArrayList<>())
                    .add(i);
            }
            for (List<Integer> group : byAudienceSubject.values()) {
                if (group.size() < 2) continue;
                for (int d = 0; d < Instance.DAYS; d++) {
                    LinearExprBuilder startingToday = LinearExpr.newBuilder();
                    boolean any = false;
                    for (int i : group)
                        for (int p = 0; p < Instance.SLOTS_PER_DAY; p++) {
                            BoolVar here = startsAt[i][d * Instance.SLOTS_PER_DAY + p];
                            if (here != null) { startingToday.add(here); any = true; }
                        }
                    if (any) model.addLessOrEqual(startingToday.build(), 1);
                }
            }
        }

        // ── R8: a unit runs as one.
        for (int i = 0; i < n; i++)
            for (int j = i + 1; j < n; j++)
                if (spec.get(i).crossClassUnit() >= 0
                    && spec.get(i).crossClassUnit() == spec.get(j).crossClassUnit())
                    model.addEquality(start[i], start[j]);

        // ── R9: inside a day, a class's busy slots must be a prefix.
        if (on("R9"))
            for (int c = 0; c < Instance.CLASSES.length; c++)
                for (int d = 0; d < Instance.DAYS; d++)
                    for (int p = 1; p < Instance.SLOTS_PER_DAY; p++)
                        model.addGreaterOrEqual(classBusy[c][d * Instance.SLOTS_PER_DAY + p - 1],
                            classBusy[c][d * Instance.SLOTS_PER_DAY + p]);

        // ── R10: an optional lesson is at the start of its day, or the slot
        //    after it is free — which with R9 means it is the last.
        for (int i = 0; i < n; i++) {
            Instance.Lesson l = spec.get(i);
            if (!l.optional() || !on("R10")) continue;
            for (int s = 0; s < slots; s++) {
                if (startsAt[i][s] == null || Instance.position(s) == 0) continue;
                if (Instance.position(s) + 1 < Instance.SLOTS_PER_DAY)
                    model.addEquality(classBusy[l.classIndex()][s + 1], 0)
                        .onlyEnforceIf(startsAt[i][s]);
            }
        }

        // ── R13, R14, R15: everything asked of a teacher, off the same booleans.
        for (int t = 0; t < Instance.teacherCount(); t++) {
            Instance.TeacherLimits lim = Instance.limits(t);
            List<Literal> daysWorked = new ArrayList<>();
            for (int d = 0; d < Instance.DAYS; d++) {
                int base = d * Instance.SLOTS_PER_DAY;
                LinearExprBuilder onThisDay = LinearExpr.newBuilder();
                for (int p = 0; p < Instance.SLOTS_PER_DAY; p++) onThisDay.add(teacherBusy[t][base + p]);
                if (on("R14")) model.addLessOrEqual(onThisDay.build(), lim.maxLessonsPerDay());

                BoolVar works = model.newBoolVar("w" + t + "_" + d);
                model.addGreaterThan(onThisDay.build(), 0).onlyEnforceIf(works);
                model.addEquality(onThisDay.build(), 0).onlyEnforceIf(works.not());
                daysWorked.add(works);

                // R15: a run of more than two free periods is only a gap if
                // the teacher is there on both sides of it. "Busy somewhere
                // earlier" and "busy somewhere later" are computed once per
                // position and reused by every window that needs them —
                // building them per window instead created about twenty
                // thousand variables restating what the busy grid already said,
                // which is what made the model take hours rather than minutes.
                if (on("R15")) {
                    BoolVar[] before = new BoolVar[Instance.SLOTS_PER_DAY + 1];
                    BoolVar[] after = new BoolVar[Instance.SLOTS_PER_DAY + 1];
                    before[0] = model.newBoolVar("bf" + t + "_" + d + "_0");
                    model.addEquality(before[0], 0);
                    for (int p = 1; p <= Instance.SLOTS_PER_DAY; p++) {
                        before[p] = model.newBoolVar("bf" + t + "_" + d + "_" + p);
                        // before[p] <=> before[p-1] or busy[p-1]
                        model.addBoolOr(new Literal[] { before[p].not(), before[p - 1],
                            teacherBusy[t][base + p - 1] });
                        model.addImplication(before[p - 1], before[p]);
                        model.addImplication(teacherBusy[t][base + p - 1], before[p]);
                    }
                    after[Instance.SLOTS_PER_DAY] =
                        model.newBoolVar("af" + t + "_" + d + "_end");
                    model.addEquality(after[Instance.SLOTS_PER_DAY], 0);
                    for (int p = Instance.SLOTS_PER_DAY - 1; p >= 0; p--) {
                        after[p] = model.newBoolVar("af" + t + "_" + d + "_" + p);
                        model.addBoolOr(new Literal[] { after[p].not(), after[p + 1],
                            teacherBusy[t][base + p] });
                        model.addImplication(after[p + 1], after[p]);
                        model.addImplication(teacherBusy[t][base + p], after[p]);
                    }
                    int run = Instance.MAX_CONSECUTIVE_GAPS + 1;
                    for (int p = 0; p + run <= Instance.SLOTS_PER_DAY; p++) {
                        List<Literal> escape = new ArrayList<>();
                        for (int k = 0; k < run; k++) escape.add(teacherBusy[t][base + p + k]);
                        escape.add(before[p].not());
                        escape.add(after[p + run].not());
                        model.addBoolOr(escape.toArray(new Literal[0]));
                    }
                }
            }
            model.addLessOrEqual(LinearExpr.sum(daysWorked.toArray(new Literal[0])),
                    lim.maxWorkingDays());
        }

        // ── The optimisation key of UC-04, in two shapes, because which one
        //    is asked for turns out to matter far more than any single rule.
        //
        //    "spread" minimises each class's longest day minus its shortest.
        //    It says exactly what CT-20 says and it is punishing: max and min
        //    over a set are weak to reason about, and proving a bound on their
        //    difference is most of the work.
        //
        //    "linear" asks for the same thing as a weighted penalty — every
        //    hour a day runs past its target costs one unit — which is the
        //    shape UC-04 actually describes, and the shape a planner can tune.
        String objective = System.getenv("POC_OBJECTIVE");
        if (objective == null || objective.isBlank()) objective = "spread";
        int targetDayLength = Integer.getInteger("poc.targetDay", 6);

        List<IntVar> penalties = new ArrayList<>();
        for (int c = 0; c < Instance.CLASSES.length; c++) {
            IntVar[] lengths = new IntVar[Instance.DAYS];
            for (int d = 0; d < Instance.DAYS; d++) {
                lengths[d] = model.newIntVar(0, Instance.SLOTS_PER_DAY, "dl" + c + "_" + d);
                LinearExprBuilder sum = LinearExpr.newBuilder();
                for (int p = 0; p < Instance.SLOTS_PER_DAY; p++)
                    sum.add(classBusy[c][d * Instance.SLOTS_PER_DAY + p]);
                model.addEquality(lengths[d], sum.build());
            }
            if (objective.equals("spread")) {
                IntVar longest = model.newIntVar(0, Instance.SLOTS_PER_DAY, "max" + c);
                IntVar shortest = model.newIntVar(0, Instance.SLOTS_PER_DAY, "min" + c);
                model.addMaxEquality(longest, lengths);
                model.addMinEquality(shortest, lengths);
                IntVar spread = model.newIntVar(0, Instance.SLOTS_PER_DAY, "sp" + c);
                model.addEquality(spread,
                    LinearExpr.newBuilder().add(longest).addTerm(shortest, -1).build());
                penalties.add(spread);
            } else {
                for (int d = 0; d < Instance.DAYS; d++) {
                    IntVar over = model.newIntVar(0, Instance.SLOTS_PER_DAY, "ov" + c + "_" + d);
                    model.addGreaterOrEqual(over, LinearExpr.newBuilder()
                        .add(lengths[d]).add(-targetDayLength).build());
                    penalties.add(over);
                }
            }
        }
        if (on("SOFT")) model.minimize(LinearExpr.sum(penalties.toArray(new IntVar[0])));

        System.out.printf("  model: %d variables, %d constraints%n",
            model.model().getVariablesCount(), model.model().getConstraintsCount());

        CpSolver solver = new CpSolver();
        solver.getParameters().setMaxTimeInSeconds(args.length > 0 ? Double.parseDouble(args[0]) : 60);
        solver.getParameters().setNumWorkers(
            args.length > 1 ? Integer.parseInt(args[1]) : Runtime.getRuntime().availableProcessors());
        solver.getParameters().setLogSearchProgress(false);

        long began = System.currentTimeMillis();
        CpSolverStatus status = solver.solve(model);
        long millis = System.currentTimeMillis() - began;

        if (status != CpSolverStatus.OPTIMAL && status != CpSolverStatus.FEASIBLE) {
            System.out.printf("%n=== OR-Tools CP-SAT === %s after %d ms%n", status, millis);
            return;
        }
        List<Verifier.Placement> placements = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            int room = 0;
            int booker = i;
            if (!Instance.booksResources(spec, i))
                for (int j = 0; j < i; j++)
                    if (spec.get(j).crossClassGroup() == spec.get(i).crossClassGroup()) { booker = j; break; }
            for (int r = 0; r < Instance.rooms(); r++)
                if (inRoom[booker][r] != null && solver.booleanValue(inRoom[booker][r])) room = r;
            placements.add(new Verifier.Placement(spec.get(i).id(), (int) solver.value(start[i]), room));
        }
        System.out.printf("  solver wall time: %.1f s%n", solver.wallTime());
        Verifier.report("OR-Tools CP-SAT " + status + ", " + objective + "=" + (long) solver.objectiveValue(),
            millis, Verifier.check(spec, placements));
        String show = System.getenv("POC_PRINT");
        if (show != null && !show.isBlank())
            for (String one : show.split(","))
                Verifier.printClass(spec, placements, Integer.parseInt(one.trim()));
    }



    private OrToolsMain() {}
}
