package fr.lkdm.homelink.tasks.task;

import java.util.ArrayDeque;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * A short, bounded history of what happened to a card.
 *
 * <p>The log never grows without limit and never records one line per item of a batch:
 * consecutive credits by the same player are folded into a single grouped entry. Oldest
 * entries are dropped once the bound is reached.</p>
 */
public final class ActivityLog {
    /** Largest number of entries one card keeps. */
    public static final int MAX_ENTRIES = 32;

    private final ArrayDeque<ActivityEntry> entries = new ArrayDeque<>();

    /** Records an entry, dropping the oldest one when the bound is reached.
     * @param entry entry to append
     */
    public void record(ActivityEntry entry) {
        entries.addLast(Objects.requireNonNull(entry, "entry"));
        while (entries.size() > MAX_ENTRIES) entries.removeFirst();
    }

    /**
     * Records credited production, grouping it with the previous credit by the same player.
     *
     * @param actor player the production is attributed to
     * @param amount credited quantity, ignored when not positive
     * @param tick server tick of the credit
     */
    public void recordProgress(UUID actor, int amount, long tick) {
        if (amount <= 0) return;
        Optional<UUID> who = Optional.of(Objects.requireNonNull(actor, "actor"));
        ActivityEntry last = entries.peekLast();
        if (last != null && last.kind() == ActivityEntry.Kind.PROGRESS && last.actor().equals(who)) {
            entries.removeLast();
            entries.addLast(last.mergeProgress(amount, tick));
            return;
        }
        record(new ActivityEntry(ActivityEntry.Kind.PROGRESS, who, tick, amount));
    }

    /** Returns the entries, oldest first.
     * @return immutable snapshot
     */
    public List<ActivityEntry> entries() { return List.copyOf(entries); }

    /** Replaces the whole log, keeping only the most recent bounded entries.
     * @param restored persisted entries, oldest first
     */
    public void restore(List<ActivityEntry> restored) {
        entries.clear();
        for (ActivityEntry entry : Objects.requireNonNull(restored, "restored")) record(entry);
    }

    /** Returns how many entries are kept.
     * @return entry count
     */
    public int size() { return entries.size(); }
}
