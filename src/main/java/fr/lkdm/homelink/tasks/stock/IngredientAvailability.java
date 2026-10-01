package fr.lkdm.homelink.tasks.stock;

import java.util.Objects;

/**
 * How one requirement of a recipe is covered by a plan.
 *
 * <p>The parts add up to the requirement: what is allocated from the player's own
 * inventory, what is allocated from authorised stock, and what is missing. No unit is
 * counted in two parts, and no unit is reused for two different requirements.</p>
 *
 * @param required units needed for the remaining operations
 * @param fromInventory units allocated from the viewing player's inventory
 * @param fromStock units allocated from verified authorised stock
 * @param missing units nothing accounted for
 * @param state how this requirement should be presented
 */
public record IngredientAvailability(int required, int fromInventory, int fromStock, int missing,
                                     AvailabilityState state) {
    /** Validates that the parts describe exactly the requirement. */
    public IngredientAvailability {
        Objects.requireNonNull(state, "state");
        if (required < 0 || fromInventory < 0 || fromStock < 0 || missing < 0) {
            throw new IllegalArgumentException("An allocation cannot be negative");
        }
        if (fromInventory + fromStock + missing != required) {
            throw new IllegalArgumentException("Allocation parts must add up to the requirement");
        }
    }

    /** Returns the units accounted for.
     * @return allocated units
     */
    public int allocated() { return fromInventory + fromStock; }
}
