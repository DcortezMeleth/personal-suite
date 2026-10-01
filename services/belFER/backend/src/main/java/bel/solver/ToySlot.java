package bel.solver;

/** A problem fact: one teaching slot on one day. */
public final class ToySlot {

    private final int day;
    private final int period;

    public ToySlot(int day, int period) {
        this.day = day;
        this.period = period;
    }

    public int getDay() {
        return day;
    }

    public int getPeriod() {
        return period;
    }

    @Override
    public String toString() {
        return "d" + day + "p" + period;
    }
}
