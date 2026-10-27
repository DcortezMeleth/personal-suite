package poc;

import java.util.ArrayList;
import java.util.List;

/**
 * One timetable problem, shaped like the school's rather than like a textbook's.
 *
 * Every candidate solver is given this same instance and its answer is checked
 * by the same Verifier, so "can it express the rule" is settled by a solved
 * timetable rather than by reading the API docs.
 *
 * What it deliberately carries, because these are the things that broke the
 * school's previous software or that the requirements call out by name:
 *
 *   podział na grupy       both halves of a class busy at once, always
 *   wymiana językowa       Gr1 angielski / Gr2 niemiecki, mirrored next slot
 *   zajęcia międzyoddz.    a unit pinning several classes to one slot, with
 *                          partial merges — one class alone, two merged
 *   nauczyciel wspomagający a second teacher booked for all of a line's hours
 *   godziny rozszerzone    base and extension of one subject, different
 *                          teachers, still one subject for "once a day"
 *   okienka nauczyciela     no more than two free periods in a row
 *   dni pracy / limit dzienny  per-teacher caps
 *   niedostępność          teaches at another school certain hours
 */
public final class Instance {

    public static final int DAYS = 5;
    public static final int SLOTS_PER_DAY = 8;
    public static final int SLOTS = DAYS * SLOTS_PER_DAY;

    public static int day(int slot) { return slot / SLOTS_PER_DAY; }
    public static int position(int slot) { return slot % SLOTS_PER_DAY; }

    /** Whole class, or one of the two halves it is always split into. */
    public static final int WHOLE = 0, GROUP_1 = 1, GROUP_2 = 2;

    /**
     * @param group          WHOLE, GROUP_1 or GROUP_2
     * @param supportTeacher a second teacher present for every hour, booked
     *                       exactly like the first; -1 when there is none
     * @param crossClassUnit lessons sharing a unit must start in the same slot
     * @param crossClassGroup lessons sharing a group are one taught group drawn
     *                        from several classes: one teacher, one room, every
     *                        class in it occupied. -1 when the lesson stands
     *                        alone. This is the partial merge the school
     *                        actually runs — 3A's girls on their own while its
     *                        boys join 3B's.
     * @param optional       religia and etyka: first or last in the day
     */
    public record Lesson(
        int id, String subject, int classIndex, int group,
        int teacher, int supportTeacher, int length,
        boolean optional, int crossClassUnit, int crossClassGroup, int roomKind) {}

    public record TeacherLimits(int maxWorkingDays, int maxLessonsPerDay) {}

    public static int classCount() {
        String env = System.getenv("POC_CLASSES");
        if (env != null && !env.isBlank()) return Integer.parseInt(env.trim());
        return Integer.getInteger("poc.classes", 3);
    }

    /** Classes share teachers within a band of three, as the real school does. */
    private static int band(int classIndex) { return classIndex / 3; }
    public static int bands() { return (classCount() + 2) / 3; }

    public static final String[] CLASSES = buildClasses();

    private static String[] buildClasses() {
        String[] out = new String[classCount()];
        for (int c = 0; c < out.length; c++)
            out[c] = (1 + c / 8) + String.valueOf((char) ('A' + c % 8));
        return out;
    }

    // ── Teachers ─────────────────────────────────────────────────────────────
    // Per band: polski, 2× angielski, 2× niemiecki, historia, fizyka,
    // 2× informatyka, 3× WF, 1 wspomagający. Maths is one per class, since a
    // maths teacher also takes that class's godzina wychowawcza. The single
    // hour subjects — biologia, chemia, geografia, religia — are shared across
    // two bands, which is where most of the contention comes from.
    private static final int PER_BAND = 12;
    private static final int POL = 0, ANG1 = 1, ANG2 = 2, NIE1 = 3, NIE2 = 4,
        HIST = 5, FIZ = 6, INF1 = 7, INF2 = 8, WF_A = 9, WF_B = 10, SUPPORT = 11;
    private static final int PER_PAIR = 4;
    private static final int BIO = 0, CHEM = 1, GEO = 2, REL = 3;

    private static int bandBase() { return classCount(); }
    private static int pairBase() { return bandBase() + bands() * PER_BAND; }
    private static int pairs() { return (bands() + 1) / 2; }

