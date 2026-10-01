package fr.lkdm.homelink.tasks.stock;

import fr.lkdm.homecore.api.stock.StockAccess;
import java.util.List;
import java.util.Objects;

/**
 * What a viewing player needs for the operations a card still requires.
 *
 * <p>A plan is a calculation, not a transaction: nothing was withdrawn, moved or
 * reserved to produce it, and the quantities are what was observed at
 * {@link #observedTick()}. Another player may use the same resources a moment later.</p>
 *
 * <p>Two players looking at the same shared card get different plans, because the
 * inventory part is theirs. The card's own production counter stays common to all.</p>
 *
 * @param ingredients per-requirement allocation, in the recipe's own order
 * @param state how the plan as a whole should be presented
 * @param access whether the player may also take the stock part out
 * @param storageConfigured whether any storage was configured for this calculation
 * @param observedTick server tick the stock part was observed at
 */
public record AvailabilityPlan(List<IngredientAvailability> ingredients, AvailabilityState state,
                               StockAccess access, boolean storageConfigured, long observedTick) {
    /** Validates the plan and copies its allocations. */
    public AvailabilityPlan {
        ingredients = List.copyOf(Objects.requireNonNull(ingredients, "ingredients"));
        Objects.requireNonNull(state, "state");
        Objects.requireNonNull(access, "access");
    }

    /**
     * Returns the plan of a card whose objective is already reached.
     *
     * <p>A finished objective asks for nothing: the materials it consumed are not
     * requested again.</p>
     *
     * @param observedTick server tick
     * @return empty, ready plan
     */
    public static AvailabilityPlan finished(long observedTick) {
        return new AvailabilityPlan(List.of(), AvailabilityState.READY, StockAccess.READ_ONLY, false, observedTick);
    }

    /**
     * Returns the plan of a recipe this version cannot describe safely.
     *
     * <p>An unsupported recipe is unknown, not empty: it must never be shown as a
     * satisfied plan or as a proven shortfall.</p>
     *
     * @param observedTick server tick
     * @return unverified plan
     */
    public static AvailabilityPlan unsupported(long observedTick) {
        return new AvailabilityPlan(List.of(), AvailabilityState.UNVERIFIED, StockAccess.READ_ONLY, false, observedTick);
    }

    /** Whether every requirement is accounted for.
     * @return whether nothing is missing and nothing is unverified
     */
    public boolean satisfied() { return state == AvailabilityState.READY || state == AvailabilityState.IN_STORAGE; }
}
