package bel.solver;

import bel.solver.TimetableProblem.Audience;
import bel.solver.TimetableProblem.Blocked;
import bel.solver.TimetableProblem.Lesson;
import bel.solver.TimetableProblem.Teacher;
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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Which day each lesson falls on, settled for the whole week before anything
 * is given a time.
 *
 * Filling a week a day at a time works and finishes each day in seconds, but
 * it decides each day on its own merits, and a day that is perfect by itself
 * can still be fatal: at twenty-five classes the first three days go in
 * cleanly and then no arrangement of the remaining four can finish the week —
 * which the solver proves in six seconds, long after the damage is done.
 *
 * The decision that was missing sits between the shape and the placement. A
 * class takes at most one block of a subject a day, so a week is not only so
 * many hours a day, it is a particular set of subjects a day, and that has to
 * be chosen for the whole week at once or the early days spend what the late
 * ones need.
 *
 * This is a much smaller question than the timetable, because it has no times
 * in it. There is no position, no room, no contiguity and no gap — a lesson is
 * on a day or it is not. What remains is the counting: a class's hours a day,
 * one block of a subject a day, and what a teacher can take in a day.
 */
public final class DaySpread {

    private DaySpread() {
    }

    /**
     * Subjects that were planned for a day and cannot in fact share it.
     *
     * The planner works in counts and so cannot see what makes a day
     * impossible to lay out; the placement can, and says so in a fraction of a
     * second. This is how the one tells the other, and why planning again
     * after a failure is not the same as planning again.
     *
     * In subjects rather than in lessons, because the four blocks of a class's
     * maths are interchangeable: naming the particular ones that failed
     * forbids one arrangement and leaves three equivalent ones standing, which
     * is how the same conflict comes back round after round wearing a
     * different hat.
     */
    public record Cut(int day, java.util.Set<String> subjects) {
    }

    /**
     * A week of days, or the reason there is not one.
     *
     * @param days which day each lesson falls on, or null when there is none
     * @param proven whether the absence was proved rather than merely not
     *     found in the time allowed. The difference matters: one means the
     *     rules cannot all be kept at once, the other means nothing at all,
     *     and reporting the second as the first is how a search talks itself
     *     out of an answer that was there all along.
     */
    public record Plan(Map<String, Integer> days, boolean proven) {
    }

    /**
     * What a lesson may be swapped with without changing anything.
     *
     * The four blocks of a class's maths are the same block four times over,
     * so a rule about the particular one that failed should bind all of them.
     * But a subject is not enough to go on: a class's standard and extended
     * hours are one subject and often two different teachers, and a two hour
     * block is not a one hour block. Everything that could make one block
     * harder to place than another belongs in here, or a rule learned about
     * one will be applied to another it was never true of.
     */
    public static String sameAs(Lesson lesson) {
        if (lesson.classId() == null) return lesson.id();
        // A class's share of a cross-class block is not interchangeable with
        // anything: it is chained to the groups being taught that hour, and
        // they are chained to every other class sending students. Two of them
        // can look alike in every respect named below and still belong to
        // blocks that fall on different days, so the block itself goes in the
        // key and they generalise to nothing but themselves.
        if (lesson.crossClassUnitId() != null)
            return lesson.classId() + "|" + lesson.crossClassUnitId();
        return lesson.classId() + "|" + lesson.subjectId() + "|" + lesson.audience()
            + "|" + lesson.teacherId() + "|" + lesson.supportTeacherId()
            + "|" + lesson.length() + "|" + lesson.requiredRoomKindId()
            + "|" + lesson.optional();
    }

    /**
     * @param slack how far a day may stray from its share of the class's week
     * @return the day each lesson belongs on, or null when no week works
     */


    /**
     * @param countSharedHalves whether to charge a class the extra hour it
     *     spends when one teacher takes both halves of a split. It is worth a
     *     great deal where it holds — fifteen classes go from 375 plans to 18 —
     *     and it is not always affordable: past twenty-two classes the week
     *     stops adding up with it in, so the caller tries without.
     */
    public static Plan plan(TimetableProblem problem, long seconds, int workers,
                            int slack, List<Cut> cuts, boolean countSharedHalves) {
        boolean[] proven = new boolean[] { false };
        Map<String, Integer> days =
            solve(problem, seconds, workers, slack, cuts, countSharedHalves, proven);
        return new Plan(days, proven[0]);
    }

