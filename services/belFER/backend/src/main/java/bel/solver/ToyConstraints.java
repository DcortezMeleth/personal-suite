package bel.solver;

import ai.timefold.solver.core.api.score.buildin.hardsoft.HardSoftScore;
import ai.timefold.solver.core.api.score.stream.Constraint;
import ai.timefold.solver.core.api.score.stream.ConstraintFactory;
import ai.timefold.solver.core.api.score.stream.ConstraintProvider;
import ai.timefold.solver.core.api.score.stream.Joiners;

/**
 * Two of belFER's real hard constraints, in miniature: a teacher cannot be in
 * two places at once, and neither can a class.
 */
public class ToyConstraints implements ConstraintProvider {

    @Override
    public Constraint[] defineConstraints(ConstraintFactory factory) {
        return new Constraint[] {
            teacherDoubleBooked(factory),
            groupDoubleBooked(factory)
        };
    }

    private Constraint teacherDoubleBooked(ConstraintFactory factory) {
        return factory
            .forEachUniquePair(ToyLesson.class,
                Joiners.equal(ToyLesson::getSlot),
                Joiners.equal(ToyLesson::getTeacher))
            .penalize(HardSoftScore.ONE_HARD)
            .asConstraint("Teacher double-booked");
    }

    private Constraint groupDoubleBooked(ConstraintFactory factory) {
        return factory
            .forEachUniquePair(ToyLesson.class,
                Joiners.equal(ToyLesson::getSlot),
                Joiners.equal(ToyLesson::getStudentGroup))
            .penalize(HardSoftScore.ONE_HARD)
            .asConstraint("Student group double-booked");
    }
}
