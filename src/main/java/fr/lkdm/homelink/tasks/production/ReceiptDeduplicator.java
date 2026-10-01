package fr.lkdm.homelink.tasks.production;

import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * Remembers which batches were already credited.
 *
 * <p>A receipt may arrive more than once — a producer may re-emit one, and a world that
 * was reloaded may deliver a batch whose credit was already saved. Each transaction is
 * therefore credited exactly once, and a replayed receipt adds nothing and notifies
 * nobody a second time.</p>
 *
 * <p>The memory is bounded and persisted with the rest of the mod's data. Oldest
 * identities are forgotten once the bound is reached, which is safe because a receipt
 * is delivered close to the batch that produced it. This is honest about its limit:
 * between two saves of separate files an abrupt server kill can still lose the record
 * of one credit, and no world-wide atomicity is claimed here.</p>
 */
public final class ReceiptDeduplicator {
    /** Largest number of transaction identities kept. */
    public static final int MAX_ENTRIES = 4096;

    private final LinkedHashSet<UUID> seen = new LinkedHashSet<>();

    /**
     * Claims a transaction for crediting.
     *
     * @param transaction batch identity from the receipt
     * @return whether this batch had not been credited yet
     */
    public boolean claim(UUID transaction) {
        Objects.requireNonNull(transaction, "transaction");
        if (!seen.add(transaction)) return false;
        while (seen.size() > MAX_ENTRIES) {
            var oldest = seen.iterator();
            oldest.next();
            oldest.remove();
        }
        return true;
    }

    /** Whether a batch was already credited.
     * @param transaction batch identity
     * @return whether the identity is remembered
     */
    public boolean seen(UUID transaction) {
        return seen.contains(Objects.requireNonNull(transaction, "transaction"));
    }

    /** Returns the remembered identities, oldest first.
     * @return immutable snapshot for persistence
     */
    public List<UUID> entries() { return List.copyOf(seen); }

    /** Returns how many identities are remembered.
     * @return entry count
     */
    public int size() { return seen.size(); }

    /** Restores persisted identities, keeping the most recent ones within the bound.
     * @param restored persisted identities, oldest first
     */
    public void restore(Collection<UUID> restored) {
        seen.clear();
        for (UUID transaction : Objects.requireNonNull(restored, "restored")) {
            if (transaction != null) claim(transaction);
        }
    }

    /** Forgets every identity, for example when the owning server stops. */
    public void clear() { seen.clear(); }

    /** Returns the remembered identities as a set view for inspection.
     * @return immutable copy
     */
    public Set<UUID> asSet() { return Set.copyOf(seen); }
}
