package fr.lkdm.homelink.tasks.server;

/** Server-wide ceiling, including HUD work that has no incoming request. */
public final class CalculationBudget {
    public static final int MAX_PER_TICK = 8;
    public static final int PROJECT_COST = 4;
    private long tick = Long.MIN_VALUE;
    private int used;
    private long accepted, refused;

    public boolean claim(long now) {
        return claim(now, 1);
    }

    public boolean claim(long now, int cost) {
        if (cost < 1 || cost > MAX_PER_TICK) throw new IllegalArgumentException("Invalid calculation cost");
        if (now != tick) { tick = now; used = 0; }
        if (used + cost > MAX_PER_TICK) { refused++; return false; }
        used += cost; accepted++; return true;
    }

    public long accepted() { return accepted; }
    public long refused() { return refused; }
}
