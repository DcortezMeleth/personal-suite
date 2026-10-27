package bel.solver;

import java.util.List;

/**
 * Where everything ended up, or why it did not.
 *
 * @param penalty the weighted soft-constraint total; 0 is the ideal
 * @param detail what to tell the user when there is no timetable
 */
public record TimetableSolution(
    Status status,
    List<Placement> placements,
    long millis,
    long penalty,
    String detail,
    /** Lessons that cannot share a day, when the solver was asked to explain. */
    List<String> core
) {

    public TimetableSolution(Status status, List<Placement> placements, long millis,
                             long penalty, String detail) {
        this(status, placements, millis, penalty, detail, List.of());
    }

    public enum Status {
        /** Solved, and no better arrangement exists under the given weights. */
        OPTIMAL,
        /** A usable timetable, not proven best — what a cut-off run returns. */
        FEASIBLE,
        /** No arrangement can satisfy the rules. The data has to change. */
        INFEASIBLE,
        /** Ran out of time without finding anything. */
        UNKNOWN
    }

    /** @param roomId the room the whole block occupies */
    public record Placement(String lessonId, int startSlot, String roomId) {}

    public boolean solved() {
        return status == Status.OPTIMAL || status == Status.FEASIBLE;
    }
}
