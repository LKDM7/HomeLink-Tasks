package fr.lkdm.homelink.tasks.recipe;

import fr.lkdm.homecore.api.recipe.RecipeDescriptor;
import fr.lkdm.homecore.api.recipe.RecipeGrid;
import fr.lkdm.homecore.api.recipe.RecipeIngredient;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.NonNullList;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.world.item.crafting.ShapedRecipe;
import net.minecraft.world.item.crafting.ShapelessRecipe;

/**
 * Describes the crafting table recipes this version supports.
 *
 * <p>Only ordinary shaped and shapeless recipes are described, which is exactly what a
 * 2×2 inventory grid and a crafting table produce. A special recipe computes its result
 * at craft time from its inputs, so it has no fixed description and is reported as
 * unsupported rather than guessed.</p>
 *
 * <p>Smelting, brewing, anvils, villager trades and third-party machines are outside
 * automatic tracking in this version. They are not silently turned into crafts.</p>
 */
public final class VanillaRecipeAdapter {
    /** Workstation reported for every described crafting recipe. */
    public static final ResourceLocation CRAFTING_TABLE =
            ResourceLocation.fromNamespaceAndPath("minecraft", "crafting_table");
    /** Largest number of accepted items an ingredient may expand to before merging is abandoned. */
    public static final int MAX_EXPANSION = 1024;

    private VanillaRecipeAdapter() { }

    /**
     * Describes one crafting recipe.
     *
     * @param holder recipe resolved from the current server recipe manager
     * @param registries registry access of the running server
     * @return description, or empty when the recipe cannot be described safely
     */
    public static Optional<RecipeDescriptor> describe(RecipeHolder<? extends Recipe<?>> holder,
                                                      HolderLookup.Provider registries) {
        Objects.requireNonNull(holder, "holder");
        Objects.requireNonNull(registries, "registries");
        Recipe<?> recipe = holder.value();
        if (recipe.isSpecial()) return Optional.empty();
        if (!(recipe instanceof ShapedRecipe) && !(recipe instanceof ShapelessRecipe)) return Optional.empty();

        ItemStack result = recipe.getResultItem(registries);
        if (result.isEmpty()) return Optional.empty();
        NonNullList<Ingredient> slots = recipe.getIngredients();
        if (slots.isEmpty()) return Optional.empty();

        // Slots holding the same predicate become one counted requirement, which is what a
        // planner and a player both need: "four planks", not "a plank" repeated four times.
        Map<String, Integer> indexByKey = new LinkedHashMap<>();
        List<Ingredient> distinct = new ArrayList<>();
        List<Integer> counts = new ArrayList<>();
        List<Integer> slotToRequirement = new ArrayList<>(slots.size());
        for (Ingredient slot : slots) {
            if (slot.isEmpty()) {
                slotToRequirement.add(-1);
                continue;
            }
            String key = key(slot);
            Integer existing = key == null ? null : indexByKey.get(key);
            if (existing == null) {
                existing = distinct.size();
                distinct.add(slot);
                counts.add(0);
                if (key != null) indexByKey.put(key, existing);
            }
            counts.set(existing, counts.get(existing) + 1);
            slotToRequirement.add(existing);
        }
        if (distinct.isEmpty() || distinct.size() > RecipeDescriptor.MAX_INGREDIENTS) return Optional.empty();

        List<RecipeIngredient> requirements = new ArrayList<>(distinct.size());
        for (int index = 0; index < distinct.size(); index++) {
            requirements.add(new RecipeIngredient(distinct.get(index), counts.get(index)));
        }
        Optional<RecipeGrid> grid = Optional.empty();
        if (recipe instanceof ShapedRecipe shaped) {
            int width = shaped.getWidth();
            int height = shaped.getHeight();
            if (width >= 1 && height >= 1 && width * height == slotToRequirement.size()) {
                grid = Optional.of(new RecipeGrid(width, height, slotToRequirement));
            }
        }
        int batch = Math.max(result.getCount(), Math.min(64, result.getMaxStackSize()));
        return Optional.of(new RecipeDescriptor(holder.id(), CRAFTING_TABLE, requirements, result, batch, grid));
    }

    /**
     * Builds a stable key for an ingredient from the items it actually accepts.
     *
     * @param ingredient ingredient to key
     * @return key, or null when the ingredient expands too widely to compare cheaply
     */
    private static String key(Ingredient ingredient) {
        ItemStack[] accepted = ingredient.getItems();
        if (accepted.length == 0 || accepted.length > MAX_EXPANSION) return null;
        List<String> names = new ArrayList<>(accepted.length);
        for (ItemStack stack : accepted) {
            names.add(net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(stack.getItem()).toString());
        }
        names.sort(null);
        return String.join(",", names);
    }
}
