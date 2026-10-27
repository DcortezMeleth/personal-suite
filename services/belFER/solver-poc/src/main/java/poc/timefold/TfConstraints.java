package poc.timefold;

import ai.timefold.solver.core.api.score.buildin.hardsoft.HardSoftScore;
import ai.timefold.solver.core.api.score.stream.Constraint;
import ai.timefold.solver.core.api.score.stream.ConstraintCollectors;
import ai.timefold.solver.core.api.score.stream.ConstraintFactory;
import ai.timefold.solver.core.api.score.stream.ConstraintProvider;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import poc.Instance;

/**
 * The rules, over the hours rather than over the lessons.
 *
 * Two of the eleven have gone. "Both halves of a split are busy together" and
 * "a cross-class block runs as one" were the two the old model spent all its
 * effort failing — and they are not stated here at all, because the thing they
 * asked for is now the shape of the entity rather than a cost to be weighed.
 *
 * Rooms have gone from the search as well, and are counted instead: how many
 * lessons of each kind an hour can hold. That was worth a great deal in the
 * other engine — thirty-four thousand optional intervals became a handful of
 * sums — and there is no reason to think it is worth less here.
 */
public class TfConstraints implements ConstraintProvider {

    @Override
    public Constraint[] defineConstraints(ConstraintFactory f) {
        return new Constraint[] {
            insideOneDay(f),
            classClash(f),
            teacherClash(f),
            sameTeacherWithin(f),
            roomCapacity(f),
            oneBlockPerSubjectPerDay(f),
            teacherAvailable(f),
            compactDay(f),
            optionalAtEdge(f),
        };
    }

    private Constraint insideOneDay(ConstraintFactory f) {
        return f.forEach(TfGroup.class)
            .filter(g -> !g.withinOneDay())
            .penalize(HardSoftScore.ONE_HARD)
            .asConstraint("block stays inside one day");
    }

    /**
     * Who is where, hour by hour, counted rather than compared.
     *
     * Written as "every pair of hours that overlap, every pair of lessons
     * inside them" this is a few million comparisons for a score, re-done
     * whenever anything moves — which measures the way the rule is written far
     * more than it measures the engine. Flattened to one cell per occupant per
     * hour and counted, the same rule is indexed: a move touches the handful of
     * cells it actually changed.
     *
     * A whole-class lesson occupies both halves of its class, which is what
     * makes "whole clashes with either half" fall out of counting instead of
     * needing a special case.
     */
    private Constraint classClash(ConstraintFactory f) {
        return f.forEach(TfGroup.class)
            .flattenLast(TfConstraints::classCells)
            .groupBy(c -> c, ConstraintCollectors.count())
            .filter((cell, used) -> used > 1)
            .penalize(HardSoftScore.ONE_HARD, (cell, used) -> used - 1)
            .asConstraint("class double-booked");
    }

    private static List<String> classCells(TfGroup g) {
        List<String> cells = new java.util.ArrayList<>();
        for (Instance.Lesson l : g.getMembers())
            for (int s = g.getStartSlot(); s < g.endSlotExclusive(); s++) {
                if (l.group() == Instance.WHOLE || l.group() == Instance.GROUP_1)
                    cells.add(l.classIndex() + "/1@" + s);
                if (l.group() == Instance.WHOLE || l.group() == Instance.GROUP_2)
                    cells.add(l.classIndex() + "/2@" + s);
            }
        return cells;
    }

    private Constraint teacherClash(ConstraintFactory f) {
        return f.forEach(TfGroup.class)
            .flattenLast(TfConstraints::teacherCells)
            .groupBy(c -> c, ConstraintCollectors.count())
            .filter((cell, used) -> used > 1)
            .penalize(HardSoftScore.ONE_HARD, (cell, used) -> used - 1)
            .asConstraint("teacher double-booked");
    }

    /** A supporting teacher is booked exactly as the teaching one is. */
    private static List<String> teacherCells(TfGroup g) {
        List<String> cells = new java.util.ArrayList<>();
        for (Instance.Lesson l : g.getMembers())
            for (int s = g.getStartSlot(); s < g.endSlotExclusive(); s++) {
                if (TfGroup.books(l)) cells.add("t" + l.teacher() + "@" + s);
                if (l.supportTeacher() >= 0) cells.add("t" + l.supportTeacher() + "@" + s);
            }
        return cells;
    }

