package fr.lkdm.homelink.tasks.production;

import fr.lkdm.homelink.tasks.objective.CraftObjective;
import fr.lkdm.homelink.tasks.task.TaskCard;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Splits one finished batch across the cards it may legitimately advance.
 *
 * <p>A produced item is a real object and can only be counted once. Two cards each
 * wanting sixty-four cables do not both gain sixteen when sixteen cables are made:
 * together they gain sixteen. So the batch is shared, never duplicated.</p>
 *
 * <p>The producer's own explicitly tracked card comes first. Everything else follows a
 * fixed order — oldest card first, then card identity — so the same batch produces the
 * same split on any server and after any reload. Each card takes at most what it still
 * needs, and the surplus moves down the list until the batch runs out.</p>
 *
 * <p>Pinning a card to a HUD does not change this. Pinning is a personal display
 * choice; "track this craft" is the separate, deliberate one.</p>
 */
public final class ContributionAllocator {
    private ContributionAllocator() { }

    /**
     * One card's share of a batch.
     *
     * @param card card to credit
     * @param quantity finished items credited to it
     */
    public record Share(UUID card, int quantity) {
        /** Validates the share. */
        public Share {
            Objects.requireNonNull(card, "card");
            if (quantity <= 0) throw new IllegalArgumentException("A share credits at least one item");
        }
    }

    /**
     * Distributes a batch over the eligible cards.
     *
     * @param eligible cards the batch may advance, already filtered for policy and rights
     * @param tracked card the producer explicitly chose to track, when they chose one
     * @param quantity finished items really produced
     * @return shares in the order they were credited, never totalling more than the batch
     */
    public static List<Share> allocate(List<TaskCard> eligible, Optional<UUID> tracked, int quantity) {
        Objects.requireNonNull(eligible, "eligible");
        Objects.requireNonNull(tracked, "tracked");
        if (quantity <= 0 || eligible.isEmpty()) return List.of();

        List<TaskCard> ordered = new ArrayList<>(eligible);
        ordered.sort(Comparator.comparingLong(TaskCard::createdTick).thenComparing(TaskCard::id));
        tracked.ifPresent(chosen -> {
            for (int index = 0; index < ordered.size(); index++) {
                if (ordered.get(index).id().equals(chosen)) {
                    ordered.add(0, ordered.remove(index));
                    return;
                }
            }
        });

        List<Share> shares = new ArrayList<>();
        int left = quantity;
        for (TaskCard card : ordered) {
            if (left <= 0) break;
            CraftObjective objective = card.objective().orElse(null);
            if (objective == null) continue;
            int share = Math.min(left, objective.remaining());
            if (share <= 0) continue;
            shares.add(new Share(card.id(), share));
            left -= share;
        }
        return List.copyOf(shares);
    }
}
