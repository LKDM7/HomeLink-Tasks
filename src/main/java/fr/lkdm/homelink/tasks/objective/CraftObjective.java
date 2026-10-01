package fr.lkdm.homelink.tasks.objective;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;

/**
 * A crafting objective counted in finished items.
 *
 * <p>The quantity counts finished objects, never clicks, operations or validated
 * prototypes. Progress only ever comes from a batch the server saw finish, so holding,
 * withdrawing or picking up an item adds nothing here.</p>
 *
 * <p>Tracking starts at {@link #activationTick()}: a batch started before that tick is
 * older than the objective and is never credited retroactively.</p>
 */
public final class CraftObjective {
    /** Largest objective this version accepts. */
    public static final int MAX_QUANTITY = 4096;

    private final ItemStack target;
    private final long activationTick;
    private fr.lkdm.homecore.api.production.ProductionStart activationStart;
    public Optional<fr.lkdm.homecore.api.production.ProductionStart> activationStart() { return Optional.ofNullable(activationStart); }
    public void setActivationStart(fr.lkdm.homecore.api.production.ProductionStart start) {
        if (activationStart != null) throw new IllegalStateException("Activation order is immutable");
        activationStart = Objects.requireNonNull(start, "start");
    }
    private ResourceLocation recipeId;
    private boolean lockedToRecipe;
    private ContributionPolicy policy;
    private int targetQuantity;
    private int completedQuantity;
    private final Map<UUID, Integer> contributions = new LinkedHashMap<>();

    /**
     * Creates an objective that starts empty.
     *
     * @param target wanted item; its components are part of its identity
     * @param targetQuantity finished items wanted, 1 to {@value #MAX_QUANTITY}
     * @param recipeId reference recipe chosen for planning
     * @param policy who may contribute
     * @param activationTick server tick tracking starts at
     */
    public CraftObjective(ItemStack target, int targetQuantity, ResourceLocation recipeId,
                          ContributionPolicy policy, long activationTick) {
        Objects.requireNonNull(target, "target");
        if (target.isEmpty()) throw new IllegalArgumentException("A crafting objective needs a target item");
        if (targetQuantity < 1 || targetQuantity > MAX_QUANTITY) {
            throw new IllegalArgumentException("Objective quantity out of bounds: " + targetQuantity);
        }
        if (activationTick < 0) throw new IllegalArgumentException("Activation tick must not be negative");
        this.target = target.copyWithCount(1);
        this.targetQuantity = targetQuantity;
        this.recipeId = Objects.requireNonNull(recipeId, "recipeId");
        this.policy = Objects.requireNonNull(policy, "policy");
        this.activationTick = activationTick;
    }

    /** Returns a fresh copy of the wanted item prototype.
     * @return count-one prototype
     */
    public ItemStack target() { return target.copy(); }

    /** Tests whether a produced stack satisfies this objective.
     * Components are compared, so two items are not merged because they share a name.
     * @param produced finished items to check
     * @return whether the produced variant is the wanted one
     */
    public boolean accepts(ItemStack produced) {
        return !produced.isEmpty() && ItemStack.isSameItemSameComponents(target, produced);
    }

    /** Returns the reference recipe chosen for planning.
     * @return recipe identifier
     */
    public ResourceLocation recipeId() { return recipeId; }

    /** Chooses another reference recipe without touching progress.
     * @param recipe recipe identifier
     */
    public void selectRecipe(ResourceLocation recipe) { recipeId = Objects.requireNonNull(recipe, "recipe"); }

    /** Whether only the selected recipe may contribute.
     * @return whether contributions are restricted to {@link #recipeId()}
     */
    public boolean lockedToRecipe() { return lockedToRecipe; }

    /** Restricts or reopens contributions to the selected recipe.
     * @param locked whether only the selected recipe counts
     */
    public void setLockedToRecipe(boolean locked) { lockedToRecipe = locked; }

    /** Returns who may contribute.
     * @return contribution policy
     */
    public ContributionPolicy policy() { return policy; }

    /** Changes who may contribute.
     * @param value new policy
     */
    public void setPolicy(ContributionPolicy value) { policy = Objects.requireNonNull(value, "policy"); }

    /** Returns the server tick tracking started at.
     * @return activation tick
     */
    public long activationTick() { return activationTick; }

    /** Returns the number of finished items wanted.
     * @return target quantity
     */
    public int targetQuantity() { return targetQuantity; }

    /** Returns the number of finished items already credited.
     * @return completed quantity
     */
    public int completedQuantity() { return completedQuantity; }

    /** Returns how many finished items are still missing.
     * @return remaining quantity, never negative
     */
    public int remaining() { return Math.max(0, targetQuantity - completedQuantity); }

    /** Whether the objective is reached.
     * @return whether nothing remains
     */
    public boolean complete() { return remaining() == 0; }

    /** Returns credited quantities per contributing player.
     * @return immutable view of contributions
     */
    public Map<UUID, Integer> contributions() { return Collections.unmodifiableMap(contributions); }

    /**
     * Credits finished items to a contributor, never beyond what is still needed.
     *
     * <p>Different players' contributions add up. The surplus of a batch is not lost
     * here; the caller passes it on to the next eligible card instead.</p>
     *
     * @param contributor player the batch is attributed to
     * @param quantity finished items offered, must be positive
     * @return quantity actually credited, zero when nothing remains
     */
    public int credit(UUID contributor, int quantity) {
        Objects.requireNonNull(contributor, "contributor");
        if (quantity <= 0) return 0;
        int credited = Math.min(quantity, remaining());
        if (credited == 0) return 0;
        completedQuantity += credited;
        contributions.merge(contributor, credited, Integer::sum);
        return credited;
    }

    /**
     * Raises or lowers the wanted quantity without ever discarding progress.
     *
     * @param quantity new target, 1 to {@value #MAX_QUANTITY}
     */
    public void setTargetQuantity(int quantity) {
        if (quantity < 1 || quantity > MAX_QUANTITY) {
            throw new IllegalArgumentException("Objective quantity out of bounds: " + quantity);
        }
        targetQuantity = quantity;
    }

    /** Restores persisted progress without replaying contributions one by one.
     * @param completed finished items already credited
     * @param persisted per-player credited quantities
     */
    public void restore(int completed, Map<UUID, Integer> persisted) {
        completedQuantity = Math.clamp(completed, 0, targetQuantity);
        contributions.clear();
        Objects.requireNonNull(persisted, "persisted").forEach((player, amount) -> {
            if (player != null && amount != null && amount > 0) contributions.put(player, amount);
        });
    }

    /** Returns the recipe contributions are restricted to, when they are.
     * @return locked recipe, or empty when any matching recipe contributes
     */
    public Optional<ResourceLocation> requiredRecipe() {
        return lockedToRecipe ? Optional.of(recipeId) : Optional.empty();
    }
}
