package poc;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * Checks a finished timetable against every rule the exercise set out to
 * express.
 *
 * This is the point of the whole comparison. A solver library will always claim
 * it can express a constraint; what matters is whether the timetable that comes
 * back actually satisfies it. Each candidate hands its answer to this, and the
 * list of violations is the result.
 */
public final class Verifier {

    public record Placement(int lessonId, int startSlot, int room) {}
    public record Rule(String id, String name, boolean satisfied, String detail) {}

    public static List<Rule> check(List<Instance.Lesson> lessons, List<Placement> placements) {
        Map<Integer, Placement> at = new HashMap<>();
        for (Placement p : placements) at.put(p.lessonId(), p);

        List<Rule> rules = new ArrayList<>();

        List<Integer> missing = new ArrayList<>();
        for (Instance.Lesson l : lessons) if (!at.containsKey(l.id())) missing.add(l.id());
        rules.add(new Rule("R4", "every block placed exactly once",
            missing.isEmpty() && placements.size() == lessons.size(),
            missing.isEmpty() ? "" : missing.size() + " unplaced"));
        if (!missing.isEmpty()) return rules;

        Map<Integer, List<Integer>> span = new HashMap<>();
        boolean withinDay = true;
        for (Instance.Lesson l : lessons) {
            int s0 = at.get(l.id()).startSlot();
            List<Integer> slots = new ArrayList<>();
            for (int k = 0; k < l.length(); k++) slots.add(s0 + k);
            span.put(l.id(), slots);
            if (Instance.day(s0) != Instance.day(s0 + l.length() - 1)) withinDay = false;
        }
        rules.add(new Rule("R6", "a multi-hour block is consecutive within one day", withinDay, ""));

        // ── R1: a class in one place. A whole-class lesson blocks both halves;
        //        the halves do not block each other, which is what lets a split
        //        happen at all.
        boolean classOk = true;
        Map<String, Integer> occupancy = new HashMap<>();
        for (Instance.Lesson l : lessons)
            for (int s : span.get(l.id()))
                for (String k : audienceKeys(l))
                    if (occupancy.merge(k + "@" + s, 1, Integer::sum) > 1) classOk = false;
        rules.add(new Rule("R1", "a class is never double-booked", classOk, ""));

        // ── R2 + R12: a teacher in one place, counting the hours they spend
        //        supporting somebody else's lesson exactly like their own.
        boolean teacherOk = true;
        Map<String, Integer> teacherAt = new HashMap<>();
        for (int i = 0; i < lessons.size(); i++) {
            Instance.Lesson l = lessons.get(i);
            // A merged group is taught once, so only the lesson that books the
            // group consumes its teacher; the rest just mark their class busy.
            if (!Instance.booksResources(lessons, i)) continue;
            for (int s : span.get(l.id())) {
                if (teacherAt.merge("t" + l.teacher() + "@" + s, 1, Integer::sum) > 1) teacherOk = false;
                if (l.supportTeacher() >= 0
                    && teacherAt.merge("t" + l.supportTeacher() + "@" + s, 1, Integer::sum) > 1)
                    teacherOk = false;
            }
        }
        rules.add(new Rule("R2", "a teacher is never double-booked", teacherOk, ""));
        rules.add(new Rule("R12", "a supporting teacher is booked like any other",
            teacherOk && lessons.stream().anyMatch(l -> l.supportTeacher() >= 0), ""));

        // ── R3: rooms
        boolean roomOk = true, kindOk = true;
        Set<String> roomAt = new HashSet<>();
        for (int i = 0; i < lessons.size(); i++) {
            Instance.Lesson l = lessons.get(i);
            if (!Instance.booksResources(lessons, i)) continue;
            Placement p = at.get(l.id());
            if (Instance.roomKindOf(p.room()) != l.roomKind()) kindOk = false;
            for (int s : span.get(l.id())) if (!roomAt.add("r" + p.room() + "@" + s)) roomOk = false;
        }
        rules.add(new Rule("R3", "a room is never double-booked", roomOk, ""));
        rules.add(new Rule("R3b", "a lesson needing a gym gets one", kindOk, ""));

        // ── R5: one block of a subject per day, per audience. Base and
        //        extension are one subject, so they compete for the same day.
        Set<String> perDay = new HashSet<>();
        boolean onceADay = true;
        for (Instance.Lesson l : lessons) {
            String k = l.subject() + "|c" + l.classIndex() + "|g" + l.group()
                + "|d" + Instance.day(at.get(l.id()).startSlot());
            if (!perDay.add(k)) onceADay = false;
        }
        rules.add(new Rule("R5", "at most one block of a subject per day, per half", onceADay, ""));

        // ── R7: whenever one half of a class is busy, so is the other. This is
        //        the rule the language swap needs: Gr1 angielski against Gr2
        //        niemiecki is legal precisely because it keeps both busy.
        boolean halvesTogether = true;
        for (int c = 0; c < Instance.CLASSES.length; c++) {
            for (int s = 0; s < Instance.SLOTS; s++) {
                int g1 = occupancy.getOrDefault("c" + c + "g1@" + s, 0);
                int g2 = occupancy.getOrDefault("c" + c + "g2@" + s, 0);
                if (g1 != g2) halvesTogether = false;
            }
        }
        rules.add(new Rule("R7", "both halves of a class are busy together", halvesTogether, ""));

        // ── R8: a unit runs as one
        Map<Integer, Set<Integer>> unitSlots = new HashMap<>();
        for (Instance.Lesson l : lessons)
            if (l.crossClassUnit() >= 0)
                unitSlots.computeIfAbsent(l.crossClassUnit(), k -> new HashSet<>())
                    .add(at.get(l.id()).startSlot());
        rules.add(new Rule("R8", "a cross-class unit runs as one",
            unitSlots.values().stream().allMatch(v -> v.size() == 1), ""));

        // ── R9/R10: the shape of a class's day
        boolean compact = true, edges = true;
        for (int c = 0; c < Instance.CLASSES.length; c++) {
            for (int d = 0; d < Instance.DAYS; d++) {
                Set<Integer> busy = new TreeSet<>();
                for (Instance.Lesson l : lessons) {
                    if (l.classIndex() != c) continue;
                    for (int s : span.get(l.id()))
                        if (Instance.day(s) == d) busy.add(Instance.position(s));
                }
                if (busy.isEmpty()) continue;
                int last = ((TreeSet<Integer>) busy).last();
                for (int p = 0; p <= last; p++) if (!busy.contains(p)) compact = false;
                if (!busy.contains(0)) compact = false;
                for (Instance.Lesson l : lessons) {
                    if (!l.optional() || l.classIndex() != c) continue;
                    int s0 = at.get(l.id()).startSlot();
                    if (Instance.day(s0) != d) continue;
                    int pos = Instance.position(s0);
                    if (pos != 0 && pos != last) edges = false;
                }
            }
        }
        rules.add(new Rule("R9", "a class day starts at slot 1 and has no gaps", compact, ""));
        rules.add(new Rule("R10", "an optional subject is first or last in its day", edges, ""));

        // ── R11, R13, R14, R15: what is asked of a teacher
        boolean available = true, withinDays = true, withinDaily = true, gapsOk = true;
        String gapDetail = "";
        for (int t = 0; t < Instance.teacherCount(); t++) {
            Set<Integer> daysWorked = new HashSet<>();
            Map<Integer, Set<Integer>> busyByDay = new HashMap<>();
            for (int i = 0; i < lessons.size(); i++) {
                Instance.Lesson l = lessons.get(i);
                if (l.teacher() != t && l.supportTeacher() != t) continue;
                if (!Instance.booksResources(lessons, i)) continue;
                for (int s : span.get(l.id())) {
                    if (Instance.unavailable(t, s)) available = false;
                    daysWorked.add(Instance.day(s));
                    busyByDay.computeIfAbsent(Instance.day(s), k -> new TreeSet<>())
                        .add(Instance.position(s));
                }
            }
            Instance.TeacherLimits lim = Instance.limits(t);
            if (daysWorked.size() > lim.maxWorkingDays()) withinDays = false;
            for (Set<Integer> positions : busyByDay.values()) {
                if (positions.size() > lim.maxLessonsPerDay()) withinDaily = false;
                int first = ((TreeSet<Integer>) positions).first();
                int last = ((TreeSet<Integer>) positions).last();
                int run = 0;
                for (int p = first; p <= last; p++) {
                    run = positions.contains(p) ? 0 : run + 1;
                    if (run > Instance.MAX_CONSECUTIVE_GAPS) {
                        gapsOk = false;
                        if (gapDetail.isEmpty()) gapDetail = "teacher " + t + " has " + run + " in a row";
                    }
                }
            }
        }
        rules.add(new Rule("R11", "teacher unavailability is respected", available, ""));
        rules.add(new Rule("R13", "a teacher's working days stay within their limit", withinDays, ""));
        rules.add(new Rule("R14", "a teacher's lessons per day stay within their limit", withinDaily, ""));
        rules.add(new Rule("R15", "no teacher sits through more than two free periods", gapsOk, gapDetail));

        return rules;
    }

