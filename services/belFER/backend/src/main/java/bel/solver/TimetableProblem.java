package bel.solver;

import java.util.List;
import java.util.Map;

/**
 * Everything the solver needs, as plain data.
 *
 * This is the boundary. No solver type appears here and nothing above it knows
 * which engine is underneath — see ADR 0001. Ids are strings rather than UUIDs
 * for the same reason: the Scala side owns identity, this side only has to
 * carry it back out unchanged.
 *
 * The problem describes **one week**. The arkusz's week ranges are resolved
 * before anything reaches here, so nothing below is time-aware (REQUIREMENTS
 * §4.1).
 */
public record TimetableProblem(
    int days,
    int slotsPerDay,
    List<Lesson> lessons,
    List<Teacher> teachers,
    List<Room> rooms,
    Settings settings,
    /**
     * Where lessons were last time, by lesson id. Not a constraint — CP-SAT
     * repairs whatever does not fit — but its large-neighbourhood workers are
     * far better at improving an arrangement than at finding a first one, and
     * at school size that is the difference between an answer and none.
     *
     * Phase 7 wants the same machinery to build a plan resembling the previous
     * one, so this is where that will attach.
     */
    Map<String, Integer> hints,
    /**
     * How many lessons each class has on each day, when that has already been
     * decided. Because a class's day is unbroken, these numbers fix exactly
     * which slots it occupies — so settling them first turns "where does every
     * lesson go" from a search over shapes and placements at once into a
     * placement problem with the shape already known.
     *
     * Empty means the solver works it out, which is what it does today.
     */
    Map<String, int[]> shape
) {

    public TimetableProblem(int days, int slotsPerDay, List<Lesson> lessons,
                            List<Teacher> teachers, List<Room> rooms, Settings settings,
                            Map<String, Integer> hints) {
        this(days, slotsPerDay, lessons, teachers, rooms, settings, hints, Map.of());
    }

    public TimetableProblem(int days, int slotsPerDay, List<Lesson> lessons,
                            List<Teacher> teachers, List<Room> rooms, Settings settings) {
        this(days, slotsPerDay, lessons, teachers, rooms, settings, Map.of(), Map.of());
    }

    /** Whole class, or one of the two halves it is always split into. */
    public enum Audience { WHOLE_CLASS, GROUP_1, GROUP_2 }

    /**
     * One block to place: a stretch of consecutive slots on one day.
     *
     * A lesson line of four hours shaped 1+1+2 arrives as three of these. The
     * solver places blocks, so this is the unit, not the line.
     *
     * @param supportTeacherId a second teacher present for every hour, booked
     *   exactly like the first; null when there is none
     * @param crossClassUnitId lessons sharing a unit must start in the same
     *   slot, because every class they touch is occupied for it
     * @param crossClassGroupId lessons sharing a group are one taught group
     *   drawn from several classes: one teacher, one room, every class in it
     *   occupied. This is the partial merge the school runs — one class's
     *   girls taught alone while its boys join another's.
     * @param requiredRoomKindId null when any ordinary room will do
     */
    public record Lesson(
        String id,
        String classId,
        String subjectId,
        Audience audience,
        String teacherId,
        String supportTeacherId,
        int length,
        boolean optional,
        String crossClassUnitId,
        String crossClassGroupId,
        String requiredRoomKindId
    ) {}

    /**
     * @param unavailable when the teacher is elsewhere, as (day, first, last)
     *   positions inclusive
     */
    public record Teacher(
        String id,
        int maxWorkingDays,
        int maxLessonsPerDay,
        List<Blocked> unavailable
    ) {}

    public record Blocked(int day, int fromPosition, int toPosition) {}

    public record Room(String id, String kindId) {}

    /**
     * @param maxConsecutiveTeacherGaps a run longer than this between two of a
     *   teacher's lessons on one day is rejected
     * A class's day is always a solid block — a student has nowhere to be
     * during a free period, so there is nothing to configure.
     * @param halvesApartWeight what it costs, per slot, to leave one half of
     *   a class without a lesson while the other has one. Not forbidden — one
     *   teacher sometimes takes both halves of a split and cannot teach them
     *   at once — but rare and unwanted, so it is paid for. Contiguity does
     *   the rest: a half with a hole in its day is impossible, so the hours it
     *   misses can only be at the start or the end, and that half simply comes
     *   in later or goes home earlier.
     * @param targetDayLength hours past this cost {@code dayLengthWeight} each
     *   — see ADR 0002 on why the preference is stated this way and not as a
     *   longest-minus-shortest spread
     */
    /**
     * @param relaxed rules to leave out of the model. Not a feature for the
     *   planner — it is how an infeasible run is explained: drop one rule, see
     *   whether a plan appears, and the one that unblocks it is the one to
     *   argue with. UC-32 wants better than this eventually, but "which rule
     *   is it" is most of the answer and costs one extra solve.
     */
    public record Settings(
        int maxConsecutiveTeacherGaps,
        int targetDayLength,
        int dayLengthWeight,
        int halvesApartWeight,
        long timeLimitSeconds,
        int workers,
        java.util.Set<Rule> relaxed,
        /**
         * Whether a lesson may be left unplaced.
         *
         * Filling a week one day at a time means asking where today's lessons
         * go while the rest of the week waits its turn, so the solver has to
         * be allowed to answer "not today" — which it cannot when every lesson
         * must land somewhere. The shape still says how many hours each class
         * has today, so "not today" never degenerates into "nowhere".
         */
        boolean partial,
        /** How far a day may stray from the length the shape gave it. */
        int shapeSlack,
        /**
         * Whether a day that will not go in should say which lessons are at
         * fault rather than only that it failed.
         *
         * Every lesson is then placed under an assumption the solver is free
         * to blame, so an impossible day comes back with the handful of
         * lessons that cannot share it — which is the one thing a planner
         * working in counts alone can never work out for itself.
         */
        boolean explain
    ) {
        public boolean has(Rule rule) { return !relaxed.contains(rule); }
    }

    /** The rules that can be dropped one at a time to explain an infeasibility. */
    public enum Rule {
        CLASS_DAY_CONTIGUOUS,
        OPTIONAL_AT_DAY_EDGE,
        ONE_BLOCK_PER_SUBJECT_PER_DAY,
        TEACHER_WORKING_DAYS,
        TEACHER_LESSONS_PER_DAY,
        TEACHER_GAP_LIMIT,
        ROOM_KIND,
        /** Enough rooms of any sort to go round, slot by slot. */
        ROOM_CAPACITY,
        /** Every class a cross-class unit touches moves with it. */
        CROSS_CLASS_TOGETHER
    }
}
