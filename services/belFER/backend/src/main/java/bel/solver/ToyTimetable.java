package bel.solver;

import ai.timefold.solver.core.api.domain.solution.PlanningEntityCollectionProperty;
import ai.timefold.solver.core.api.domain.solution.PlanningScore;
import ai.timefold.solver.core.api.domain.solution.PlanningSolution;
import ai.timefold.solver.core.api.domain.solution.ProblemFactCollectionProperty;
import ai.timefold.solver.core.api.domain.valuerange.ValueRangeProvider;
import ai.timefold.solver.core.api.score.buildin.hardsoft.HardSoftScore;
import java.util.List;

/** The toy problem and, once solved, its answer. */
@PlanningSolution
public class ToyTimetable {

    @ValueRangeProvider
    @ProblemFactCollectionProperty
    private List<ToySlot> slots;

    @PlanningEntityCollectionProperty
    private List<ToyLesson> lessons;

    @PlanningScore
    private HardSoftScore score;

    /** Required: Timefold instantiates the solution reflectively when cloning. */
    public ToyTimetable() {
    }

    public ToyTimetable(List<ToySlot> slots, List<ToyLesson> lessons) {
        this.slots = slots;
        this.lessons = lessons;
    }

    public List<ToySlot> getSlots() {
        return slots;
    }

    public void setSlots(List<ToySlot> slots) {
        this.slots = slots;
    }

    public List<ToyLesson> getLessons() {
        return lessons;
    }

    public void setLessons(List<ToyLesson> lessons) {
        this.lessons = lessons;
    }

    public HardSoftScore getScore() {
        return score;
    }

    public void setScore(HardSoftScore score) {
        this.score = score;
    }
}
