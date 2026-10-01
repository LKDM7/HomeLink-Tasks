package fr.lkdm.homelink.tasks.recipe;

import fr.lkdm.homecore.api.recipe.RecipeDescriptor;
import fr.lkdm.homecore.api.recipe.RecipeDescriptors;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.item.crafting.RecipeHolder;

/**
 * Finds the real recipes a server currently has loaded.
 *
 * <p>Descriptions come from the running {@code RecipeManager} and from public adapters,
 * never from recipes written into this mod's code. After {@code /reload} or a datapack
 * change the answer is computed again, so a card can never plan against a recipe the
 * server no longer has.</p>
 *
 * <p>A mod that owns a recipe type describes it through HomeCore's registry; crafting
 * table recipes are described here. Anything else is genuinely unsupported and is shown
 * as such rather than approximated.</p>
 */
public final class RecipeResolver {
    /** Largest number of alternative recipes offered for one result. */
    public static final int MAX_ALTERNATIVES = 16;

    private RecipeResolver() { }

    /**
     * Describes one recipe by its identifier, resolving it again from the server.
     *
     * @param server running server
     * @param recipeId recipe to describe
     * @return description, or empty when the recipe is gone or unsupported
     */
    public static Optional<RecipeDescriptor> describe(MinecraftServer server, ResourceLocation recipeId) {
        Objects.requireNonNull(server, "server");
        Objects.requireNonNull(recipeId, "recipeId");
        return server.getRecipeManager().byKey(recipeId).flatMap(holder -> describe(server, holder));
    }

    /**
     * Describes one already resolved recipe.
     *
     * @param server running server
     * @param holder recipe holder from the server's recipe manager
     * @return description, or empty when the recipe cannot be described safely
     */
    public static Optional<RecipeDescriptor> describe(MinecraftServer server,
                                                      RecipeHolder<? extends Recipe<?>> holder) {
        Objects.requireNonNull(server, "server");
        Objects.requireNonNull(holder, "holder");
        // A mod that owns the recipe type describes it; this mod never reimplements that.
        Optional<RecipeDescriptor> owned = RecipeDescriptors.describe(holder);
        if (owned.isPresent()) return owned;
        return VanillaRecipeAdapter.describe(holder, server.registryAccess());
    }

    /**
     * Lists the describable recipes producing a wanted variant.
     *
     * <p>Components are part of the identity, so two items are never merged because they
     * share a name or a texture. The list is bounded, and a result with no describable
     * recipe yields an empty list rather than a guess.</p>
     *
     * @param server running server
     * @param wanted count-one prototype of the wanted result
     * @return descriptions in recipe-manager order, at most {@value #MAX_ALTERNATIVES}
     */
    public static List<RecipeDescriptor> alternatives(MinecraftServer server, ItemStack wanted) {
        Objects.requireNonNull(server, "server");
        Objects.requireNonNull(wanted, "wanted");
        if (wanted.isEmpty()) return List.of();
        List<RecipeDescriptor> found = new ArrayList<>();
        for (RecipeHolder<?> holder : server.getRecipeManager().getRecipes()) {
            if (found.size() >= MAX_ALTERNATIVES) break;
            Optional<RecipeDescriptor> described = describe(server, holder);
            if (described.isEmpty()) continue;
            if (ItemStack.isSameItemSameComponents(described.get().result(), wanted)) found.add(described.get());
        }
        return List.copyOf(found);
    }

    /**
     * Returns the number of operations needed for a remaining quantity.
     *
     * <p>Rounded up, because a partial operation produces nothing: ten items still wanted
     * from a recipe yielding four need three operations, producing twelve of which two
     * are surplus.</p>
     *
     * @param descriptor described recipe
     * @param remaining items still wanted
     * @return operation count
     */
    public static int operations(RecipeDescriptor descriptor, int remaining) {
        return Objects.requireNonNull(descriptor, "descriptor").operationsFor(remaining);
    }
}
