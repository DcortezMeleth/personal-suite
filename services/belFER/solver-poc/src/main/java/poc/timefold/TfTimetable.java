package poc.timefold;

import ai.timefold.solver.core.api.domain.solution.PlanningEntityCollectionProperty;
import ai.timefold.solver.core.api.domain.solution.PlanningScore;
import ai.timefold.solver.core.api.domain.solution.PlanningSolution;
import ai.timefold.solver.core.api.domain.valuerange.ValueRangeProvider;
import ai.timefold.solver.core.api.score.buildin.hardsoft.HardSoftScore;
import java.util.List;

/**
 * A week, as a choice of hour for each thing that has to happen at one hour.
 *
 * No rooms: they are counted by the rules rather than chosen by the search,
 * and handed out once the hours are settled. One variable per group instead of
 * two per lesson is most of a factor of three on this instance before anything
 * else is considered.
 */
@PlanningSolution
public class TfTimetable {

    @ValueRangeProvider(id = "slots")
    private List<Integer> slots;

    @PlanningEntityCollectionProperty
    private List<TfGroup> groups;

    @PlanningScore
    private HardSoftScore score;

    public TfTimetable() {}

    public TfTimetable(List<Integer> slots, List<TfGroup> groups) {
        this.slots = slots;
        this.groups = groups;
    }

    public List<Integer> getSlots() { return slots; }
    public void setSlots(List<Integer> slots) { this.slots = slots; }
    public List<TfGroup> getGroups() { return groups; }
    public void setGroups(List<TfGroup> groups) { this.groups = groups; }
    public HardSoftScore getScore() { return score; }
    public void setScore(HardSoftScore score) { this.score = score; }
}
