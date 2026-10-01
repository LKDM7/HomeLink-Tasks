package fr.lkdm.homelink.tasks.task;

import fr.lkdm.homelink.tasks.objective.CraftObjective;
import java.util.Objects;
import java.util.Optional;

/**
 * The rules that decide whether a card may change column.
 *
 * <p>The server is authoritative here. A client may animate a drag optimistically, but
 * a refused move is corrected from this decision rather than left on screen.</p>
 *
 * <p>A manual card moves freely, because only a player ever finishes it. A crafting
 * card is different: its counter belongs to batches the server saw finish, so dragging
 * can neither forge progress nor throw it away.</p>
 */
public final class CardTransitions {
    private CardTransitions() { }

    /** Why a move was accepted or refused. */
    public enum Outcome {
        /** The move is allowed and was applied. */
        APPLIED,
        /** The card was already in that column. */
        UNCHANGED,
        /** A crafting card cannot be dropped into Done while items are still missing. */
        CRAFT_INCOMPLETE,
        /** A crafting card that has already produced something does not go back to To do. */
        CRAFT_ALREADY_STARTED;

        /** Whether the card actually changed column.
         * @return whether a status change happened
         */
        public boolean changed() { return this == APPLIED; }

        /** Whether the move was refused rather than merely redundant.
         * @return whether the player should be told why
         */
        public boolean refused() { return this == CRAFT_INCOMPLETE || this == CRAFT_ALREADY_STARTED; }
    }

    /**
     * Decides a player-requested move without changing anything.
     *
     * @param card card being dragged or moved through the accessible menu
     * @param target requested column
     * @return what the server would do
     */
    public static Outcome evaluate(TaskCard card, TaskStatus target) {
        Objects.requireNonNull(card, "card");
        Objects.requireNonNull(target, "target");
        if (card.status() == target) return Outcome.UNCHANGED;
        if (card.type() != TaskType.CRAFT) return Outcome.APPLIED;
        Optional<CraftObjective> objective = card.objective();
        // A crafting card without a configured objective still behaves like a plan, not a counter.
        if (objective.isEmpty()) return target == TaskStatus.DONE ? Outcome.CRAFT_INCOMPLETE : Outcome.APPLIED;
        CraftObjective craft = objective.get();
        if (craft.complete() && target != TaskStatus.DONE) return Outcome.CRAFT_ALREADY_STARTED;
        if (target == TaskStatus.DONE && !craft.complete()) return Outcome.CRAFT_INCOMPLETE;
        if (target == TaskStatus.TODO && craft.completedQuantity() > 0) return Outcome.CRAFT_ALREADY_STARTED;
        return Outcome.APPLIED;
    }

    /**
     * Applies a player-requested move when the rules allow it.
     *
     * <p>The crafting counter is never touched here: a refused move leaves it alone, and
     * an accepted one leaves it alone too.</p>
     *
     * @param card card to move
     * @param target requested column
     * @param tick server tick for the history entry
     * @param actor player requesting the move
     * @return what actually happened
     */
    public static Outcome apply(TaskCard card, TaskStatus target, long tick, java.util.UUID actor) {
        Outcome outcome = evaluate(card, target);
        if (outcome == Outcome.APPLIED) {
            card.setStatus(target);
            card.log().record(new ActivityEntry(ActivityEntry.Kind.STATUS_CHANGED,
                    Optional.ofNullable(actor), tick, 0));
        }
        return outcome;
    }

    /**
     * Moves a crafting card according to its own counter after production was credited.
     *
     * <p>The first admissible batch starts the card, and reaching the objective finishes
     * it. Both transitions happen once: a replayed receipt credits nothing, so it moves
     * nothing and notifies nobody a second time.</p>
     *
     * @param card crafting card that has just been credited
     * @param tick server tick for the history entry
     * @return whether the column changed
     */
    public static boolean applyProduction(TaskCard card, long tick) {
        Objects.requireNonNull(card, "card");
        CraftObjective craft = card.objective().orElse(null);
        if (craft == null || card.archived()) return false;
        if (craft.complete() && card.status() != TaskStatus.DONE) {
            card.setStatus(TaskStatus.DONE);
            card.log().record(new ActivityEntry(ActivityEntry.Kind.COMPLETED, Optional.empty(), tick, 0));
            return true;
        }
        if (!craft.complete() && craft.completedQuantity() > 0 && card.status() == TaskStatus.TODO) {
            card.setStatus(TaskStatus.IN_PROGRESS);
            card.log().record(new ActivityEntry(ActivityEntry.Kind.STATUS_CHANGED, Optional.empty(), tick, 0));
            return true;
        }
        return false;
    }
}
