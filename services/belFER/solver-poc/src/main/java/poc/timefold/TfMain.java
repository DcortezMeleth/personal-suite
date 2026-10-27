package poc.timefold;

import ai.timefold.solver.core.api.domain.common.DomainAccessType;
import ai.timefold.solver.core.api.solver.SolverFactory;
import ai.timefold.solver.core.config.solver.SolverConfig;
import ai.timefold.solver.core.config.solver.termination.TerminationConfig;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.IntStream;
import poc.Instance;
import poc.Verifier;

/**
 * Timefold over hours rather than over lessons.
 *
 * Rooms are handed out after the search rather than chosen during it. Nothing
 * is lost by that here: the rules have already made sure no hour asks for more
 * rooms of a kind than exist, and with that true, giving them out is a matter
 * of walking the list.
 */
public final class TfMain {

    public static void main(String[] args) {
        List<Instance.Lesson> spec = Instance.lessons();
        List<TfGroup> groups = TfGroup.of(spec);
        List<Integer> slots = IntStream.range(0, Instance.SLOTS).boxed().toList();

        SolverConfig config = new SolverConfig()
            .withSolutionClass(TfTimetable.class)
            .withEntityClasses(TfGroup.class)
            .withConstraintProviderClass(TfConstraints.class)
            .withDomainAccessType(DomainAccessType.REFLECTION)
            .withTerminationConfig(new TerminationConfig()
                .withSpentLimit(Duration.ofSeconds(seconds(args)))
                .withBestScoreLimit("0hard/0soft"));

        System.out.printf("%d classes, %d lessons, %d hours to choose, %d rooms%n",
            Instance.classCount(), spec.size(), groups.size(), Instance.rooms());

        long started = System.currentTimeMillis();
        TfTimetable solved = SolverFactory.<TfTimetable>create(config).buildSolver()
            .solve(new TfTimetable(slots, groups));
        long millis = System.currentTimeMillis() - started;

        Verifier.report("Timefold " + solved.getScore(), millis,
            Verifier.check(spec, place(spec, solved)));
    }

    /** Where every lesson ended up, with a room for the ones that need one. */
    private static List<Verifier.Placement> place(List<Instance.Lesson> spec, TfTimetable solved) {
        Map<Integer, Integer> startOf = new HashMap<>();
        for (TfGroup g : solved.getGroups())
            for (Instance.Lesson l : g.getMembers()) startOf.put(l.id(), g.getStartSlot());

        Set<String> taken = new HashSet<>();
        Map<Integer, Integer> roomOf = new HashMap<>();
        for (Instance.Lesson l : spec) {
            if (!TfGroup.books(l)) continue;
            int from = l.roomKind() == 0 ? 0 : Instance.ordinaryRooms();
            int to = l.roomKind() == 0 ? Instance.ordinaryRooms() : Instance.rooms();
            int start = startOf.getOrDefault(l.id(), 0);
            for (int room = from; room < to; room++) {
                boolean free = true;
                for (int s = start; s < start + l.length(); s++)
                    if (taken.contains(room + "@" + s)) { free = false; break; }
                if (!free) continue;
                for (int s = start; s < start + l.length(); s++) taken.add(room + "@" + s);
                roomOf.put(l.id(), room);
                break;
            }
        }

        List<Verifier.Placement> placements = new ArrayList<>();
        for (Instance.Lesson l : spec)
            placements.add(new Verifier.Placement(
                l.id(), startOf.getOrDefault(l.id(), 0), roomOf.getOrDefault(l.id(), 0)));
        return placements;
    }

    static int seconds(String[] args) {
        return args.length > 0 ? Integer.parseInt(args[0]) : 30;
    }

    private TfMain() {}
}