    private static Map<String, Integer> solve(TimetableProblem problem, long seconds,
                                             int workers, int slack, List<Cut> cuts,
                                             boolean countSharedHalves, boolean[] proven) {
        Loader.loadNativeLibraries();
        CpModel model = new CpModel();
        proven[0] = false;

        List<Lesson> lessons = problem.lessons();
        int n = lessons.size();
        int days = problem.days();
        int slotsPerDay = problem.slotsPerDay();

        Map<String, Teacher> teachers = new HashMap<>();
        for (Teacher t : problem.teachers()) teachers.put(t.id(), t);

        // ── A lesson is on exactly one day. A day on which one of its
        //    teachers is away for every hour is not offered at all, which is
        //    cheaper than forbidding it afterwards.
        BoolVar[][] on = new BoolVar[n][days];
        for (int i = 0; i < n; i++) {
            List<Literal> options = new ArrayList<>();
            for (int d = 0; d < days; d++) {
                if (freeHours(teachers, lessons.get(i), d, slotsPerDay) < lessons.get(i).length())
                    continue;
                on[i][d] = model.newBoolVar("on" + i + "_" + d);
                options.add(on[i][d]);
            }
            if (options.isEmpty()) {
                // No day this lesson could be on at all: proved, and by hand.
                proven[0] = true;
                return null;
            }
            model.addExactlyOne(options.toArray(new Literal[0]));
        }

        // ── One block of a subject a day, which is the whole point of doing
        //    this up front: it is the rule that makes a day's subjects scarce.
        Map<String, List<Integer>> sameSubject = new LinkedHashMap<>();
        for (int i = 0; i < n; i++) {
            Lesson l = lessons.get(i);
            if (l.classId() == null) continue;
            sameSubject
                .computeIfAbsent(l.classId() + "|" + l.subjectId() + "|" + l.audience(),
                    k -> new ArrayList<>())
                .add(i);
        }
        for (List<Integer> group : sameSubject.values()) {
            if (group.size() < 2) continue;
            for (int d = 0; d < days; d++) {
                List<Literal> today = new ArrayList<>();
                for (int i : group) if (on[i][d] != null) today.add(on[i][d]);
                if (today.size() > 1) model.addAtMostOne(today.toArray(new Literal[0]));
            }
        }

        // ── A block of a cross-class unit runs everywhere at once, so every
        //    lesson standing for it — the taught groups and the occupancy of
        //    each class sending students — shares a day.
        Map<String, List<Integer>> units = new LinkedHashMap<>();
        for (int i = 0; i < n; i++)
            if (lessons.get(i).crossClassUnitId() != null)
                units.computeIfAbsent(lessons.get(i).crossClassUnitId(), k -> new ArrayList<>())
                    .add(i);
        for (List<Integer> together : units.values())
            for (int k = 1; k < together.size(); k++)
                for (int d = 0; d < days; d++) {
                    BoolVar a = on[together.get(0)][d];
                    BoolVar b = on[together.get(k)][d];
                    if (a == null && b == null) continue;
                    if (a == null) model.addEquality(b, 0);
                    else if (b == null) model.addEquality(a, 0);
                    else model.addEquality(a, b);
                }

        // ── How much of a class's day each day takes.
        //
        //    The two halves of a split run side by side, so a class is busy
        //    for the longer of them rather than for both — which is why this
        //    is a maximum and not a sum, and why a half left on its own costs
        //    the class a slot the other half is not in.
        List<IntVar> drift = new ArrayList<>();
        Map<String, List<Integer>> byClass = new LinkedHashMap<>();
        for (int i = 0; i < n; i++)
            if (lessons.get(i).classId() != null)
                byClass.computeIfAbsent(lessons.get(i).classId(), k -> new ArrayList<>()).add(i);

        Map<String, List<String>> bothHalves = new LinkedHashMap<>();
        for (Map.Entry<String, List<Integer>> cls : byClass.entrySet()) {
            Map<Audience, java.util.Set<String>> teaching = new LinkedHashMap<>();
            for (int i : cls.getValue()) {
                Lesson l = lessons.get(i);
                if (l.teacherId() == null) continue;
                teaching.computeIfAbsent(l.audience(), k -> new java.util.LinkedHashSet<>())
                    .add(l.teacherId());
            }
            java.util.Set<String> first = teaching.getOrDefault(Audience.GROUP_1, java.util.Set.of());
            java.util.Set<String> second = teaching.getOrDefault(Audience.GROUP_2, java.util.Set.of());
            List<String> shared = new ArrayList<>(first);
            shared.retainAll(second);
            if (!shared.isEmpty()) bothHalves.put(cls.getKey(), shared);
        }

        for (Map.Entry<String, List<Integer>> cls : byClass.entrySet()) {
            int[] wanted = problem.shape().get(cls.getKey());
            if (wanted == null) continue;
            for (int d = 0; d < days; d++) {
                LinearExprBuilder whole = LinearExpr.newBuilder();
                LinearExprBuilder first = LinearExpr.newBuilder();
                LinearExprBuilder second = LinearExpr.newBuilder();
                for (int i : cls.getValue()) {
                    if (on[i][d] == null) continue;
                    Lesson l = lessons.get(i);
                    if (l.audience() == Audience.GROUP_1) first.addTerm(on[i][d], l.length());
                    else if (l.audience() == Audience.GROUP_2) second.addTerm(on[i][d], l.length());
                    else whole.addTerm(on[i][d], l.length());
                }
                // The two halves of a split sit in the same slots, so a day
                // owes them the same number of hours. Taking the longer of the
                // two instead says nothing about whether they can be made to
                // line up, and a day whose halves cannot line up is a day with
                // a hole in it — which is the one thing a class's day may not
                // have.
                //
                // They are allowed to come apart, because one teacher
                // sometimes takes both halves and cannot teach them at once,
                // but it costs: every hour of daylight between them is an hour
                // one half sits out.
                IntVar halves = model.newIntVar(0, slotsPerDay, "half_" + cls.getKey() + "_" + d);
                model.addMaxEquality(halves,
                    new LinearExpr[] { first.build(), second.build() });
                IntVar apart = model.newIntVar(0, slotsPerDay, "apart_" + cls.getKey() + "_" + d);
                model.addEquality(
                    LinearExpr.newBuilder().add(apart)
                        .add(first.build()).add(second.build()).build(),
                    LinearExpr.newBuilder().addTerm(halves, 2).build());
                // addEquality above gives 2*max - g1 - g2, which is |g1 - g2|.
                drift.add(apart);

                // One teacher sometimes takes both halves of a split, and
                // cannot teach them at once — so that class does not get the
                // two hours for the price of one that a split normally gets.
                // It is rare, and it is sixty per cent of everything the
                // placement has had to send back: the plan kept promising a
                // day one hour shorter than it could possibly be.
                LinearExprBuilder alone = LinearExpr.newBuilder();
                for (String teacher : countSharedHalves
                        ? bothHalves.getOrDefault(cls.getKey(), List.of())
                        : List.<String>of()) {
                    BoolVar first1 = anyOn(model, on, d, cls.getValue(), lessons,
                        teacher, Audience.GROUP_1, "g1_" + cls.getKey() + "_" + teacher + "_" + d);
                    BoolVar second1 = anyOn(model, on, d, cls.getValue(), lessons,
                        teacher, Audience.GROUP_2, "g2_" + cls.getKey() + "_" + teacher + "_" + d);
                    if (first1 == null || second1 == null) continue;
                    BoolVar solo = model.newBoolVar(
                        "solo_" + cls.getKey() + "_" + teacher + "_" + d);
                    model.addMinEquality(solo, new Literal[] { first1, second1 });
                    alone.addTerm(solo, 1);
                }

                int target = d < wanted.length ? wanted[d] : 0;
                IntVar busy = model.newIntVar(
                    Math.max(0, target - slack), Math.min(slotsPerDay, target + slack),
                    "busy_" + cls.getKey() + "_" + d);
                model.addEquality(busy,
                    LinearExpr.newBuilder().add(whole.build()).add(halves)
                        .add(alone.build()).build());
            }
        }

        // ── A day has two edges, so it can hold two optional subjects and no
        //    more. Religia and etyka have to be the first or the last thing a
        //    class does, and counting is enough to see that a third one has
        //    nowhere to go.
        for (Map.Entry<String, List<Integer>> cls : byClass.entrySet()) {
            List<Integer> spare = new ArrayList<>();
            for (int i : cls.getValue()) if (lessons.get(i).optional()) spare.add(i);
            if (spare.size() < 3) continue;
            for (int d = 0; d < days; d++) {
                List<Literal> today = new ArrayList<>();
                for (int i : spare) if (on[i][d] != null) today.add(on[i][d]);
                if (today.size() > 2)
                    model.addLessOrEqual(LinearExpr.sum(today.toArray(new Literal[0])), 2);
            }
        }

        // ── Days already found impossible, so that planning again is not
        //    planning the same thing again.
        for (Cut cut : cuts) {
            if (cut.day() >= days || cut.subjects().isEmpty()) continue;
            List<Literal> same = new ArrayList<>();
            for (int i = 0; i < n; i++)
                if ((cut.subjects().contains(sameAs(lessons.get(i)))
                     || cut.subjects().contains("lesson:" + lessons.get(i).id()))
                    && on[i][cut.day()] != null)
                    same.add(on[i][cut.day()]);
            // One block of a subject a day already, so this sum counts
            // subjects present and forbids the whole set being there at once.
            if (!same.isEmpty())
                model.addLessOrEqual(LinearExpr.sum(same.toArray(new Literal[0])),
                    cut.subjects().size() - 1);
        }

        // ── A teacher's day holds only so many hours, and fewer when they are
        //    somewhere else for part of it.
        Map<String, List<Integer>> byTeacher = new LinkedHashMap<>();
        for (int i = 0; i < n; i++) {
            Lesson l = lessons.get(i);
            for (String t : new String[] { l.teacherId(), l.supportTeacherId() })
                if (t != null)
                    byTeacher.computeIfAbsent(t, k -> new ArrayList<>()).add(i);
        }
        for (Map.Entry<String, List<Integer>> who : byTeacher.entrySet()) {
            Teacher teacher = teachers.get(who.getKey());
            int cap = teacher == null ? slotsPerDay
                : Math.min(slotsPerDay, teacher.maxLessonsPerDay());
            List<Literal> working = new ArrayList<>();
            for (int d = 0; d < days; d++) {
                LinearExprBuilder load = LinearExpr.newBuilder();
                List<Literal> here = new ArrayList<>();
                for (int i : who.getValue())
                    if (on[i][d] != null) {
                        load.addTerm(on[i][d], lessons.get(i).length());
                        here.add(on[i][d]);
                    }
                if (here.isEmpty()) continue;
                int room = teacher == null ? cap
                    : Math.min(cap, freeHours(teachers, who.getKey(), d, slotsPerDay));
                model.addLessOrEqual(load.build(), room);

                // Whether this is a day they come in at all, for the weekly limit.
                BoolVar comesIn = model.newBoolVar("in_" + who.getKey() + "_" + d);
                for (Literal l : here) model.addImplication(l, comesIn);
                working.add(comesIn);
            }
            int maxDays = teacher == null ? days : teacher.maxWorkingDays();
            if (!working.isEmpty() && maxDays < days)
                model.addLessOrEqual(LinearExpr.sum(working.toArray(new Literal[0])), maxDays);
        }

        // Keeping each class's two halves together is the only thing to
        // choose between arrangements by, and it is a preference rather than a
        // rule: one teacher sometimes takes both halves and cannot teach them
        // at once. Forced, it costs more days than it saves.
        if (!drift.isEmpty())
            model.minimize(LinearExpr.sum(drift.toArray(new IntVar[0])));

        CpSolver solver = new CpSolver();
        solver.getParameters().setMaxTimeInSeconds(seconds);
        if (workers > 0) solver.getParameters().setNumWorkers(workers);
        CpSolverStatus status = solver.solve(model);
        if (status != CpSolverStatus.OPTIMAL && status != CpSolverStatus.FEASIBLE) {
            proven[0] = status == CpSolverStatus.INFEASIBLE;
            return null;
        }

        Map<String, Integer> spread = new LinkedHashMap<>();
        for (int i = 0; i < n; i++)
            for (int d = 0; d < days; d++)
                if (on[i][d] != null && solver.value(on[i][d]) == 1)
                    spread.put(lessons.get(i).id(), d);
        return spread;
    }

