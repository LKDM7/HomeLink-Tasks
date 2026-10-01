package fr.lkdm.homelink.tasks.stock;

/**
 * How a requirement, or a whole plan, is covered.
 *
 * <p>Every state is shown with its own label and icon as well as its colour, so the
 * information does not depend on being able to tell green from red.</p>
 */
public enum AvailabilityState {
    /** Green. Covered entirely from the viewing player's own inventory. */
    READY,
    /** Orange. The inventory is not enough, but verified authorised stock covers the rest. */
    IN_STORAGE,
    /**
     * Red. The verified sources of the selected scope are not enough, and the shortfall is
     * a fact rather than a gap in what could be observed.
     */
    MISSING,
    /**
     * Grey. A configured storage could not be observed, or the recipe is not supported, so
     * the answer is unknown. Unknown is never zero.
     */
    UNVERIFIED
}