    private static int mathsOf(int c) { return c; }
    private static int bandT(int c, int offset) { return bandBase() + band(c) * PER_BAND + offset; }
    private static int pairT(int c, int offset) { return pairBase() + (band(c) / 2) * PER_PAIR + offset; }

    public static int teacherCount() { return pairBase() + pairs() * PER_PAIR; }

    public static TeacherLimits limits(int teacher) {
        // The band's Polish teacher is the one working four days; everyone else
        // takes the school default. One restriction of each kind is enough to
        // prove the rule is expressible without making the instance a puzzle.
        if (teacher >= bandBase() && (teacher - bandBase()) % PER_BAND == POL)
            return new TeacherLimits(4, 6);
        return new TeacherLimits(5, 8);
    }

    /** Every band's Polish teacher is at another school the last two hours. */
    public static boolean unavailable(int teacher, int slot) {
        if (teacher < bandBase() || teacher >= pairBase()) return false;
        return (teacher - bandBase()) % PER_BAND == POL && position(slot) >= 6;
    }

    /** Nobody may sit through more than two free periods in a row. */
    public static final int MAX_CONSECUTIVE_GAPS = 2;

    // ── Rooms ────────────────────────────────────────────────────────────────
    public static int ordinaryRooms() { return 2 * 3 * bands(); }
    public static int gymRooms() { return 3 * bands(); }
    public static int rooms() { return ordinaryRooms() + gymRooms(); }
    public static int roomKindOf(int room) { return room < ordinaryRooms() ? 0 : 1; }

    /**
     * Whether this lesson is the one that books the teacher and the room for
     * its group. A merged group is taught once however many classes it draws
     * from, so only its first lesson consumes those resources; the rest record
     * that their class is occupied.
     */
    public static boolean booksResources(List<Lesson> all, int index) {
        return bookers(all)[index];
    }

    /**
     * Computed once and cached. The obvious implementation rescans the list on
     * every call, which is fine until it is asked inside a loop over every pair
     * of lessons — cubic work to build a model that then solves in seconds.
     */
    public static boolean[] bookers(List<Lesson> all) {
        if (cachedBookers != null && cachedFor == all) return cachedBookers;
        boolean[] out = new boolean[all.size()];
        java.util.Set<Integer> seen = new java.util.HashSet<>();
        for (int i = 0; i < all.size(); i++) {
            int group = all.get(i).crossClassGroup();
            out[i] = group < 0 || seen.add(group);
        }
        cachedFor = all;
        cachedBookers = out;
        return out;
    }

    private static List<Lesson> cachedFor;
    private static boolean[] cachedBookers;

