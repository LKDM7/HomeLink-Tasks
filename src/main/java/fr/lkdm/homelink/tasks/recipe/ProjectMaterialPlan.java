package fr.lkdm.homelink.tasks.recipe;

import fr.lkdm.homecore.api.recipe.RecipeDescriptor;
import fr.lkdm.homelink.tasks.board.TaskBoard;
import fr.lkdm.homelink.tasks.task.TaskCard;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.crafting.Ingredient;

/** A bounded frontier of the selected plan; a derived component replaces its parent's material. */
public record ProjectMaterialPlan(List<Need> needs, boolean incomplete) {
    public static final int MAX_NEEDS = 128;
    public record Need(UUID card, ResourceLocation recipe, int ingredientIndex, Ingredient ingredient, int quantity) { }
    public ProjectMaterialPlan { needs = List.copyOf(needs); }

    public static ProjectMaterialPlan build(TaskBoard board, Function<ResourceLocation, Optional<RecipeDescriptor>> resolver) {
        List<Need> needs = new ArrayList<>();
        boolean incomplete = false;
        for (TaskCard card : board.cards()) {
            if (card.archived() || card.type() != fr.lkdm.homelink.tasks.task.TaskType.CRAFT) continue;
            var objective = card.objective().orElse(null);
            if (objective == null) { incomplete = true; continue; }
            if (objective.complete()) continue;
            var recipe = resolver.apply(objective.recipeId()).orElse(null);
            if (recipe == null || !objective.accepts(recipe.result())) { incomplete = true; continue; }
            int operations = recipe.operationsFor(objective.remaining());
            for (int index = 0; index < recipe.ingredients().size(); index++) {
                final int slot = index;
                var derived = board.cards().stream().filter(child -> !child.archived()
                        && child.derivedIngredient() == slot && child.parent().filter(card.id()::equals).isPresent()).toList();
                boolean replaced = derived.size() == 1 && derived.getFirst().objective()
                        .map(child -> recipe.ingredients().get(slot).ingredient().test(child.target())).orElse(false);
                if (replaced) continue;
                long quantity = (long) operations * recipe.ingredients().get(index).count();
                if (quantity < 1 || quantity > Integer.MAX_VALUE || needs.size() >= MAX_NEEDS) { incomplete = true; continue; }
                needs.add(new Need(card.id(), recipe.recipeId(), index, recipe.ingredients().get(index).ingredient(), (int) quantity));
            }
        }
        return new ProjectMaterialPlan(needs, incomplete);
    }
}