    /** Which audiences a lesson occupies: a whole-class lesson takes both halves. */
    private static List<String> audienceKeys(Instance.Lesson l) {
        List<String> keys = new ArrayList<>();
        if (l.group() == Instance.WHOLE) {
            keys.add("c" + l.classIndex() + "g1");
            keys.add("c" + l.classIndex() + "g2");
        } else {
            keys.add("c" + l.classIndex() + "g" + l.group());
        }
        return keys;
    }

    /**
     * The week as the class sees it. Printed when POC_PRINT names a class,
     * because the rules being satisfied is not quite the same as the timetable
     * looking like a timetable — and the language swap in particular is only
     * convincing when you can see it.
     */
    public static void printClass(List<Instance.Lesson> lessons, List<Placement> placements, int c) {
        Map<Integer, Placement> at = new HashMap<>();
        for (Placement p : placements) at.put(p.lessonId(), p);
        System.out.printf("%n--- %s ---%n", Instance.CLASSES[c]);
        for (int d = 0; d < Instance.DAYS; d++) {
            StringBuilder line = new StringBuilder(String.format("  %s ", "pn wt sr cz pt".split(" ")[d]));
            for (int p = 0; p < Instance.SLOTS_PER_DAY; p++) {
                int slot = d * Instance.SLOTS_PER_DAY + p;
                List<String> here = new ArrayList<>();
                for (Instance.Lesson l : lessons) {
                    if (l.classIndex() != c) continue;
                    int s0 = at.get(l.id()).startSlot();
                    if (slot < s0 || slot >= s0 + l.length()) continue;
                    String tag = l.subject().substring(0, Math.min(4, l.subject().length()));
                    if (l.group() != Instance.WHOLE) tag += "/" + l.group();
                    if (l.crossClassGroup() >= 0) tag += "*";
                    if (l.supportTeacher() >= 0) tag += "+";
                    here.add(tag);
                }
                line.append(String.format("%-12s", here.isEmpty() ? "." : String.join(",", here)));
            }
            System.out.println(line);
        }
        System.out.println("  (/1 /2 = half of a split, * = merged cross-class group, "
            + "+ = supporting teacher)");
    }

    public static void report(String solver, long millis, List<Rule> rules) {
        long ok = rules.stream().filter(Rule::satisfied).count();
        System.out.printf("%n=== %s === %d/%d rules, %d ms%n", solver, ok, rules.size(), millis);
        for (Rule r : rules)
            System.out.printf("  [%s] %-4s %s%s%n", r.satisfied() ? "ok" : "XX", r.id(), r.name(),
                r.detail().isEmpty() ? "" : " (" + r.detail() + ")");
    }

    private Verifier() {}
}