    public static List<Lesson> lessons() {
        List<Lesson> out = new ArrayList<>();
        int id = 0;
        for (int c = 0; c < classCount(); c++) {
            int maths = mathsOf(c);
            boolean extended = c % 3 == 0;   // one class per band takes extended maths
            boolean swaps = c % 3 == 1;      // one per band runs the language swap
            // A support teacher is attached to one class per band, to the
            // subject it most plausibly happens on.
            int support = c % 3 == 2 ? bandT(c, SUPPORT) : -1;

            // matematyka: 5h as 1+2+2, and 2 extension hours under a different
            // teacher — one subject, so still at most one block a day.
            out.add(new Lesson(id++, "matematyka", c, WHOLE, maths, -1, 1, false, -1, -1, 0));
            out.add(new Lesson(id++, "matematyka", c, WHOLE, maths, -1, 2, false, -1, -1, 0));
            out.add(new Lesson(id++, "matematyka", c, WHOLE, maths, -1, 2, false, -1, -1, 0));
            if (extended)
                out.add(new Lesson(id++, "matematyka", c, WHOLE, bandT(c, FIZ), -1, 2, false, -1, -1, 0));

            // polski 4h, with the support teacher where there is one
            out.add(new Lesson(id++, "polski", c, WHOLE, bandT(c, POL), support, 2, false, -1, -1, 0));
            out.add(new Lesson(id++, "polski", c, WHOLE, bandT(c, POL), support, 1, false, -1, -1, 0));
            out.add(new Lesson(id++, "polski", c, WHOLE, bandT(c, POL), support, 1, false, -1, -1, 0));

            // Languages. The swapping class runs Gr1 angielski against Gr2
            // niemiecki and mirrors it, which is only legal because "one block
            // a day" is per half rather than per class.
            if (swaps) {
                // The halves take opposite language loads: three hours of
                // angielski for one and three of niemiecki for the other. They
                // cannot pair up subject-with-subject, so the only way to keep
                // both halves busy is for one to sit angielski while the other
                // sits niemiecki — the arrangement the school already runs and
                // the one its previous software could not express.
                for (int k = 0; k < 3; k++)
                    out.add(new Lesson(id++, "angielski", c, GROUP_1, bandT(c, ANG1), -1, 1, false, -1, -1, 0));
                out.add(new Lesson(id++, "niemiecki", c, GROUP_1, bandT(c, NIE1), -1, 1, false, -1, -1, 0));
                for (int k = 0; k < 3; k++)
                    out.add(new Lesson(id++, "niemiecki", c, GROUP_2, bandT(c, NIE2), -1, 1, false, -1, -1, 0));
                out.add(new Lesson(id++, "angielski", c, GROUP_2, bandT(c, ANG2), -1, 1, false, -1, -1, 0));
            } else {
                for (int k = 0; k < 3; k++) {
                    out.add(new Lesson(id++, "angielski", c, GROUP_1, bandT(c, ANG1), -1, 1, false, -1, -1, 0));
                    out.add(new Lesson(id++, "angielski", c, GROUP_2, bandT(c, ANG2), -1, 1, false, -1, -1, 0));
                }
                for (int k = 0; k < 2; k++) {
                    out.add(new Lesson(id++, "niemiecki", c, GROUP_1, bandT(c, NIE1), -1, 1, false, -1, -1, 0));
                    out.add(new Lesson(id++, "niemiecki", c, GROUP_2, bandT(c, NIE2), -1, 1, false, -1, -1, 0));
                }
            }

            // informatyka 2h, split — the other subject taught in halves
            for (int k = 0; k < 2; k++) {
                out.add(new Lesson(id++, "informatyka", c, GROUP_1, bandT(c, INF1), -1, 1, false, -1, -1, 0));
                out.add(new Lesson(id++, "informatyka", c, GROUP_2, bandT(c, INF2), -1, 1, false, -1, -1, 0));
            }

            out.add(new Lesson(id++, "historia", c, WHOLE, bandT(c, HIST), -1, 2, false, -1, -1, 0));
            out.add(new Lesson(id++, "fizyka", c, WHOLE, bandT(c, FIZ), -1, 1, false, -1, -1, 0));
            out.add(new Lesson(id++, "fizyka", c, WHOLE, bandT(c, FIZ), -1, 2, false, -1, -1, 0));
            out.add(new Lesson(id++, "biologia", c, WHOLE, pairT(c, BIO), -1, 1, false, -1, -1, 0));
            out.add(new Lesson(id++, "chemia", c, WHOLE, pairT(c, CHEM), -1, 1, false, -1, -1, 0));
            out.add(new Lesson(id++, "geografia", c, WHOLE, pairT(c, GEO), -1, 1, false, -1, -1, 0));
            out.add(new Lesson(id++, "religia", c, WHOLE, pairT(c, REL), -1, 1, true, -1, -1, 0));
            // godzina wychowawcza: the class's own maths teacher
            out.add(new Lesson(id++, "godz. wych.", c, WHOLE, maths, -1, 1, false, -1, -1, 0));
        }

        // ── Zajęcia międzyoddziałowe ─────────────────────────────────────────
        // Two WF hours a week. The merge is partial, as the school runs it: the
        // band's first class is taught on its own while the other two are
        // taught together as one group — one teacher, one gym, both classes
        // occupied. Every class the unit touches is pinned to its slot.
        int unit = 0, group = 0;
        for (int b = 0; b < bands(); b++) {
            int first = b * 3;
            int last = Math.min(first + 3, classCount());
            for (int hour = 0; hour < 2; hour++) {
                int u = unit++;
                int alone = group++;
                out.add(new Lesson(id++, "wf", first, WHOLE, bandT(first, WF_A), -1, 1,
                    false, u, alone, 1));
                if (last - first > 1) {
                    int merged = group++;
                    for (int c = first + 1; c < last; c++)
                        out.add(new Lesson(id++, "wf", c, WHOLE, bandT(first, WF_B), -1, 1,
                            false, u, merged, 1));
                }
            }
        }
        return out;
    }

    private Instance() {}
}
