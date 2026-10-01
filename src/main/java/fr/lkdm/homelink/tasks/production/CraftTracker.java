package fr.lkdm.homelink.tasks.production;

import fr.lkdm.homecore.api.production.ProductionReceipt;
import fr.lkdm.homelink.tasks.board.BoardPermissions;
import fr.lkdm.homelink.tasks.board.TaskBoard;
import fr.lkdm.homelink.tasks.objective.CraftObjective;
import fr.lkdm.homelink.tasks.task.CardTransitions;
import fr.lkdm.homelink.tasks.task.TaskCard;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.world.item.ItemStack;

/**
 * Credits crafting objectives from batches the server actually saw finish.
 *
 * <p>This is the only way a crafting card ever advances. Moving items between
 * inventories, taking them out of storage, trading, picking them up, {@code /give} and
 * the creative menu produce no receipt and therefore no progress, and no packet from a
 * client claiming to have crafted anything is believed.</p>
 *
 * <p>Tracking begins when a card's objective is activated. A batch started before that
 * moment belongs to an earlier piece of work and is never credited retroactively.</p>
 */
public final class CraftTracker {
    private final ReceiptDeduplicator deduplicator;

    /** Creates a tracker over a persistent deduplication memory.
     * @param deduplicator memory of already credited batches
     */
    public CraftTracker(ReceiptDeduplicator deduplicator) {
        this.deduplicator = Objects.requireNonNull(deduplicator, "deduplicator");
    }

    /** What the server needs to look up to credit a batch. */
    public interface Context {
        /**
         * Returns the crafting cards whose objective could accept this result.
         *
         * <p>Backed by an index keyed on the produced variant, so a batch never walks
         * every board of the server.</p>
         *
         * @param produced finished items
         * @return candidate cards, in any order
         */
        List<TaskCard> candidates(ItemStack produced);

        /** Returns the board a card belongs to.
         * @param boardId board identity
         * @return board, or empty when it no longer exists
         */
        Optional<TaskBoard> board(UUID boardId);

        /** Returns the card this player deliberately chose to track, which is not their pins.
         * @param player producing player
         * @return tracked card identity, or empty
         */
        Optional<UUID> trackedCard(UUID player);
        default Optional<UUID> currentEpoch() { return Optional.empty(); }
    }

    /**
     * Credits one receipt, at most once.
     *
     * @param receipt proof of a batch that really finished
     * @param context board and card lookups
     * @return the cards actually credited and by how much; empty when nothing was eligible
     */
    public List<ContributionAllocator.Share> credit(ProductionReceipt receipt, Context context) {
        Objects.requireNonNull(receipt, "receipt");
        Objects.requireNonNull(context, "context");
        // A replayed receipt credits nothing and re-notifies nobody.
        if (!deduplicator.claim(receipt.transactionId())) return List.of();

        ItemStack produced = receipt.result();
        List<TaskCard> eligible = new ArrayList<>();
        for (TaskCard card : context.candidates(produced)) {
            if (isEligible(card, receipt, context)) eligible.add(card);
        }
        if (eligible.isEmpty()) return List.of();

        var shares = ContributionAllocator.allocate(eligible, context.trackedCard(receipt.producer()),
                receipt.quantity());
        List<ContributionAllocator.Share> credited = new ArrayList<>(shares.size());
        for (var share : shares) {
            TaskCard card = eligible.stream().filter(candidate -> candidate.id().equals(share.card()))
                    .findFirst().orElse(null);
            if (card == null) continue;
            CraftObjective objective = card.objective().orElseThrow();
            int amount = objective.credit(receipt.producer(), share.quantity());
            if (amount <= 0) continue;
            card.touch();
            card.log().recordProgress(receipt.producer(), amount, receipt.completedTick());
            CardTransitions.applyProduction(card, receipt.completedTick());
            credited.add(new ContributionAllocator.Share(card.id(), amount));
        }
        return List.copyOf(credited);
    }

    private static boolean isEligible(TaskCard card, ProductionReceipt receipt, Context context) {
        CraftObjective objective = card.objective().orElse(null);
        if (objective == null || card.archived() || objective.complete()) return false;
        if (!objective.accepts(receipt.result())) return false;
        // A batch older than the card's activation belongs to work done before it existed.
        if (receipt.startedTick() < objective.activationTick()) return false;
        if (receipt.startedTick() == objective.activationTick()) {
            var activated = objective.activationStart().orElse(null);
            var started = receipt.start().orElse(null);
            if (activated != null && started != null && activated.epoch().equals(started.epoch())) {
                if (started.ordinal() <= activated.ordinal()) return false;
            } else if (activated != null || started != null) {
                if (started == null || !context.currentEpoch().filter(started.epoch()::equals).isPresent()) return false;
            } else if (context.currentEpoch().isPresent()) return false; // Legacy ambiguous starts fail conservatively.
        }
        Optional<net.minecraft.resources.ResourceLocation> required = objective.requiredRecipe();
        if (required.isPresent() && !required.equals(receipt.recipe())) return false;
        TaskBoard board = context.board(card.boardId()).orElse(null);
        return board != null && !board.archived() && BoardPermissions.canContribute(board, card, receipt.producer());
    }

    /** Returns the deduplication memory, for persistence.
     * @return memory of already credited batches
     */
    public ReceiptDeduplicator deduplicator() { return deduplicator; }
}
