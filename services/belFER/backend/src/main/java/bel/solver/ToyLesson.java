package bel.solver;

import ai.timefold.solver.core.api.domain.entity.PlanningEntity;
import ai.timefold.solver.core.api.domain.lookup.PlanningId;
import ai.timefold.solver.core.api.domain.variable.PlanningVariable;

/**
 * One lesson to be placed. The slot is what the solver decides; the teacher and
 * the student group are given.
 */
@PlanningEntity
public class ToyLesson {

    @PlanningId
    private String id;

    private String teacher;
    private String studentGroup;

    @PlanningVariable
    private ToySlot slot;

    /** Required: Timefold instantiates entities reflectively when cloning. */
    public ToyLesson() {
    }

    public ToyLesson(String id, String teacher, String studentGroup) {
        this.id = id;
        this.teacher = teacher;
        this.studentGroup = studentGroup;
    }

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getTeacher() {
        return teacher;
    }

    public void setTeacher(String teacher) {
        this.teacher = teacher;
    }

    public String getStudentGroup() {
        return studentGroup;
    }

    public void setStudentGroup(String studentGroup) {
        this.studentGroup = studentGroup;
    }

    public ToySlot getSlot() {
        return slot;
    }

    public void setSlot(ToySlot slot) {
        this.slot = slot;
    }

    @Override
    public String toString() {
        return id + "(" + teacher + "/" + studentGroup + ")@" + slot;
    }
}
