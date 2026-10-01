package fr.lkdm.homelink.tasks.server;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * Keeps one player's requests from costing the server more than their share.
 *
 * <p>Availability is recomputed for the cards a player is actually looking at or has
 * pinned, at a bounded rate. A client that asks faster is throttled rather than served,
 * so an open screen cannot turn into a packet per frame or a recalculation per tick.</p>
 */
public final class RequestBudget {
    /** Minimum number of ticks between two recalculations for one player. */
    public static final int MIN_INTERVAL_TICKS = 10;
    /** Largest number of recalculations one player may trigger within a window. */
    public static final int MAX_PER_WINDOW = 8;
    /** Length of the counting window, in ticks. */
    public static final int WINDOW_TICKS = 100;

    private final Map<UUID, Window> windows = new HashMap<>();

    /** Creates an empty budget. */
    public RequestBudget() { }

    /**
     * Claims one unit of a player's budget.
     *
     * @param player authenticated player
     * @param tick current server tick
     * @return whether the request may proceed
     */
    public boolean claim(UUID player, long tick) {
        Objects.requireNonNull(player, "player");
        Window window = windows.computeIfAbsent(player, ignored -> new Window(tick));
        if (tick - window.start >= WINDOW_TICKS) {
            window.start = tick;
            window.used = 0;
        }
        if (tick - window.last < MIN_INTERVAL_TICKS) return false;
        if (window.used >= MAX_PER_WINDOW) return false;
        window.used++;
        window.last = tick;
        return true;
    }

    /** Forgets a player's budget, for example when they disconnect.
     * @param player player identity
     */
    public void forget(UUID player) { windows.remove(Objects.requireNonNull(player, "player")); }

    /** Forgets every player's budget. */
    public void clear() { windows.clear(); }

    private static final class Window {
        private long start;
        private long last = Long.MIN_VALUE / 2;
        private int used;

        private Window(long start) { this.start = start; }
    }
}
