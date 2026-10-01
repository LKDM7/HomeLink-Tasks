package fr.lkdm.homelink.tasks.client;

import fr.lkdm.homecore.api.recipe.RecipeDescriptor;
import fr.lkdm.homecore.api.recipe.RecipeDescriptors;
import fr.lkdm.homelink.tasks.recipe.RecipeResolver;
import fr.lkdm.homelink.tasks.recipe.VanillaRecipeAdapter;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import net.minecraft.client.Minecraft;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.item.crafting.RecipeHolder;

/**
 * Lists the recipes a client may offer for a wanted item.
 *
 * <p>Recipes come from the recipe manager the server synchronised, and from the same
 * public adapters the server uses, so what a player picks in the editor is something the
 * server will recognise. The server validates the choice again in any case.</p>
 *
 * <p>A recipe type nobody describes is left out here and reported as unsupported, rather
 * than guessed from its name.</p>
 */
public final class ClientRecipeLookup {
    private ClientRecipeLookup() { }

    /** Supported outputs, including component variants, from the current server's recipes. */
    public static List<ItemStack> outputs() {
        Minecraft client = Minecraft.getInstance();
        if (client.level == null) return List.of();
        List<ItemStack> results = new ArrayList<>();
        for (var holder : client.level.getRecipeManager().getRecipes()) {
            describe(holder).ifPresent(recipe -> {
                ItemStack result = recipe.result();
                if (!result.isEmpty() && results.stream().noneMatch(existing -> ItemStack.isSameItemSameComponents(existing, result)))
                    results.add(result.copyWithCount(1));
            });
        }
        results.sort(java.util.Comparator.comparing(stack -> stack.getHoverName().getString(), String.CASE_INSENSITIVE_ORDER));
        return List.copyOf(results);
    }

    public static Optional<RecipeDescriptor> describe(net.minecraft.resources.ResourceLocation id) {
        Minecraft client = Minecraft.getInstance();
        return client.level == null ? Optional.empty() : client.level.getRecipeManager().byKey(id).flatMap(ClientRecipeLookup::describe);
    }

    /**
     * Returns the describable recipes producing a wanted variant.
     *
     * @param wanted count-one prototype of the wanted result
     * @return descriptions, at most {@link RecipeResolver#MAX_ALTERNATIVES}
     */
    public static List<RecipeDescriptor> alternatives(ItemStack wanted) {
        Objects.requireNonNull(wanted, "wanted");
        Minecraft client = Minecraft.getInstance();
        if (wanted.isEmpty() || client.level == null) return List.of();
        List<RecipeDescriptor> found = new ArrayList<>();
        for (RecipeHolder<?> holder : client.level.getRecipeManager().getRecipes()) {
            if (found.size() >= RecipeResolver.MAX_ALTERNATIVES) break;
            describe(holder).filter(described ->
                    ItemStack.isSameItemSameComponents(described.result(), wanted)).ifPresent(found::add);
        }
        return List.copyOf(found);
    }

    private static Optional<RecipeDescriptor> describe(RecipeHolder<? extends Recipe<?>> holder) {
        Optional<RecipeDescriptor> owned = RecipeDescriptors.describe(holder);
        if (owned.isPresent()) return owned;
        Minecraft client = Minecraft.getInstance();
        return client.level == null ? Optional.empty()
                : VanillaRecipeAdapter.describe(holder, client.level.registryAccess());
    }
}
