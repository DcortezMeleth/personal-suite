package poc.jacop;

import java.util.ArrayList;
import java.util.List;
import org.jacop.constraints.And;
import org.jacop.constraints.IfThen;
import org.jacop.constraints.Or;
import org.jacop.constraints.PrimitiveConstraint;
import org.jacop.constraints.Reified;
import org.jacop.constraints.SumBool;
import org.jacop.constraints.SumInt;
import org.jacop.constraints.XeqC;
import org.jacop.constraints.XeqY;
import org.jacop.constraints.XgtC;
import org.jacop.constraints.XlteqC;
import org.jacop.constraints.XlteqY;
import org.jacop.constraints.XltY;
import org.jacop.constraints.XneqY;
import org.jacop.constraints.XplusClteqZ;
import org.jacop.core.IntVar;
import org.jacop.core.IntervalDomain;
import org.jacop.core.Store;
import org.jacop.search.DepthFirstSearch;
import org.jacop.search.IndomainMin;
import org.jacop.search.MostConstrainedDynamic;
import org.jacop.search.SelectChoicePoint;
import org.jacop.search.SimpleSelect;
import poc.Instance;
import poc.Verifier;

/**
 * The same timetable in JaCoP — a pure-JVM constraint solver, kept in the
 * comparison because it needs no native binary and because its first showing
 * was the fastest on the page.
 *
 * The model is the same shape as the CP-SAT one but says it differently:
 * clashes are pairwise disjunctions rather than resource sums, because that is
 * what this style of solver propagates well. The grids of "is this class busy",
 * "is this teacher busy" are still needed, since the rules about the shape of a
 * day and of a teacher's week are not statements about pairs of lessons.
 */
public final class JacopMain {

    private static boolean on(String rule) {
        String skip = System.getenv("POC_SKIP");
        return skip == null || !java.util.Arrays.asList(skip.split(",")).contains(rule);
    }

