package fr.lkdm.homelink.tasks.client;

import fr.lkdm.homecore.api.recipe.RecipeDescriptor;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.resources.ResourceLocation;

/** Shared ingredient action for the task detail and its recipe preview. */
final class IngredientTaskActions {
    private final UUID board, card;
    private final Map<Integer, List<RecipeDescriptor>> choices = new HashMap<>();
    private ResourceLocation recipeId;
    IngredientTaskActions(UUID board, UUID card) { this.board = board; this.card = card; }

    private RecipeDescriptor recipe() {
        return ClientTaskState.card(card).flatMap(value -> value.objective())
                .flatMap(value -> ClientRecipeLookup.describe(value.recipeId())).orElse(null);
    }

    private List<RecipeDescriptor> choices(int index) {
        var recipe = recipe();
        if (recipe == null || index < 0 || index >= recipe.ingredients().size()) return List.of();
        if (!recipe.recipeId().equals(recipeId)) { choices.clear(); recipeId = recipe.recipeId(); }
        return choices.computeIfAbsent(index, ignored -> {
            var found = new java.util.LinkedHashMap<ResourceLocation, RecipeDescriptor>();
            for (var variant : recipe.ingredients().get(index).ingredient().getItems())
                for (var candidate : ClientRecipeLookup.alternatives(variant)) found.putIfAbsent(candidate.recipeId(), candidate);
            return List.copyOf(found.values());
        });
    }

    boolean canCreate(int index) {
        var view = ClientTaskState.card(card).orElse(null);
        if (view == null || view.archived() || !ClientCardPermissions.editor()
                || view.objective().isEmpty() || view.objective().orElseThrow().remaining() == 0) return false;
        if (ClientTaskState.board().orElseThrow().cards().stream().anyMatch(child ->
                child.parent().filter(card::equals).isPresent() && child.derivedIngredient() == index)) return false;
        return !choices(index).isEmpty();
    }

    void create(int index, Screen parent) {
        if (!canCreate(index)) return;
        var recipes = choices(index);
        if (recipes.size() == 1) {
            var selected = recipes.getFirst();
            TaskClientNetwork.expand(board, card, index, selected.result().copyWithCount(1), selected.recipeId(),
                    ClientTaskState.card(card).orElseThrow().revision());
        } else Minecraft.getInstance().setScreen(new RecipeExpansionScreen(board, card, index, recipe(), parent));
    }
}