    /** Whether this teacher has any lesson of this half of the class today. */
    private static BoolVar anyOn(CpModel model, BoolVar[][] on, int day, List<Integer> mine,
                                 List<Lesson> lessons, String teacher, Audience half,
                                 String name) {
        List<Literal> theirs = new ArrayList<>();
        for (int i : mine) {
            Lesson l = lessons.get(i);
            if (l.audience() == half && teacher.equals(l.teacherId()) && on[i][day] != null)
                theirs.add(on[i][day]);
        }
        if (theirs.isEmpty()) return null;
        BoolVar any = model.newBoolVar(name);
        model.addMaxEquality(any, theirs.toArray(new Literal[0]));
        return any;
    }

    /** Hours of a day a lesson's teachers are all free for. */
    private static int freeHours(Map<String, Teacher> teachers, Lesson lesson, int day,
                                 int slotsPerDay) {
        int free = slotsPerDay;
        for (String id : new String[] { lesson.teacherId(), lesson.supportTeacherId() })
            if (id != null) free = Math.min(free, freeHours(teachers, id, day, slotsPerDay));
        return free;
    }

    private static int freeHours(Map<String, Teacher> teachers, String teacherId, int day,
                                 int slotsPerDay) {
        Teacher teacher = teachers.get(teacherId);
        if (teacher == null) return slotsPerDay;
        int away = 0;
        for (Blocked block : teacher.unavailable())
            if (block.day() == day) away += block.toPosition() - block.fromPosition() + 1;
        return Math.max(0, slotsPerDay - away);
    }
}