    public static void main(String[] args) {
        List<Instance.Lesson> spec = Instance.lessons();
        int n = spec.size();
        int slots = Instance.SLOTS;
        Store store = new Store();
        IntVar zero = new IntVar(store, 0, 0);
        IntVar one = new IntVar(store, 1, 1);

        IntVar[] start = new IntVar[n];
        IntVar[] room = new IntVar[n];
        for (int i = 0; i < n; i++) {
            Instance.Lesson l = spec.get(i);
            IntervalDomain legal = new IntervalDomain();
            for (int s = 0; s < slots; s++) {
                if (Instance.position(s) + l.length() > Instance.SLOTS_PER_DAY) continue;
                boolean away = false;
                for (int k = 0; k < l.length(); k++) {
                    if (Instance.unavailable(l.teacher(), s + k)) away = true;
                    if (l.supportTeacher() >= 0 && Instance.unavailable(l.supportTeacher(), s + k))
                        away = true;
                }
                if (!away) legal.unionAdapt(s, s);
            }
            start[i] = new IntVar(store, "s" + i, legal);

            IntervalDomain okRooms = new IntervalDomain();
            for (int r = 0; r < Instance.rooms(); r++)
                if (Instance.roomKindOf(r) == l.roomKind()) okRooms.unionAdapt(r, r);
            room[i] = new IntVar(store, "r" + i, okRooms);
        }

        // ── Pairwise rules ───────────────────────────────────────────────────
        for (int i = 0; i < n; i++) {
            boolean iBooks = Instance.booksResources(spec, i);
            for (int j = i + 1; j < n; j++) {
                Instance.Lesson a = spec.get(i), b = spec.get(j);
                boolean sameUnitGroup = a.crossClassGroup() >= 0
                    && a.crossClassGroup() == b.crossClassGroup();
                PrimitiveConstraint apart = new Or(
                    new XplusClteqZ(start[i], a.length(), start[j]),
                    new XplusClteqZ(start[j], b.length(), start[i]));

                // R1: one class in one place, except for its two halves.
                boolean sameClass = a.classIndex() == b.classIndex();
                boolean halves = a.group() != Instance.WHOLE && b.group() != Instance.WHOLE
                    && a.group() != b.group();
                if (sameClass && !halves) store.impose(apart);

                // R2 + R12. A merged group is taught once, so two of its
                // lessons sharing a teacher is not a clash — it is the point.
                if (!sameUnitGroup && iBooks && Instance.booksResources(spec, j)
                    && sharesTeacher(a, b)) store.impose(apart);

                // R3, likewise only between lessons that each book a room.
                if (!sameUnitGroup && iBooks && Instance.booksResources(spec, j))
                    store.impose(new Or(apart, new XneqY(room[i], room[j])));

                if (on("R5") && sameClass && a.subject().equals(b.subject())
                    && a.group() == b.group())
                    store.impose(differentDays(store, start[i], start[j]));

                if (a.crossClassUnit() >= 0 && a.crossClassUnit() == b.crossClassUnit())
                    store.impose(new XeqY(start[i], start[j]));                 // R8
            }
        }

        // ── Occupancy grids ──────────────────────────────────────────────────
        IntVar[][][] halfBusy = new IntVar[Instance.CLASSES.length][3][slots];
        IntVar[][] classBusy = new IntVar[Instance.CLASSES.length][slots];
        for (int c = 0; c < Instance.CLASSES.length; c++) {
            for (int s = 0; s < slots; s++) {
                for (int half = 1; half <= 2; half++) {
                    List<IntVar> here = new ArrayList<>();
                    for (int i = 0; i < n; i++) {
                        Instance.Lesson l = spec.get(i);
                        if (l.classIndex() != c) continue;
                        if (l.group() != Instance.WHOLE && l.group() != half) continue;
                        here.add(occupies(store, start[i], l.length(), s, "o" + i + "_" + s + "_" + half));
                    }
                    halfBusy[c][half][s] = new IntVar(store, "hb" + c + "_" + half + "_" + s, 0, 1);
                    store.impose(new SumBool(here.toArray(new IntVar[0]), "=", halfBusy[c][half][s]));
                }
                // R7: whenever one half is busy so is the other, which is what
                // lets Gr1 sit angielski while Gr2 sits niemiecki.
                if (on("R7")) store.impose(new XeqY(halfBusy[c][1][s], halfBusy[c][2][s]));
                classBusy[c][s] = halfBusy[c][1][s];
            }
            if (on("R9"))
                for (int d = 0; d < Instance.DAYS; d++)
                    for (int p = 1; p < Instance.SLOTS_PER_DAY; p++)
                        store.impose(new XlteqY(classBusy[c][d * Instance.SLOTS_PER_DAY + p],
                            classBusy[c][d * Instance.SLOTS_PER_DAY + p - 1]));
        }

        // R10
        for (int i = 0; on("R10") && i < n; i++) {
            Instance.Lesson l = spec.get(i);
            if (!l.optional()) continue;
            for (int s = 0; s < slots; s++) {
                if (Instance.position(s) == 0) continue;
                if (Instance.position(s) + 1 >= Instance.SLOTS_PER_DAY) continue;
                store.impose(new IfThen(new XeqC(start[i], s),
                    new XeqC(classBusy[l.classIndex()][s + 1], 0)));
            }
        }

        // ── Teachers: R13, R14, R15 off one grid ─────────────────────────────
        for (int t = 0; t < Instance.teacherCount(); t++) {
            IntVar[] busy = new IntVar[slots];
            for (int s = 0; s < slots; s++) {
                List<IntVar> here = new ArrayList<>();
                for (int i = 0; i < n; i++) {
                    Instance.Lesson l = spec.get(i);
                    if (!Instance.booksResources(spec, i)) continue;
                    if (l.teacher() != t && l.supportTeacher() != t) continue;
                    here.add(occupies(store, start[i], l.length(), s, "t" + t + "_" + i + "_" + s));
                }
                busy[s] = new IntVar(store, "tb" + t + "_" + s, 0, 1);
                if (here.isEmpty()) store.impose(new XeqC(busy[s], 0));
                else store.impose(new SumBool(here.toArray(new IntVar[0]), "=", busy[s]));
            }
            Instance.TeacherLimits lim = Instance.limits(t);
            List<IntVar> worked = new ArrayList<>();
            for (int d = 0; d < Instance.DAYS; d++) {
                int base = d * Instance.SLOTS_PER_DAY;
                IntVar[] ofDay = new IntVar[Instance.SLOTS_PER_DAY];
                System.arraycopy(busy, base, ofDay, 0, Instance.SLOTS_PER_DAY);
                IntVar count = new IntVar(store, "tc" + t + "_" + d, 0, Instance.SLOTS_PER_DAY);
                store.impose(new SumBool(ofDay, "=", count));
                if (on("R14")) store.impose(new XlteqC(count, lim.maxLessonsPerDay()));

                IntVar works = new IntVar(store, "tw" + t + "_" + d, 0, 1);
                store.impose(new Reified(new XgtC(count, 0), works));
                worked.add(works);

                // R15: a run of three free periods is only a gap when the
                // teacher is there on both sides of it.
                for (int p = 0; on("R15") && p + Instance.MAX_CONSECUTIVE_GAPS
                    < Instance.SLOTS_PER_DAY; p++) {
                    int run = Instance.MAX_CONSECUTIVE_GAPS + 1;
                    if (p + run > Instance.SLOTS_PER_DAY) break;
                    IntVar before = anyBusy(store, busy, base, base + p, "bf" + t + d + p);
                    IntVar after = anyBusy(store, busy, base + p + run,
                        base + Instance.SLOTS_PER_DAY, "af" + t + d + p);
                    IntVar[] window = new IntVar[run];
                    System.arraycopy(busy, base + p, window, 0, run);
                    IntVar inWindow = new IntVar(store, "iw" + t + d + p, 0, run);
                    store.impose(new SumBool(window, "=", inWindow));
                    store.impose(new IfThen(
                        new And(new XeqC(before, 1), new XeqC(after, 1)),
                        new XgtC(inWindow, 0)));
                }
            }
            if (on("R13")) {
                IntVar days = new IntVar(store, "td" + t, 0, Instance.DAYS);
                store.impose(new SumBool(worked.toArray(new IntVar[0]), "=", days));
                store.impose(new XlteqC(days, lim.maxWorkingDays()));
            }
        }

        List<IntVar> decisions = new ArrayList<>();
        for (IntVar v : start) decisions.add(v);
        for (int i = 0; i < n; i++) if (Instance.booksResources(spec, i)) decisions.add(room[i]);

        long began = System.currentTimeMillis();
        DepthFirstSearch<IntVar> search = new DepthFirstSearch<>();
        search.setPrintInfo(false);
        search.setTimeOut(args.length > 0 ? Long.parseLong(args[0]) : 120);
        SelectChoicePoint<IntVar> choice = new SimpleSelect<>(
            decisions.toArray(new IntVar[0]), new MostConstrainedDynamic<>(), new IndomainMin<>());
        boolean solved = search.labeling(store, choice);
        long millis = System.currentTimeMillis() - began;

        if (!solved) {
            System.out.printf("%n=== JaCoP === no solution within the limit, %d ms%n", millis);
            return;
        }
        List<Verifier.Placement> placements = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            int booker = i;
            if (!Instance.booksResources(spec, i))
                for (int j = 0; j < i; j++)
                    if (spec.get(j).crossClassGroup() == spec.get(i).crossClassGroup()) { booker = j; break; }
            placements.add(new Verifier.Placement(spec.get(i).id(), start[i].value(), room[booker].value()));
        }
        Verifier.report("JaCoP", millis, Verifier.check(spec, placements));
        String show = System.getenv("POC_PRINT");
        if (show != null && !show.isBlank())
            for (String p : show.split(",")) Verifier.printClass(spec, placements, Integer.parseInt(p.trim()));
    }

    private static boolean sharesTeacher(Instance.Lesson a, Instance.Lesson b) {
        int[] mine = { a.teacher(), a.supportTeacher() };
        int[] theirs = { b.teacher(), b.supportTeacher() };
        for (int x : mine) for (int y : theirs) if (x >= 0 && x == y) return true;
        return false;
    }

    /** Two starts fall on different days. */
    private static PrimitiveConstraint differentDays(Store store, IntVar a, IntVar b) {
        IntVar dayA = new IntVar(store, 0, Instance.DAYS - 1);
        IntVar dayB = new IntVar(store, 0, Instance.DAYS - 1);
        bindDay(store, a, dayA);
        bindDay(store, b, dayB);
        return new XneqY(dayA, dayB);
    }

    private static void bindDay(Store store, IntVar slot, IntVar day) {
        for (int d = 0; d < Instance.DAYS; d++) {
            int lo = d * Instance.SLOTS_PER_DAY, hi = lo + Instance.SLOTS_PER_DAY - 1;
            store.impose(new IfThen(
                new And(new XlteqY(new IntVar(store, lo, lo), slot),
                        new XlteqY(slot, new IntVar(store, hi, hi))),
                new XeqC(day, d)));
        }
    }

    private static IntVar occupies(Store store, IntVar start, int len, int slot, String name) {
        IntVar here = new IntVar(store, name, 0, 1);
        store.impose(new Reified(
            new And(new XlteqY(start, new IntVar(store, slot, slot)),
                    new XltY(new IntVar(store, slot - len, slot - len), start)),
            here));
        return here;
    }

    private static IntVar anyBusy(Store store, IntVar[] busy, int from, int to, String name) {
        IntVar any = new IntVar(store, name, 0, 1);
        if (from >= to) { store.impose(new XeqC(any, 0)); return any; }
        IntVar[] window = new IntVar[to - from];
        System.arraycopy(busy, from, window, 0, to - from);
        IntVar count = new IntVar(store, name + "c", 0, window.length);
        store.impose(new SumBool(window, "=", count));
        store.impose(new Reified(new XgtC(count, 0), any));
        return any;
    }

    private JacopMain() {}
}