    /**
     * A teacher cannot take two of the lessons inside one hour either. Grouping
     * keeps a shared teacher's two halves apart to begin with, so this should
     * never fire — it is here because the grouping is a claim about the data
     * and claims about data are worth checking.
     */
    private Constraint sameTeacherWithin(ConstraintFactory f) {
        return f.forEach(TfGroup.class)
            .penalize(HardSoftScore.ONE_HARD, g -> {
                Set<Integer> seen = new HashSet<>();
                int bad = 0;
                for (Instance.Lesson l : g.getMembers()) if (!seen.add(l.teacher())) bad++;
                return bad;
            })
            .asConstraint("one teacher twice in one hour");
    }

    /** Rooms as a count per hour, not as a choice per lesson. */
    private Constraint roomCapacity(ConstraintFactory f) {
        return f.forEach(TfGroup.class)
            .flattenLast(g -> {
                List<int[]> cells = new java.util.ArrayList<>();
                for (int s = g.getStartSlot(); s < g.endSlotExclusive(); s++)
                    for (Instance.Lesson l : g.getMembers())
                        if (TfGroup.books(l)) cells.add(new int[] { s, l.roomKind() });
                return cells;
            })
            .groupBy(c -> c[0], c -> c[1], ConstraintCollectors.count())
            .filter((slot, kind, used) -> used > capacity(kind))
            .penalize(HardSoftScore.ONE_HARD, (slot, kind, used) -> used - capacity(kind))
            .asConstraint("not enough rooms of that kind");
    }

    private static int capacity(int kind) {
        return kind == 0 ? Instance.ordinaryRooms() : Instance.gymRooms();
    }

    private Constraint oneBlockPerSubjectPerDay(ConstraintFactory f) {
        return f.forEach(TfGroup.class)
            .flattenLast(g -> {
                List<String> cells = new java.util.ArrayList<>();
                for (Instance.Lesson l : g.getMembers())
                    cells.add(l.classIndex() + "|" + l.subject() + "|" + l.group() + "@" + g.day());
                return cells;
            })
            .groupBy(c -> c, ConstraintCollectors.count())
            .filter((cell, used) -> used > 1)
            .penalize(HardSoftScore.ONE_HARD, (cell, used) -> used - 1)
            .asConstraint("two blocks of a subject in one day");
    }

    private Constraint teacherAvailable(ConstraintFactory f) {
        return f.forEach(TfGroup.class)
            .penalize(HardSoftScore.ONE_HARD, g -> {
                int bad = 0;
                for (Instance.Lesson l : g.getMembers())
                    for (int s = g.getStartSlot(); s < g.endSlotExclusive(); s++)
                        if (Instance.unavailable(l.teacher(), s)) bad++;
                return bad;
            })
            .asConstraint("teacher unavailable");
    }

    private Constraint compactDay(ConstraintFactory f) {
        return f.forEach(TfGroup.class)
            .flattenLast(g -> {
                List<int[]> cells = new java.util.ArrayList<>();
                for (Instance.Lesson l : g.getMembers())
                    for (int s = g.getStartSlot(); s < g.endSlotExclusive(); s++)
                        cells.add(new int[] { l.classIndex(), Instance.day(s), Instance.position(s) });
                return cells;
            })
            .groupBy(c -> c[0], c -> c[1], ConstraintCollectors.toList(c -> c[2]))
            .filter((cls, day, used) -> holes(used) > 0)
            .penalize(HardSoftScore.ONE_HARD, (cls, day, used) -> holes(used))
            .asConstraint("class day has gaps");
    }

    private static int holes(List<Integer> positions) {
        Set<Integer> busy = new HashSet<>(positions);
        int max = busy.stream().max(Integer::compare).orElse(-1);
        int holes = 0;
        for (int p = 0; p <= max; p++) if (!busy.contains(p)) holes++;
        return holes;
    }

    private Constraint optionalAtEdge(ConstraintFactory f) {
        return f.forEach(TfGroup.class)
            .flattenLast(g -> {
                List<int[]> cells = new java.util.ArrayList<>();
                for (Instance.Lesson l : g.getMembers())
                    cells.add(new int[] { l.classIndex(), g.day(),
                        Instance.position(g.getStartSlot()),
                        Instance.position(g.endSlotExclusive() - 1),
                        l.optional() ? 1 : 0 });
                return cells;
            })
            .groupBy(c -> c[0], c -> c[1], ConstraintCollectors.toList())
            .penalize(HardSoftScore.ONE_HARD, (cls, day, cells) -> {
                int last = 0;
                for (int[] c : cells) last = Math.max(last, c[3]);
                int bad = 0;
                for (int[] c : cells) if (c[4] == 1 && c[2] != 0 && c[2] != last) bad++;
                return bad;
            })
            .asConstraint("optional subject mid-day");
    }
}
