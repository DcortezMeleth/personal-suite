package poc.timefold;

import ai.timefold.solver.core.api.domain.entity.PlanningEntity;
import ai.timefold.solver.core.api.domain.lookup.PlanningId;
import ai.timefold.solver.core.api.domain.variable.PlanningVariable;
import java.util.ArrayList;
import java.util.List;
import poc.Instance;

/**
 * The lessons that must happen at one and the same hour, and the hour.
 *
 * This is the whole of what the first comparison got wrong about Timefold. The
 * entity was a lesson, so a move changed one lesson's hour, and the two halves
 * of a split have to move together or not at all — every single move broke a
 * pair and every repair broke another. The note made at the time said as much:
 * the ceiling was the model's and not the engine's.
 *
 * Nothing about that needed a cleverer move. It needed the thing that must stay
 * together to be the thing that moves: one hour, chosen once, for the whole of
 * a split or the whole of a cross-class block. The rules that said "these two
 * must coincide" then have nothing left to say, because there is no longer a
 * way to express their coinciding failing.
 */
@PlanningEntity
public class TfGroup {

    @PlanningId private int id;
    private List<Instance.Lesson> members = new ArrayList<>();
    private int length;

    @PlanningVariable(valueRangeProviderRefs = "slots")
    private Integer startSlot;

    public TfGroup() {
    }

    public TfGroup(int id, List<Instance.Lesson> members) {
        this.id = id;
        this.members = members;
        this.length = members.stream().mapToInt(Instance.Lesson::length).max().orElse(1);
    }

    public int getId() { return id; }
    public void setId(int id) { this.id = id; }
    public List<Instance.Lesson> getMembers() { return members; }
    public void setMembers(List<Instance.Lesson> members) { this.members = members; }
    public int getLength() { return length; }
    public void setLength(int length) { this.length = length; }
    public Integer getStartSlot() { return startSlot; }
    public void setStartSlot(Integer startSlot) { this.startSlot = startSlot; }

    public int endSlotExclusive() { return startSlot + length; }
    public int day() { return Instance.day(startSlot); }

    public boolean withinOneDay() {
        return Instance.day(startSlot) == Instance.day(startSlot + length - 1);
    }

    public boolean overlaps(TfGroup other) {
        return startSlot < other.endSlotExclusive() && other.startSlot < endSlotExclusive();
    }

    /**
     * Which lessons must share an hour.
     *
     * Both halves of a split, unless one teacher takes both of them — she
     * cannot be in two rooms at once, so that pair is the one case where the
     * halves have to come apart, and it is a real one in this school. And every
     * class's share of a cross-class block, which is simultaneous by
     * definition.
     */
    public static List<TfGroup> of(List<Instance.Lesson> lessons) {
        int[] parent = new int[lessons.size()];
        for (int i = 0; i < parent.length; i++) parent[i] = i;

        for (int i = 0; i < lessons.size(); i++)
            for (int j = i + 1; j < lessons.size(); j++) {
                Instance.Lesson a = lessons.get(i);
                Instance.Lesson b = lessons.get(j);
                if (a.crossClassUnit() >= 0 && a.crossClassUnit() == b.crossClassUnit())
                    union(parent, i, j);
            }

        // The two halves of a class take their hours side by side, and which
        // of one half's lessons sits against which of the other's is a choice
        // rather than a given. It matters that it is: the halves do not take
        // the same subject at the same time. One sits angielski while the
        // other sits niemiecki and then they swap, which is the arrangement
        // this school runs and the one its old software could not express.
        //
        // Pairing them in the order they were asked for reproduces exactly
        // that, and spends nothing on deciding it. A pair whose two halves
        // share a teacher is left alone, because she cannot teach both at once.
        for (int c = 0; c < Instance.classCount(); c++) {
            List<Integer> first = new ArrayList<>();
            List<Integer> second = new ArrayList<>();
            for (int i = 0; i < lessons.size(); i++) {
                Instance.Lesson l = lessons.get(i);
                if (l.classIndex() != c || l.crossClassUnit() >= 0) continue;
                if (l.group() == Instance.GROUP_1) first.add(i);
                else if (l.group() == Instance.GROUP_2) second.add(i);
            }
            for (int k = 0; k < Math.min(first.size(), second.size()); k++) {
                Instance.Lesson a = lessons.get(first.get(k));
                Instance.Lesson b = lessons.get(second.get(k));
                if (a.length() == b.length() && a.teacher() != b.teacher())
                    union(parent, first.get(k), second.get(k));
            }
        }

        List<List<Instance.Lesson>> buckets = new ArrayList<>();
        int[] where = new int[lessons.size()];
        java.util.Arrays.fill(where, -1);
        for (int i = 0; i < lessons.size(); i++) {
            int root = find(parent, i);
            if (where[root] < 0) {
                where[root] = buckets.size();
                buckets.add(new ArrayList<>());
            }
            buckets.get(where[root]).add(lessons.get(i));
        }

        List<TfGroup> groups = new ArrayList<>();
        for (int i = 0; i < buckets.size(); i++) groups.add(new TfGroup(i, buckets.get(i)));
        return groups;
    }

    /**
     * Whether a lesson books a room and a teacher of its own.
     *
     * A class's share of a cross-class block does not — the groups being
     * taught book those — and asking the question lesson by lesson rescans
     * every lesson each time, which is cubic work inside a rule that runs over
     * every pair of hours. Asked once, here.
     */
    private static final java.util.Set<Integer> BOOKERS = bookerIds();

    private static java.util.Set<Integer> bookerIds() {
        List<Instance.Lesson> all = Instance.lessons();
        boolean[] flags = Instance.bookers(all);
        java.util.Set<Integer> ids = new java.util.HashSet<>();
        for (int i = 0; i < all.size(); i++) if (flags[i]) ids.add(all.get(i).id());
        return ids;
    }

    public static boolean books(Instance.Lesson lesson) { return BOOKERS.contains(lesson.id()); }

    private static int find(int[] parent, int i) {
        while (parent[i] != i) {
            parent[i] = parent[parent[i]];
            i = parent[i];
        }
        return i;
    }

    private static void union(int[] parent, int i, int j) {
        int a = find(parent, i);
        int b = find(parent, j);
        if (a != b) parent[a] = b;
    }
}
