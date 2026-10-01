package fr.lkdm.homelink.tasks.task;

import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * One line of a card's history.
 *
 * <p>Entries never disclose anything the reader could not already see on the card,
 * so a log line about progress carries a quantity and not a storage location.</p>
 *
 * @param kind what happened
 * @param actor player responsible, empty for an automatic transition
 * @param tick server tick the entry was recorded at
 * @param amount quantity for a grouped progress entry, zero otherwise
 */
public record ActivityEntry(Kind kind, Optional<UUID> actor, long tick, int amount) {
    /** Kinds of entry a bounded log keeps. */
    public enum Kind {
        /** The card was created. */
        CREATED,
        /** Its status changed. */
        STATUS_CHANGED,
        /** Somebody was assigned or unassigned. */
        ASSIGNED,
        /** Production was credited; consecutive credits are grouped into one entry. */
        PROGRESS,
        /** The objective was reached. */
        COMPLETED,
        /** The tracked item or recipe was reset through an explicit, confirmed action. */
        OBJECTIVE_RESET
    }

    /** Validates the entry. */
    public ActivityEntry {
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(actor, "actor");
        if (tick < 0 || amount < 0) throw new IllegalArgumentException("An activity entry cannot be negative");
    }

    /** Returns a copy with more quantity folded into a grouped progress entry.
     * @param extra additional credited quantity
     * @param at server tick of the newer credit
     * @return merged entry
     */
    public ActivityEntry mergeProgress(int extra, long at) {
        if (kind != Kind.PROGRESS) throw new IllegalStateException("Only progress entries are grouped");
        return new ActivityEntry(kind, actor, Math.max(tick, at), amount + Math.max(0, extra));
    }
}
