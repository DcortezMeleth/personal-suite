package bel.solver;

import java.util.List;

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
    Settings settings
) {

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
     * @param allowClassGaps when false a class's day is a solid block starting
     *   at the first slot
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
        boolean allowClassGaps,
        int targetDayLength,
        int dayLengthWeight,
        long timeLimitSeconds,
        int workers,
        java.util.Set<Rule> relaxed
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
        /** Both halves of a class busy at the same time. */
        HALVES_TOGETHER,
        /** Every class a cross-class unit touches moves with it. */
        CROSS_CLASS_TOGETHER
    }
}
