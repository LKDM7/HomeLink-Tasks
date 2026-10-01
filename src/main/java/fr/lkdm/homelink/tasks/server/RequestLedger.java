package fr.lkdm.homelink.tasks.server;

import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.UUID;

/** Bounds mutations and remembers operation IDs for the lifetime of a connection. */
public final class RequestLedger {
    public enum Result { ACCEPTED, DUPLICATE, THROTTLED }
    private final Map<UUID, Window> windows = new HashMap<>();
    public Result claim(UUID player, UUID operation, long tick) {
        Window window = windows.computeIfAbsent(player, ignored -> new Window());
        if (window.operations.contains(operation)) return Result.DUPLICATE;
        if (tick - window.start >= 20) { window.start = tick; window.used = 0; }
        if (window.used >= 32) return Result.THROTTLED;
        window.used++;
        window.operations.add(operation);
        if (window.operations.size() > 128) window.operations.remove(window.operations.iterator().next());
        return Result.ACCEPTED;
    }
    public void forget(UUID player) { windows.remove(player); }
    private static final class Window {
        private long start = -20;
        private int used;
        private final LinkedHashSet<UUID> operations = new LinkedHashSet<>();
    }
}
